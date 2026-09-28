package io.mosip.mocksync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import io.mosip.mocksync.config.MockSyncProperties;
import io.mosip.mocksync.crypto.KeyMaterial;
import io.mosip.mocksync.crypto.ServerKeys;
import io.mosip.mocksync.web.ResponseFactory;
import io.mosip.mocksync.web.ScriptController;

/**
 * T-SYNC-02: MockSync signatures verify with keymanager {@code SignatureServiceImpl.jwtVerify}, used the way the
 * desktop client's {@code ResponseSignatureAdvice} and {@code RequiredFieldValidator} use it.
 */
class ResponseSignatureTest {

	@TempDir
	Path work;

	@Test
	void signedEnvelopeVerifiesAfterClientReserialisation() throws Exception {
		ServerKeys keys = keys();
		ResponseFactory factory = new ResponseFactory(keys);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("certificate", KeyMaterial.toPem(keys.kernelSign().getCertificate()));
		payload.put("idVersion", 0.1);
		payload.put("count", 3);
		payload.put("flag", true);
		payload.put("nested", List.of(Map.of("text", "café \"quoted\" <tag> / \\ slash"), 1.5E-3));

		ResponseEntity<byte[]> response = factory.signed("mosip.test", payload);
		String signature = response.getHeaders().getFirst(ResponseFactory.SIGNATURE_HEADER);
		String pem = KeyMaterial.toPem(keys.kernelSign().getCertificate());

		assertTrue(MosipClientSide.verifyResponseSignature(signature, response.getBody(), pem));
	}

	@Test
	void tamperedBodyFailsVerification() throws Exception {
		ServerKeys keys = keys();
		ResponseEntity<byte[]> response = new ResponseFactory(keys).signed("mosip.test", Map.of("keyIndex", "ab:cd"));
		String signature = response.getHeaders().getFirst(ResponseFactory.SIGNATURE_HEADER);
		byte[] tampered = new String(response.getBody(), StandardCharsets.UTF_8).replace("ab:cd", "ab:ce")
				.getBytes(StandardCharsets.UTF_8);

		assertFalse(MosipClientSide.verifyResponseSignature(signature, tampered,
				KeyMaterial.toPem(keys.kernelSign().getCertificate())));
	}

	@Test
	void scriptFileSignatureVerifiesLikeRequiredFieldValidator() throws Exception {
		ServerKeys keys = keys();
		byte[] script = "def getApplicantType() { return \"001\"; }".getBytes(StandardCharsets.UTF_8);
		String actualData = String.format("{\"hash\":\"%s\"}", ScriptController.sha256UpperHex(script));
		String signature = keys.signer().signDetached(actualData.getBytes(StandardCharsets.UTF_8));

		assertTrue(MosipClientSide.jwtVerify(signature, actualData,
				KeyMaterial.toPem(keys.kernelSign().getCertificate())));
		assertTrue(actualData.equals(String.format("{\"hash\":\"%s\"}",
				io.mosip.kernel.core.util.HMACUtils2.digestAsPlainText(script))), "hash format matches HMACUtils2");
	}

	private ServerKeys keys() {
		MockSyncProperties properties = new MockSyncProperties();
		properties.setWorkDir(work.toString());
		return new ServerKeys(properties);
	}
}
