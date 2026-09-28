package io.mosip.mocksync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import org.junit.jupiter.api.Test;

import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.crypto.jce.core.CryptoCore;
import io.mosip.mocksync.crypto.ClientCrypto;
import io.mosip.mocksync.crypto.ClientType;
import io.mosip.mocksync.crypto.KeyMaterial;
import tss.tpm.TPM2B_PUBLIC_KEY_RSA;
import tss.tpm.TPMA_OBJECT;
import tss.tpm.TPMS_ENC_SCHEME_OAEP;
import tss.tpm.TPMS_RSA_PARMS;
import tss.tpm.TPMT_PUBLIC;
import tss.tpm.TPMT_SYM_DEF_OBJECT;
import tss.tpm.TPM_ALG_ID;

/**
 * T-SYNC-01: responses encrypted by MockSync decrypt with the algorithms each client type uses.
 */
class ClientCryptoTest {

	private static final byte[] PLAIN = "{\"token\":\"abc\",\"expiryTime\":3600,\"text\":\"näme\"}"
			.getBytes(StandardCharsets.UTF_8);

	@Test
	void localKeyDecryptsWithKeymanagerCryptoCore() throws Exception {
		KeyPair machine = KeyMaterial.newRsaKeyPair();
		byte[] cipher = ClientCrypto.encrypt(ClientType.LOCAL, machine.getPublic().getEncoded(), PLAIN);

		assertEquals(256 + 12 + 32 + PLAIN.length + 16, cipher.length, "key | iv | aad | data | tag");
		CryptoCore core = MosipClientSide.cryptoCore();
		assertArrayEquals(PLAIN, MosipClientSide.facadeDecrypt(core, machine.getPrivate(), cipher));
	}

	@Test
	void base64UrlOutputDecodesWithKeymanagerHelpers() throws Exception {
		KeyPair machine = KeyMaterial.newRsaKeyPair();
		String text = ClientCrypto.encryptToBase64Url(ClientType.LOCAL, machine.getPublic().getEncoded(),
				new String(PLAIN, StandardCharsets.UTF_8));

		assertFalse(text.contains("=") || text.contains("+") || text.contains("/"), "base64url without padding");
		byte[] decoded = io.mosip.kernel.clientcrypto.util.ClientCryptoUtils.decodeBase64Data(text);
		assertArrayEquals(PLAIN, MosipClientSide.facadeDecrypt(MosipClientSide.cryptoCore(), machine.getPrivate(), decoded));
		assertArrayEquals(decoded, CryptoUtil.decodeURLSafeBase64(text));
	}

	@Test
	void androidKeyUsesMgf1Sha1() throws Exception {
		KeyPair machine = KeyMaterial.newRsaKeyPair();
		byte[] cipher = ClientCrypto.encrypt(ClientType.ANDROID, machine.getPublic().getEncoded(), PLAIN);

		byte[] sessionKey = oaepDecrypt(machine, Arrays.copyOfRange(cipher, 0, 256), MGF1ParameterSpec.SHA1,
				PSource.PSpecified.DEFAULT);
		assertArrayEquals(PLAIN, gcmDecrypt(sessionKey, cipher));
		assertThrows(Exception.class, () -> MosipClientSide.facadeDecrypt(MosipClientSide.cryptoCore(),
				machine.getPrivate(), cipher), "an Android cipher text must not decrypt as a LOCAL one");
	}

	@Test
	void tpmKeyUsesTpmOaepWithZeroLabel() throws Exception {
		KeyPair machine = KeyMaterial.newRsaKeyPair();
		byte[] tpmPublic = tpmEncryptionKey((RSAPublicKey) machine.getPublic()).toTpm();
		assertTrue(ClientCrypto.isTpmKey(tpmPublic));
		assertFalse(ClientCrypto.isTpmKey(machine.getPublic().getEncoded()));

		byte[] cipher = ClientCrypto.encrypt(ClientType.LOCAL, tpmPublic, PLAIN);

		byte[] sessionKey = oaepDecrypt(machine, Arrays.copyOfRange(cipher, 0, 256), MGF1ParameterSpec.SHA256,
				new PSource.PSpecified(new byte[] { 0 }));
		assertArrayEquals(PLAIN, gcmDecrypt(sessionKey, cipher));
	}

	@Test
	void fingerprintMatchesKeymanagerCryptoUtil() {
		byte[] key = KeyMaterial.newRsaKeyPair().getPublic().getEncoded();
		assertEquals(CryptoUtil.computeFingerPrint(key, null).toLowerCase(), ClientCrypto.fingerprint(key));
	}

	/** Builds the TPMT_PUBLIC the desktop client's TPM encryption key has (TPMClientCryptoServiceImpl.createRSAKey). */
	private static TPMT_PUBLIC tpmEncryptionKey(RSAPublicKey publicKey) {
		byte[] modulus = publicKey.getModulus().toByteArray();
		if (modulus[0] == 0) {
			modulus = Arrays.copyOfRange(modulus, 1, modulus.length);
		}
		return new TPMT_PUBLIC(TPM_ALG_ID.SHA256,
				new TPMA_OBJECT(TPMA_OBJECT.fixedTPM, TPMA_OBJECT.fixedParent, TPMA_OBJECT.decrypt,
						TPMA_OBJECT.sensitiveDataOrigin, TPMA_OBJECT.userWithAuth),
				new byte[32], new TPMS_RSA_PARMS(new TPMT_SYM_DEF_OBJECT(TPM_ALG_ID.NULL, 0, TPM_ALG_ID.NULL),
						new TPMS_ENC_SCHEME_OAEP(TPM_ALG_ID.SHA256), 2048, 65537),
				new TPM2B_PUBLIC_KEY_RSA(modulus));
	}

	private static byte[] oaepDecrypt(KeyPair pair, byte[] wrapped, MGF1ParameterSpec mgf, PSource label)
			throws Exception {
		Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
		rsa.init(Cipher.DECRYPT_MODE, pair.getPrivate(), new OAEPParameterSpec("SHA-256", "MGF1", mgf, label));
		return rsa.doFinal(wrapped);
	}

	private static byte[] gcmDecrypt(byte[] key, byte[] cipher) throws Exception {
		return ClientCrypto.aesGcm(Cipher.DECRYPT_MODE, key, Arrays.copyOfRange(cipher, 256, 268),
				Arrays.copyOfRange(cipher, 268, 300), Arrays.copyOfRange(cipher, 300, cipher.length));
	}
}
