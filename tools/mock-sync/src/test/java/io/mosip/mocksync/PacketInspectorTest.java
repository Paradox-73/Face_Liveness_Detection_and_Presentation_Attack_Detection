package io.mosip.mocksync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import org.junit.jupiter.api.Test;

import io.mosip.mocksync.crypto.ClientCrypto;
import io.mosip.mocksync.crypto.KeyMaterial;
import io.mosip.mocksync.packet.PacketInspector;

/**
 * Tests {@link PacketInspector} on a packet built like the desktop client's: an outer zip encrypted to the machine's
 * REGISTRATION key holding an {@code id} sub-packet with a CBEFF file, the packet meta info and the audit list.
 */
class PacketInspectorTest {

	private static final String NAME = "10001100010000120260930101010-10001_10001-20260930101010.zip";

	private static final String CBEFF = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
			+ "<BIR xmlns=\"http://standards.iso.org/iso-iec/19785/-3/ed-2/\"><BIRInfo><Integrity>false</Integrity></BIRInfo>"
			+ "<BIR><BDBInfo><Type>Face</Type><Subtype></Subtype></BDBInfo><BDB>AAAA</BDB>"
			+ "<others><entry key=\"SPEC_VERSION\">0.9.5</entry><entry key=\"LIVENESS_DECISION\">PASS</entry>"
			+ "<entry key=\"LIVENESS_METHOD\">PASSIVE_ACTIVE</entry><entry key=\"LIVENESS_CHALLENGES\">SMILE:OK,BLINK:OK</entry>"
			+ "</others></BIR>"
			+ "<BIR><BDBInfo><Type>Finger</Type><Subtype>Left IndexFinger</Subtype></BDBInfo><BDB>BBBB</BDB>"
			+ "<others><entry key=\"RETRIES\">1</entry></others></BIR></BIR>";

	private static final String META = "{\"identity\":{\"biometrics\":{\"individualBiometrics\":{"
			+ "\"face\":{\"numRetry\":1,\"livenessDecision\":\"PASS\",\"livenessMethod\":\"PASSIVE_ACTIVE\"},"
			+ "\"leftIndex\":{\"numRetry\":1}}}}}";

	private static final String AUDIT = "[{\"eventId\":\"REG-EVT-001\",\"eventName\":\"LOGIN\"},"
			+ "{\"eventId\":\"REG-EVT-LV10\",\"eventName\":\"LIVENESS_PASSED\",\"sessionUserId\":\"officer1\","
			+ "\"description\":\"{\\\"workflow\\\":\\\"registration\\\",\\\"method\\\":\\\"PASSIVE_ACTIVE\\\"}\"}]";

	@Test
	void reportsLivenessEvidenceWithoutBiometricData() throws Exception {
		KeyPair machine = KeyMaterial.newRsaKeyPair();
		byte[] id = zip(Map.of("individualBiometrics_bio_CBEFF.xml", CBEFF, "packet_meta_info.json", META,
				"audit.json", AUDIT));
		byte[] outer = zip(Map.of("REGISTRATION_CLIENT/NEW/10001100010000120260930101010_id.zip", id));

		Map<String, Object> report = PacketInspector.inspect(NAME, encrypt(machine.getPublic(), outer),
				machine.getPrivate());

		assertEquals("10001100010000120260930101010", report.get("registrationId"));
		List<?> records = (List<?>) report.get("faceRecords");
		assertEquals(1, records.size());
		assertEquals(1, report.get("otherBiometricRecords"));
		Map<?, ?> face = (Map<?, ?>) records.get(0);
		assertEquals("Face", face.get("type"));
		assertEquals(Map.of("LIVENESS_DECISION", "PASS", "LIVENESS_METHOD", "PASSIVE_ACTIVE", "LIVENESS_CHALLENGES",
				"SMILE:OK,BLINK:OK"), face.get("liveness"));

		List<?> meta = (List<?>) report.get("metaInfo");
		assertEquals(1, meta.size());
		assertEquals("PASS", ((Map<?, ?>) meta.get(0)).get("livenessDecision"));

		List<?> audits = (List<?>) report.get("livenessAudits");
		assertEquals(1, audits.size());
		assertEquals("REG-EVT-LV10", ((Map<?, ?>) audits.get(0)).get("eventId"));

		assertFalse(report.toString().contains("AAAA"), "biometric data must not be reported");
	}

	@Test
	void wrongKeyAndNamesAreRefused() throws Exception {
		KeyPair machine = KeyMaterial.newRsaKeyPair();
		byte[] packet = encrypt(machine.getPublic(), zip(Map.of("a.txt", "x")));
		assertThrows(GeneralSecurityException.class,
				() -> PacketInspector.inspect(NAME, packet, KeyMaterial.newRsaKeyPair().getPrivate()));
		assertEquals("10001_10001", PacketInspector.referenceId(NAME));
		assertThrows(IllegalArgumentException.class, () -> PacketInspector.referenceId("../secret.zip"));
	}

	private static byte[] zip(Map<String, ?> entries) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, ?> e : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				Object v = e.getValue();
				zip.write(v instanceof byte[] ? (byte[]) v : String.valueOf(v).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	/** commons-packet-manager layout: nonce | aad | thumbprint | wrapped key | #KEY_SPLITTER# | AES-GCM. */
	private static byte[] encrypt(PublicKey key, byte[] data) throws GeneralSecurityException, IOException {
		KeyGenerator generator = KeyGenerator.getInstance("AES");
		generator.init(256);
		SecretKey aes = generator.generateKey();
		byte[] nonce = new byte[12];
		byte[] aad = new byte[32];
		new SecureRandom().nextBytes(nonce);
		new SecureRandom().nextBytes(aad);
		Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
		rsa.init(Cipher.ENCRYPT_MODE, key,
				new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(nonce);
		out.write(aad);
		out.write(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
		out.write(rsa.doFinal(aes.getEncoded()));
		out.write("#KEY_SPLITTER#".getBytes(StandardCharsets.US_ASCII));
		out.write(ClientCrypto.aesGcm(Cipher.ENCRYPT_MODE, aes.getEncoded(), nonce, aad, data));
		return out.toByteArray();
	}
}
