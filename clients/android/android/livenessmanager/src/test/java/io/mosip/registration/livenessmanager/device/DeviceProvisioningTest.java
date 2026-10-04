package io.mosip.registration.livenessmanager.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

/**
 * In-app provisioning of the built-in face device key: the certificate request the app writes, the import of the
 * chain the CA returns, and the refusal of chains for another key or from another CA.
 *
 * <p>The certificates and the device key in {@code src/test/resources/device-provisioning/} are throwaway test
 * fixtures (100-year validity, CAs named "Device Test CA"), trusted nowhere. {@code device-1.key} stands in for
 * the Android Keystore key, which unit tests cannot reach.
 */
public class DeviceProvisioningTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final byte[] SHA256_WITH_RSA_OID = {
            0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File folder;
    private FakeKey key;
    private DeviceProvisioning provisioning;

    @Before
    public void setUp() throws Exception {
        folder = tmp.newFolder("liveness");
        key = new FakeKey(privateKey("device-1.key"), cert("device-1-self.pem"));
        provisioning = new DeviceProvisioning(key, folder, CLOCK);
    }

    // ---------------------------------------------------------------- certificate request

    @Test
    public void requestIsPkcs10ForTheDeviceKeySignedByIt() throws Exception {
        provisioning.writeRequest();

        byte[] csr = pemBody(new File(folder, DeviceProvisioning.REQUEST_FILE), "CERTIFICATE REQUEST");
        Der request = Der.read(csr, 0);
        List<Der> parts = request.children();
        assertEquals(3, parts.size());
        Der info = parts.get(0);
        List<Der> fields = info.children();
        assertArrayEquals("version 0", new byte[]{0}, fields.get(0).content());
        X500Principal subject = new X500Principal(fields.get(1).encoded());
        assertEquals(cert("device-1-self.pem").getSubjectX500Principal(), subject);
        assertArrayEquals("public key of the device key", key.chain().get(0).getPublicKey().getEncoded(),
                fields.get(2).encoded());
        assertEquals("empty attributes [0]", 0xA0, fields.get(3).tag());
        assertArrayEquals(SHA256_WITH_RSA_OID, parts.get(1).children().get(0).encoded());

        byte[] bits = parts.get(2).content();
        assertEquals("no unused bits", 0, bits[0]);
        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(key.chain().get(0).getPublicKey());
        verify.update(info.encoded());
        assertTrue("signed by the device key", verify.verify(Arrays.copyOfRange(bits, 1, bits.length)));
    }

    @Test
    public void runWritesRequestAndStatusWithoutWaitingChain() throws Exception {
        DeviceProvisioning.Outcome outcome = provisioning.run("req-1", false);

        assertEquals(DeviceProvisioning.Outcome.Kind.NONE, outcome.kind());
        assertTrue(new File(folder, DeviceProvisioning.REQUEST_FILE).isFile());
        Map<String, String> status = status();
        assertEquals(DeviceProvisioning.STATE_NOT_PROVISIONED, status.get("state"));
        assertEquals("req-1", status.get("request"));
        assertEquals("NONE", status.get("last_import"));
        assertEquals(status.get("subject"), status.get("issuer"));
        assertFalse(new File(folder, DeviceProvisioning.STATUS_FILE + ".tmp").exists());
    }

    @Test
    public void requestIdIsCleanedInStatus() throws Exception {
        provisioning.run("a b\nstate=PROVISIONED", false);

        Map<String, String> status = status();
        assertEquals("abstatePROVISIONED", status.get("request"));
        assertEquals(DeviceProvisioning.STATE_NOT_PROVISIONED, status.get("state"));
    }

    // ---------------------------------------------------------------- chain import

    @Test
    public void importsChainFromTheCaForTheDeviceKey() throws Exception {
        waitingChain("device-1-by-ca-a.pem", "ca-a.pem");

        DeviceProvisioning.Outcome outcome = provisioning.run("req-2", false);

        assertEquals(DeviceProvisioning.Outcome.Kind.ACCEPTED, outcome.kind());
        assertEquals(Arrays.asList(cert("device-1-by-ca-a.pem"), cert("ca-a.pem")), key.chain());
        assertFalse("chain file deleted", new File(folder, DeviceProvisioning.CHAIN_FILE).exists());
        Map<String, String> status = status();
        assertEquals(DeviceProvisioning.STATE_PROVISIONED, status.get("state"));
        assertEquals("req-2", status.get("request"));
        assertEquals("ACCEPTED", status.get("last_import"));
        assertEquals("2", status.get("chain_length"));
        assertEquals(cert("ca-a.pem").getSubjectX500Principal().getName(), status.get("issuer"));
        assertTrue(DeviceProvisioning.isCaIssued(key.chain()));
    }

    @Test
    public void importsChainWithTextBetweenCertificates() throws Exception {
        String pem = "Bag Attributes\n    friendlyName: device\nsubject=CN = BUILTIN-TEST0001\n"
                + new String(resource("device-1-by-ca-a.pem"), StandardCharsets.US_ASCII)
                + "Bag Attributes\n    friendlyName: ca\nissuer=CN = Device Test CA A\n"
                + new String(resource("ca-a.pem"), StandardCharsets.US_ASCII);
        Files.write(new File(folder, DeviceProvisioning.CHAIN_FILE).toPath(),
                pem.getBytes(StandardCharsets.US_ASCII));

        assertEquals(DeviceProvisioning.Outcome.Kind.ACCEPTED, provisioning.run("bags", false).kind());
        assertEquals(Arrays.asList(cert("device-1-by-ca-a.pem"), cert("ca-a.pem")), key.chain());
    }

    @Test
    public void requestAfterImportStillNamesTheSameKey() throws Exception {
        waitingChain("device-1-by-ca-a.pem", "ca-a.pem");
        provisioning.run("req-3", false);

        byte[] csr = pemBody(new File(folder, DeviceProvisioning.REQUEST_FILE), "CERTIFICATE REQUEST");
        assertArrayEquals(cert("device-1-self.pem").getPublicKey().getEncoded(),
                Der.read(csr, 0).children().get(0).children().get(2).encoded());
    }

    @Test
    public void refusesCertificateForAnotherKey() throws Exception {
        waitingChain("device-2-by-ca-a.pem", "ca-a.pem");

        DeviceProvisioning.Outcome outcome = provisioning.run("req-4", false);

        assertRefused(outcome, DeviceProvisioning.Refusal.KEY_MISMATCH);
        assertEquals(Collections.singletonList(cert("device-1-self.pem")), key.chain());
        assertFalse(new File(folder, DeviceProvisioning.CHAIN_FILE).exists());
        Map<String, String> status = status();
        assertEquals(DeviceProvisioning.STATE_NOT_PROVISIONED, status.get("state"));
        assertEquals("REFUSED", status.get("last_import"));
        assertEquals("KEY_MISMATCH", status.get("reason"));
    }

    @Test
    public void refusesCertificateFromAnotherCaOnceProvisioned() throws Exception {
        waitingChain("device-1-by-ca-a.pem", "ca-a.pem");
        provisioning.run("first", false);
        waitingChain("device-1-by-ca-b.pem", "ca-b.pem");

        DeviceProvisioning.Outcome outcome = provisioning.run("second", false);

        assertRefused(outcome, DeviceProvisioning.Refusal.UNTRUSTED_CA);
        assertEquals(Arrays.asList(cert("device-1-by-ca-a.pem"), cert("ca-a.pem")), key.chain());
        assertEquals("UNTRUSTED_CA", status().get("reason"));
        assertEquals(DeviceProvisioning.STATE_PROVISIONED, status().get("state"));
    }

    @Test
    public void acceptsAnotherCaOnlyWhenTheRequestAllowsIt() throws Exception {
        waitingChain("device-1-by-ca-a.pem", "ca-a.pem");
        provisioning.run("first", false);
        waitingChain("device-1-by-ca-b.pem", "ca-b.pem");

        DeviceProvisioning.Outcome outcome = provisioning.run("replace", true);

        assertEquals(DeviceProvisioning.Outcome.Kind.ACCEPTED, outcome.kind());
        assertEquals(Arrays.asList(cert("device-1-by-ca-b.pem"), cert("ca-b.pem")), key.chain());
    }

    @Test
    public void sameCaCanIssueANewCertificate() throws Exception {
        waitingChain("device-1-by-ca-a.pem", "ca-a.pem");
        provisioning.run("first", false);
        waitingChain("device-1-by-ca-a.pem", "ca-a.pem");

        assertEquals(DeviceProvisioning.Outcome.Kind.ACCEPTED, provisioning.run("again", false).kind());
    }

    @Test
    public void caCheckWorksWhenTheKeyStoreKeepsOnlyTheDeviceCertificate() throws Exception {
        key.keepOnlyLeaf = true;
        waitingChain("device-1-by-ca-a.pem", "ca-a.pem");
        provisioning.run("first", false);
        assertEquals(Collections.singletonList(cert("device-1-by-ca-a.pem")), key.chain());

        waitingChain("device-1-by-ca-a.pem", "ca-a.pem");
        assertEquals(DeviceProvisioning.Outcome.Kind.ACCEPTED, provisioning.run("same-ca", false).kind());
        waitingChain("device-1-by-ca-b.pem", "ca-b.pem");
        assertRefused(provisioning.run("other-ca", false), DeviceProvisioning.Refusal.UNTRUSTED_CA);
        assertEquals(Collections.singletonList(cert("device-1-by-ca-a.pem")), key.chain());
    }

    @Test
    public void refusesCertificateNotIssuedByTheCaInTheChain() throws Exception {
        waitingChain("device-1-by-ca-b.pem", "ca-a.pem");

        assertRefused(provisioning.run("req-5", false), DeviceProvisioning.Refusal.NOT_ISSUED_BY_CA);
        assertEquals(Collections.singletonList(cert("device-1-self.pem")), key.chain());
    }

    @Test
    public void refusesIssuerThatIsNotACa() throws Exception {
        waitingChain("device-1-by-ca-c.pem", "ca-c.pem");

        assertRefused(provisioning.run("req-6", false), DeviceProvisioning.Refusal.NOT_A_CA);
    }

    @Test
    public void refusesDeviceCertificateWithoutTheCa() throws Exception {
        waitingChain("device-1-by-ca-a.pem");

        assertRefused(provisioning.run("req-7", false), DeviceProvisioning.Refusal.NO_CA_CERTIFICATE);
    }

    @Test
    public void refusesCertificateThatMayNotSign() throws Exception {
        waitingChain("device-1-keyenc-by-ca-a.pem", "ca-a.pem");

        assertRefused(provisioning.run("req-8", false), DeviceProvisioning.Refusal.NOT_FOR_SIGNING);
    }

    @Test
    public void refusesUnreadableFileAndDeletesIt() throws Exception {
        Files.write(new File(folder, DeviceProvisioning.CHAIN_FILE).toPath(),
                "not a certificate".getBytes(StandardCharsets.UTF_8));

        assertRefused(provisioning.run("req-9", false), DeviceProvisioning.Refusal.UNREADABLE);
        assertFalse(new File(folder, DeviceProvisioning.CHAIN_FILE).exists());
    }

    @Test
    public void keyStoreFailureKeepsTheChainForTheNextTry() throws Exception {
        waitingChain("device-1-by-ca-a.pem", "ca-a.pem");
        key.failInstall = true;

        try {
            provisioning.importWaitingChain(false);
            fail("key store failure must surface");
        } catch (GeneralSecurityException expected) {
            assertTrue(new File(folder, DeviceProvisioning.CHAIN_FILE).isFile());
        }
        key.failInstall = false;
        assertEquals(DeviceProvisioning.Outcome.Kind.ACCEPTED, provisioning.importWaitingChain(false).kind());
    }

    // ---------------------------------------------------------------- chain check

    @Test
    public void checkRefusesChainFromAnotherCaThanTheExpectedOne() throws Exception {
        List<X509Certificate> chain = Arrays.asList(cert("device-1-by-ca-b.pem"), cert("ca-b.pem"));
        PublicKey device = cert("device-1-self.pem").getPublicKey();

        try {
            DeviceProvisioning.checkChain(chain, device, Collections.singletonList(cert("ca-a.pem")), now());
            fail("chain from CA B accepted with CA A expected");
        } catch (DeviceProvisioning.RefusedException e) {
            assertEquals(DeviceProvisioning.Refusal.UNTRUSTED_CA, e.reason());
        }
        DeviceProvisioning.checkChain(chain, device, Collections.singletonList(cert("ca-b.pem")), now());
    }

    @Test
    public void checkAcceptsDeviceCertificateIssuedByAnExpectedCaWithoutTheCaInTheChain() throws Exception {
        DeviceProvisioning.checkChain(Collections.singletonList(cert("device-1-by-ca-a.pem")),
                cert("device-1-self.pem").getPublicKey(), Collections.singletonList(cert("ca-a.pem")), now());
    }

    @Test
    public void checkRefusesExpiredCertificates() throws Exception {
        List<X509Certificate> chain = Arrays.asList(cert("device-1-by-ca-a.pem"), cert("ca-a.pem"));
        try {
            DeviceProvisioning.checkChain(chain, cert("device-1-self.pem").getPublicKey(),
                    Collections.<X509Certificate>emptyList(), Date.from(Instant.parse("2200-01-01T00:00:00Z")));
            fail("expired chain accepted");
        } catch (DeviceProvisioning.RefusedException e) {
            assertEquals(DeviceProvisioning.Refusal.NOT_VALID_NOW, e.reason());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Date now() {
        return Date.from(CLOCK.instant());
    }

    private static void assertRefused(DeviceProvisioning.Outcome outcome, DeviceProvisioning.Refusal reason) {
        assertEquals(DeviceProvisioning.Outcome.Kind.REFUSED, outcome.kind());
        assertEquals(reason, outcome.refusal());
        assertTrue(outcome.detail(), outcome.detail().startsWith(reason.name()));
    }

    private void waitingChain(String... resources) throws IOException {
        StringBuilder pem = new StringBuilder();
        for (String r : resources) {
            pem.append(new String(resource(r), StandardCharsets.US_ASCII));
        }
        Files.write(new File(folder, DeviceProvisioning.CHAIN_FILE).toPath(),
                pem.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private Map<String, String> status() throws IOException {
        Map<String, String> map = new HashMap<>();
        for (String line : Files.readAllLines(new File(folder, DeviceProvisioning.STATUS_FILE).toPath(),
                StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            map.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return map;
    }

    private static X509Certificate cert(String name) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(resource(name)));
    }

    private static PrivateKey privateKey(String name) throws Exception {
        String pem = new String(resource(name), StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private static byte[] pemBody(File file, String type) throws IOException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII);
        assertTrue(text.startsWith("-----BEGIN " + type + "-----\n"));
        assertTrue(text.endsWith("-----END " + type + "-----\n"));
        String body = text.replace("-----BEGIN " + type + "-----", "").replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = DeviceProvisioningTest.class.getResourceAsStream("/device-provisioning/" + name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** The device key of the tests: a software RSA key with a replaceable certificate chain. */
    private static final class FakeKey implements DeviceProvisioning.DeviceKey {
        private final PrivateKey privateKey;
        private List<X509Certificate> chain;
        boolean failInstall;
        /** Like an Android Keystore that stores the device certificate without its issuers. */
        boolean keepOnlyLeaf;

        FakeKey(PrivateKey privateKey, X509Certificate selfSigned) {
            this.privateKey = privateKey;
            this.chain = Collections.singletonList(selfSigned);
        }

        @Override
        public List<X509Certificate> chain() {
            return chain;
        }

        @Override
        public byte[] sign(byte[] data) throws GeneralSecurityException {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(privateKey);
            s.update(data);
            return s.sign();
        }

        @Override
        public void installChain(List<X509Certificate> newChain) throws GeneralSecurityException {
            if (failInstall) {
                throw new GeneralSecurityException("key store unavailable");
            }
            chain = keepOnlyLeaf ? Collections.singletonList(newChain.get(0)) : new ArrayList<>(newChain);
        }
    }

    /** A minimal DER reader for the certificate request (definite lengths only). */
    private static final class Der {
        private final byte[] data;
        private final int start;
        private final int contentStart;
        private final int end;

        private Der(byte[] data, int start, int contentStart, int end) {
            this.data = data;
            this.start = start;
            this.contentStart = contentStart;
            this.end = end;
        }

        static Der read(byte[] data, int offset) {
            int p = offset + 1;
            int length = data[p++] & 0xFF;
            if (length >= 0x80) {
                int bytes = length & 0x7F;
                length = 0;
                for (int i = 0; i < bytes; i++) {
                    length = (length << 8) | (data[p++] & 0xFF);
                }
            }
            return new Der(data, offset, p, p + length);
        }

        int tag() {
            return data[start] & 0xFF;
        }

        byte[] encoded() {
            return Arrays.copyOfRange(data, start, end);
        }

        byte[] content() {
            return Arrays.copyOfRange(data, contentStart, end);
        }

        List<Der> children() {
            List<Der> list = new ArrayList<>();
            for (int p = contentStart; p < end; ) {
                Der child = read(data, p);
                list.add(child);
                p = child.end;
            }
            return list;
        }
    }
}
