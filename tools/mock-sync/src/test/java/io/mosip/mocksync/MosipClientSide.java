package io.mosip.mocksync;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.crypto.jce.core.CryptoCore;
import io.mosip.kernel.keymanagerservice.util.KeymanagerUtil;
import io.mosip.kernel.signature.dto.JWTSignatureVerifyRequestDto;
import io.mosip.kernel.signature.service.impl.SignatureServiceImpl;

/**
 * Test helper that runs MOSIP keymanager 1.2.0.1 code (the classes the desktop client ships) on MockSync output:
 * {@code CryptoCore} configured like the client's {@code spring.properties}, the {@code ClientCryptoFacade.decrypt}
 * byte layout, and {@code SignatureServiceImpl.jwtVerify} as {@code ResponseSignatureAdvice} calls it.
 */
final class MosipClientSide {

	private static final int WRAPPED_KEY_LENGTH = 256;
	private static final int IV_LENGTH = 12;
	private static final int AAD_LENGTH = 32;

	private MosipClientSide() {
	}

	/**
	 * Creates keymanager's CryptoCore with the desktop client's settings (keystore type OFFLINE, AES/GCM, OAEP SHA-256).
	 *
	 * @return configured CryptoCore
	 * @throws Exception on reflection failure
	 */
	static CryptoCore cryptoCore() throws Exception {
		CryptoCore core = new CryptoCore();
		set(core, "asymmetricKeyLength", 2048);
		set(core, "tagLength", 128);
		set(core, "symmetricAlgorithm", "AES/GCM/PKCS5Padding");
		set(core, "asymmetricAlgorithm", "RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING");
		set(core, "keystoreType", "OFFLINE");
		core.init();
		return core;
	}

	/**
	 * Decrypts like {@code ClientCryptoFacade.decrypt} with a LocalClientCryptoServiceImpl key.
	 *
	 * @param core       CryptoCore
	 * @param privateKey machine private key
	 * @param data       MockSync cipher bytes
	 * @return plain bytes
	 */
	static byte[] facadeDecrypt(CryptoCore core, PrivateKey privateKey, byte[] data) {
		byte[] wrapped = Arrays.copyOfRange(data, 0, WRAPPED_KEY_LENGTH);
		SecretKey key = new SecretKeySpec(core.asymmetricDecrypt(privateKey, wrapped), "AES");
		byte[] iv = Arrays.copyOfRange(data, WRAPPED_KEY_LENGTH, WRAPPED_KEY_LENGTH + IV_LENGTH);
		byte[] aad = Arrays.copyOfRange(data, WRAPPED_KEY_LENGTH + IV_LENGTH, WRAPPED_KEY_LENGTH + IV_LENGTH + AAD_LENGTH);
		byte[] cipher = Arrays.copyOfRange(data, WRAPPED_KEY_LENGTH + IV_LENGTH + AAD_LENGTH, data.length);
		return core.symmetricDecrypt(key, cipher, iv, aad);
	}

	/**
	 * Verifies a response signature exactly as {@code ResponseSignatureAdvice.responseSignatureValidation}: the body is
	 * parsed into a LinkedHashMap, re-serialised with a plain ObjectMapper and checked with keymanager jwtVerify.
	 *
	 * @param signatureHeader {@code response-signature} header value
	 * @param body            response body bytes
	 * @param certificatePem  stored KERNEL SIGN certificate
	 * @return keymanager's verdict
	 * @throws Exception on parse or reflection failure
	 */
	static boolean verifyResponseSignature(String signatureHeader, byte[] body, String certificatePem) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		LinkedHashMap<?, ?> parsed = mapper.readValue(body, LinkedHashMap.class);
		return jwtVerify(signatureHeader, new ObjectMapper().writeValueAsString(parsed), certificatePem);
	}

	/**
	 * Runs keymanager {@code SignatureServiceImpl.jwtVerify} for one signature and payload.
	 *
	 * @param signature      detached JWS
	 * @param actualData     payload text
	 * @param certificatePem certificate PEM
	 * @return signature validity
	 * @throws Exception on reflection failure
	 */
	static boolean jwtVerify(String signature, String actualData, String certificatePem) throws Exception {
		SignatureServiceImpl service = new SignatureServiceImpl();
		set(service, "keymanagerUtil", new KeymanagerUtil());
		JWTSignatureVerifyRequestDto request = new JWTSignatureVerifyRequestDto();
		request.setJwtSignatureData(signature);
		request.setActualData(Base64.getUrlEncoder().withoutPadding()
				.encodeToString(actualData.getBytes(StandardCharsets.UTF_8)));
		request.setCertificateData(certificatePem);
		return service.jwtVerify(request).isSignatureValid();
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
