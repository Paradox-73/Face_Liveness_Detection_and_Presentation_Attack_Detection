package io.mosip.registration.livenessmanager.device;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.security.auth.x500.X500Principal;

/**
 * In-app provisioning of the built-in face device key, for release and debug builds alike.
 *
 * <p>The key stays in the Android Keystore. The exchange goes through the app-specific external files folder
 * ({@code Android/data/<package>/files/liveness/}), which {@code adb} can read and write on every build type:
 * <ol>
 *   <li>The app writes a PKCS#10 certificate request for its Keystore key ({@value #REQUEST_FILE}, PEM, signed
 *   with that key) and its status ({@value #STATUS_FILE}).</li>
 *   <li>The device certificate authority signs the request and places the certificate chain (device certificate
 *   first, then its issuers, PEM) in {@value #CHAIN_FILE}. {@code scripts/android-liveness-device-cert.sh --csr}
 *   does this with MockSync's DEVICE CA.</li>
 *   <li>Before the chain is used, {@link #checkChain} checks that the device certificate holds the public key
 *   of the Keystore key, may sign, is valid now, and was issued by the CA in the chain (or by one of the expected
 *   CAs); then the chain replaces the certificate of the Keystore key. The file is deleted either way and the
 *   outcome is written to the status file.</li>
 * </ol>
 *
 * <p>Once the device holds a certificate issued by a CA, a chain from a different CA is refused
 * ({@link Refusal#UNTRUSTED_CA}) unless the provisioning request explicitly allows a new CA. ARC's own DEVICE
 * trust check at capture time stays the final authority: a CA that ARC does not trust gives a certificate the app
 * imports but ARC rejects.
 */
public final class DeviceProvisioning {

    /** The certificate request the app writes (PEM). */
    public static final String REQUEST_FILE = "device.csr";
    /** The certificate chain the CA returns (PEM, device certificate first). */
    public static final String CHAIN_FILE = "device-chain.pem";
    /** The provisioning status the app writes (UTF-8 lines {@code name=value}). */
    public static final String STATUS_FILE = "status.txt";

    /** Status value: the device certificate was issued by a CA. */
    public static final String STATE_PROVISIONED = "PROVISIONED";
    /** Status value: the device key has a self-signed certificate. */
    public static final String STATE_NOT_PROVISIONED = "NOT_PROVISIONED";
    /** Status value: the key store could not be read. */
    public static final String STATE_ERROR = "ERROR";

    private static final byte[] SHA256_WITH_RSA = {
            0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final int KEY_USAGE_DIGITAL_SIGNATURE = 0;
    private static final String PEM_BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String PEM_END = "-----END CERTIFICATE-----";

    /** Why a certificate chain was refused. */
    public enum Refusal {
        /** The file holds no readable X.509 certificate. */
        UNREADABLE,
        /** The device certificate holds the public key of another key. */
        KEY_MISMATCH,
        /** The device certificate may not sign (key usage without digitalSignature) or is itself a CA. */
        NOT_FOR_SIGNING,
        /** A certificate is expired or not yet valid. */
        NOT_VALID_NOW,
        /** A certificate is not signed by the next certificate of the chain. */
        NOT_ISSUED_BY_CA,
        /** An issuer in the chain is not a certificate authority (basic constraints). */
        NOT_A_CA,
        /** The chain ends without a self-signed CA certificate and no expected CA issued it. */
        NO_CA_CERTIFICATE,
        /** The chain does not lead to an expected CA. */
        UNTRUSTED_CA
    }

    /** A chain refused by {@link #checkChain}. */
    public static final class RefusedException extends GeneralSecurityException {
        private static final long serialVersionUID = 1L;
        private final Refusal reason;

        RefusedException(Refusal reason, String message) {
            super(reason + ": " + message);
            this.reason = reason;
        }

        /**
         * Returns the reason.
         *
         * @return why the chain was refused
         */
        public Refusal reason() {
            return reason;
        }
    }

    /** The device key the provisioning works on. */
    public interface DeviceKey {

        /**
         * Returns the certificate chain of the key, device certificate first; its first certificate holds the
         * public key.
         *
         * @return the chain, never empty
         * @throws GeneralSecurityException if the key store cannot be read
         */
        List<X509Certificate> chain() throws GeneralSecurityException;

        /**
         * Signs with the key (RSA PKCS#1 v1.5, SHA-256).
         *
         * @param data bytes to sign
         * @return the signature
         * @throws GeneralSecurityException if the key cannot sign
         */
        byte[] sign(byte[] data) throws GeneralSecurityException;

        /**
         * Replaces the certificate chain of the key; the private key stays where it is.
         *
         * @param chain checked chain, device certificate first
         * @throws GeneralSecurityException if the key store refuses it
         */
        void installChain(List<X509Certificate> chain) throws GeneralSecurityException;
    }

    /** Result of looking for a waiting chain. */
    public static final class Outcome {

        /** What happened. */
        public enum Kind {
            /** No chain was waiting. */
            NONE,
            /** A chain was checked and installed. */
            ACCEPTED,
            /** A chain was refused; the key kept its certificate. */
            REFUSED
        }

        private static final Outcome NONE = new Outcome(Kind.NONE, null, "");
        private final Kind kind;
        private final Refusal refusal;
        private final String detail;

        private Outcome(Kind kind, Refusal refusal, String detail) {
            this.kind = kind;
            this.refusal = refusal;
            this.detail = detail;
        }

        /**
         * Returns what happened.
         *
         * @return the kind
         */
        public Kind kind() {
            return kind;
        }

        /**
         * Returns the reason of a refusal.
         *
         * @return the reason, or {@code null} unless {@link Kind#REFUSED}
         */
        public Refusal refusal() {
            return refusal;
        }

        /**
         * Returns a one-line explanation.
         *
         * @return the explanation, empty for {@link Kind#NONE}
         */
        public String detail() {
            return detail;
        }
    }

    private final DeviceKey key;
    private final File folder;
    private final Clock clock;

    /**
     * Creates the provisioning of one device key.
     *
     * @param key    the device key
     * @param folder exchange folder (app-specific external files, {@code liveness/})
     * @param clock  clock for validity checks and the status time
     */
    public DeviceProvisioning(DeviceKey key, File folder, Clock clock) {
        this.key = Objects.requireNonNull(key, "key");
        this.folder = Objects.requireNonNull(folder, "folder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the exchange folder.
     *
     * @return the folder
     */
    public File folder() {
        return folder;
    }

    /**
     * Runs one provisioning step: imports a waiting chain, writes a fresh certificate request for the current
     * key, and writes the status.
     *
     * @param requestId  identifier echoed in the status file, so the caller can tell this run's status from an
     *                   older one (letters, digits and '-', at most 64; anything else is dropped)
     * @param allowNewCa {@code true} to accept a chain from another CA than the one of the current certificate
     * @return the import outcome
     * @throws GeneralSecurityException if the key store cannot be read
     * @throws IOException              if the folder cannot be written
     */
    public Outcome run(String requestId, boolean allowNewCa) throws GeneralSecurityException, IOException {
        Outcome outcome = importWaitingChain(allowNewCa);
        writeRequest();
        writeStatus(requestId, outcome);
        return outcome;
    }

    /**
     * Imports {@value #CHAIN_FILE} when it is waiting: checks it against the key and the CA, installs it, and
     * deletes the file whether it was accepted or refused.
     *
     * @param allowNewCa {@code true} to accept a chain from another CA than the one of the current certificate
     * @return the outcome
     * @throws GeneralSecurityException if the key store cannot be read or refuses the checked chain
     */
    public Outcome importWaitingChain(boolean allowNewCa) throws GeneralSecurityException {
        File file = new File(folder, CHAIN_FILE);
        if (!file.isFile()) {
            return Outcome.NONE;
        }
        List<X509Certificate> current = key.chain();
        List<X509Certificate> chain;
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            chain = parseChain(data);
            checkChain(chain, current.get(0).getPublicKey(), Collections.<X509Certificate>emptyList(),
                    Date.from(clock.instant()));
            if (!allowNewCa && isCaIssued(current)) {
                checkSameCa(chain, current.get(0));
            }
        } catch (IOException e) {
            delete(file);
            return new Outcome(Outcome.Kind.REFUSED, Refusal.UNREADABLE, "cannot read " + CHAIN_FILE);
        } catch (RefusedException e) {
            delete(file);
            return new Outcome(Outcome.Kind.REFUSED, e.reason(), e.getMessage());
        }
        // A key store failure leaves the file in place, so the next capture or request tries again.
        key.installChain(chain);
        delete(file);
        return new Outcome(Outcome.Kind.ACCEPTED, null,
                "issued by " + chain.get(0).getIssuerX500Principal().getName());
    }

    /**
     * Writes {@value #REQUEST_FILE}: a PKCS#10 request for the current key, with the subject of its current
     * certificate, signed by the key.
     *
     * @throws GeneralSecurityException if the key cannot sign
     * @throws IOException              if the file cannot be written
     */
    public void writeRequest() throws GeneralSecurityException, IOException {
        List<X509Certificate> chain = key.chain();
        X509Certificate leaf = chain.get(0);
        byte[] der = certificationRequest(leaf.getSubjectX500Principal(), leaf.getPublicKey(), key);
        writeAtomically(new File(folder, REQUEST_FILE), pem("CERTIFICATE REQUEST", der));
    }

    /**
     * Writes {@value #STATUS_FILE} with the current certificate and the outcome of the last import.
     *
     * @param requestId identifier echoed in the file (see {@link #run})
     * @param last      outcome of the last import
     * @throws GeneralSecurityException if the key store cannot be read
     * @throws IOException              if the file cannot be written
     */
    public void writeStatus(String requestId, Outcome last) throws GeneralSecurityException, IOException {
        List<X509Certificate> chain = key.chain();
        X509Certificate leaf = chain.get(0);
        Map<String, String> status = new LinkedHashMap<>();
        status.put("state", isCaIssued(chain) ? STATE_PROVISIONED : STATE_NOT_PROVISIONED);
        status.put("request", cleanRequestId(requestId));
        status.put("time", clock.instant().toString());
        status.put("subject", leaf.getSubjectX500Principal().getName());
        status.put("issuer", leaf.getIssuerX500Principal().getName());
        status.put("not_after", leaf.getNotAfter().toInstant().toString());
        status.put("chain_length", String.valueOf(chain.size()));
        status.put("last_import", last.kind().name());
        status.put("reason", last.refusal() == null ? "" : last.refusal().name());
        status.put("detail", oneLine(last.detail()));
        writeStatusMap(folder, status);
    }

    /**
     * Writes a status file for a failure before the key could be read.
     *
     * @param folder    exchange folder
     * @param requestId identifier echoed in the file
     * @param detail    what went wrong
     * @param clock     clock for the time stamp
     * @throws IOException if the file cannot be written
     */
    public static void writeErrorStatus(File folder, String requestId, String detail, Clock clock)
            throws IOException {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("state", STATE_ERROR);
        status.put("request", cleanRequestId(requestId));
        status.put("time", clock.instant().toString());
        status.put("detail", oneLine(detail));
        writeStatusMap(folder, status);
    }

    /**
     * Tells whether a chain starts with a certificate issued by another party (not self-signed).
     *
     * @param chain the chain, device certificate first
     * @return {@code true} when provisioned by a CA
     */
    public static boolean isCaIssued(List<X509Certificate> chain) {
        return !chain.isEmpty()
                && !chain.get(0).getIssuerX500Principal().equals(chain.get(0).getSubjectX500Principal());
    }

    /**
     * Reads PEM or DER certificates, device certificate first.
     *
     * @param data file content
     * @return the certificates
     * @throws RefusedException {@link Refusal#UNREADABLE} when there is none or one cannot be parsed
     */
    public static List<X509Certificate> parseChain(byte[] data) throws RefusedException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            String text = new String(data, StandardCharsets.US_ASCII);
            if (text.contains(PEM_BEGIN)) {
                // Each block on its own: Android's certificate factory stops at text between blocks, such as the
                // "Bag Attributes" lines OpenSSL writes for certificates taken from a PKCS#12 file.
                for (int at = text.indexOf(PEM_BEGIN); at >= 0; at = text.indexOf(PEM_BEGIN, at + 1)) {
                    int end = text.indexOf(PEM_END, at);
                    if (end < 0) {
                        throw new RefusedException(Refusal.UNREADABLE, "unterminated certificate in " + CHAIN_FILE);
                    }
                    byte[] der = Base64.getMimeDecoder().decode(text.substring(at + PEM_BEGIN.length(), end));
                    chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
                }
            } else {
                for (java.security.cert.Certificate c : factory.generateCertificates(new ByteArrayInputStream(data))) {
                    chain.add((X509Certificate) c);
                }
            }
            if (chain.isEmpty()) {
                throw new RefusedException(Refusal.UNREADABLE, "no certificate in " + CHAIN_FILE);
            }
            return chain;
        } catch (CertificateException | RuntimeException e) {
            throw new RefusedException(Refusal.UNREADABLE, "cannot parse " + CHAIN_FILE);
        }
    }

    /**
     * Checks a certificate chain before it is given to the device key.
     *
     * @param chain       the chain, device certificate first, then each issuer
     * @param devicePublicKey public key of the Keystore key
     * @param expectedCas CA certificates the chain must lead to; empty to accept the self-signed CA at the end of
     *                    the chain
     * @param now         time for the validity check
     * @throws RefusedException with the reason when the chain must not be used
     */
    public static void checkChain(List<X509Certificate> chain, PublicKey devicePublicKey,
                                  Collection<X509Certificate> expectedCas, Date now) throws RefusedException {
        if (chain.isEmpty()) {
            throw new RefusedException(Refusal.UNREADABLE, "empty chain");
        }
        X509Certificate leaf = chain.get(0);
        if (!Arrays.equals(leaf.getPublicKey().getEncoded(), devicePublicKey.getEncoded())) {
            throw new RefusedException(Refusal.KEY_MISMATCH,
                    "the certificate " + leaf.getSubjectX500Principal().getName() + " is for another key");
        }
        boolean[] usage = leaf.getKeyUsage();
        if (leaf.getBasicConstraints() >= 0 || (usage != null && !usage[KEY_USAGE_DIGITAL_SIGNATURE])) {
            throw new RefusedException(Refusal.NOT_FOR_SIGNING, "the device certificate may not sign captures");
        }
        for (X509Certificate c : chain) {
            try {
                c.checkValidity(now);
            } catch (CertificateException e) {
                throw new RefusedException(Refusal.NOT_VALID_NOW,
                        c.getSubjectX500Principal().getName() + " is not valid at " + now.toInstant());
            }
        }
        for (int i = 1; i < chain.size(); i++) {
            X509Certificate issuer = chain.get(i);
            if (issuer.getBasicConstraints() < 0) {
                throw new RefusedException(Refusal.NOT_A_CA,
                        issuer.getSubjectX500Principal().getName() + " is not a certificate authority");
            }
            if (!issuedBy(chain.get(i - 1), issuer)) {
                throw new RefusedException(Refusal.NOT_ISSUED_BY_CA, chain.get(i - 1).getSubjectX500Principal()
                        .getName() + " was not issued by " + issuer.getSubjectX500Principal().getName());
            }
        }
        X509Certificate top = chain.get(chain.size() - 1);
        if (expectedCas.isEmpty()) {
            if (chain.size() < 2 || !issuedBy(top, top)) {
                throw new RefusedException(Refusal.NO_CA_CERTIFICATE,
                        "the chain does not end with the self-signed certificate of its CA");
            }
            return;
        }
        for (X509Certificate ca : expectedCas) {
            if (ca.equals(top) || (ca.getBasicConstraints() >= 0 && issuedBy(top, ca))) {
                return;
            }
        }
        throw new RefusedException(Refusal.UNTRUSTED_CA, "issued by "
                + top.getIssuerX500Principal().getName() + ", not by the CA of the current device certificate");
    }

    /**
     * Encodes a PKCS#10 certification request (RFC 2986) without attributes, signed with SHA-256 and RSA.
     *
     * @param subject   subject name
     * @param publicKey public key of the signer
     * @param signer    the key that signs the request
     * @return the DER encoding
     * @throws GeneralSecurityException if the key cannot sign
     */
    public static byte[] certificationRequest(X500Principal subject, PublicKey publicKey, DeviceKey signer)
            throws GeneralSecurityException {
        byte[] info = der(0x30, concat(new byte[]{0x02, 0x01, 0x00}, subject.getEncoded(), publicKey.getEncoded(),
                new byte[]{(byte) 0xA0, 0x00}));
        byte[] algorithm = der(0x30, concat(SHA256_WITH_RSA, new byte[]{0x05, 0x00}));
        byte[] signature = signer.sign(info);
        byte[] bits = der(0x03, concat(new byte[]{0x00}, signature));
        return der(0x30, concat(info, algorithm, bits));
    }

    /**
     * Wraps DER bytes in PEM armour with 64-character lines.
     *
     * @param type label, for example {@code CERTIFICATE REQUEST}
     * @param der  the bytes
     * @return the PEM text
     */
    public static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    /**
     * Checks that a new chain comes from the CA that issued the current device certificate: one of its issuer
     * certificates must verify the current certificate. Only the device certificate is compared, because the
     * Android Keystore may keep the device certificate without its issuers.
     *
     * @param chain   the new chain, already checked by {@link #checkChain}
     * @param current the current device certificate (issued by a CA)
     * @throws RefusedException {@link Refusal#UNTRUSTED_CA} when no certificate of the chain issued it
     */
    static void checkSameCa(List<X509Certificate> chain, X509Certificate current) throws RefusedException {
        for (int i = 1; i < chain.size(); i++) {
            if (issuedBy(current, chain.get(i))) {
                return;
            }
        }
        throw new RefusedException(Refusal.UNTRUSTED_CA, "issued by " + chain.get(0).getIssuerX500Principal()
                .getName() + ", not by " + current.getIssuerX500Principal().getName()
                + " that issued the current device certificate");
    }

    private static boolean issuedBy(X509Certificate cert, X509Certificate issuer) {
        if (!cert.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
            return false;
        }
        try {
            cert.verify(issuer.getPublicKey());
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static String cleanRequestId(String requestId) {
        if (requestId == null) {
            return "";
        }
        String clean = requestId.replaceAll("[^A-Za-z0-9-]", "");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("[\\r\\n]+", " ");
    }

    private static void writeStatusMap(File folder, Map<String, String> status) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : status.entrySet()) {
            text.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        writeAtomically(new File(folder, STATUS_FILE), text.toString());
    }

    private static void writeAtomically(File target, String text) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(target)) {
            Files.deleteIfExists(target.toPath());
            if (!tmp.renameTo(target)) {
                throw new IOException("cannot write " + target);
            }
        }
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = length > 0xFFFFFF ? 4 : length > 0xFFFF ? 3 : length > 0xFF ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((length >>> (8 * i)) & 0xFF);
            }
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }
}
