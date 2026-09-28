package io.mosip.mocksync.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import tss.tpm.TPMS_SIGNATURE_RSASSA;
import tss.tpm.TPMT_PUBLIC;
import tss.tpm.TPM_ALG_ID;

/**
 * Encrypts data for a registration client machine in the layout MOSIP keymanager 1.2.0.1
 * {@code ClientCryptoFacade.encrypt} produces and {@code ClientCryptoFacade.decrypt} reads:
 * <pre>
 * wrapped AES-256 key (256 bytes) | IV (12 bytes) | AAD (32 bytes) | AES-GCM ciphertext with 128-bit tag
 * </pre>
 * The key wrap depends on the {@link ClientType}: TPM keys use the TPM OAEP encoding of the key's name
 * algorithm with a one-byte zero label ({@code TPMT_PUBLIC.encrypt(data, "")}), Android keys use
 * OAEP SHA-256 with MGF1 SHA-1, and local keys use OAEP SHA-256 with MGF1 SHA-256.
 */
public final class ClientCrypto {

	/** IV length used by {@code ClientCryptoFacade} ({@code mosip.kernel.client.crypto.iv-length}). */
	public static final int IV_LENGTH = 12;

	/** AAD length used by {@code ClientCryptoFacade} ({@code mosip.kernel.client.crypto.aad-length}). */
	public static final int AAD_LENGTH = 32;

	/** GCM tag length in bits ({@code mosip.kernel.crypto.gcm-tag-length}). */
	public static final int GCM_TAG_BITS = 128;

	private static final String GCM = "AES/GCM/NoPadding";
	private static final String OAEP = "RSA/ECB/OAEPPadding";
	private static final SecureRandom RANDOM = new SecureRandom();

	private ClientCrypto() {
	}

	/**
	 * Encrypts data for one machine.
	 *
	 * @param type      key storage type of the machine; a TPM-format key is always treated as {@link ClientType#TPM}
	 * @param publicKey the machine's encryption public key as the client reports it (TPMT_PUBLIC or X.509 DER)
	 * @param data      plain data
	 * @return the combined cipher bytes described in the class comment
	 * @throws GeneralSecurityException if the key cannot be parsed or a cipher fails
	 */
	public static byte[] encrypt(ClientType type, byte[] publicKey, byte[] data) throws GeneralSecurityException {
		ClientType effective = isTpmKey(publicKey) ? ClientType.TPM : (type == null ? ClientType.LOCAL : type);
		SecretKey sessionKey = newSessionKey();
		byte[] iv = randomBytes(IV_LENGTH);
		byte[] aad = randomBytes(AAD_LENGTH);
		byte[] cipherText = aesGcm(Cipher.ENCRYPT_MODE, sessionKey.getEncoded(), iv, aad, data);
		byte[] wrappedKey = wrapKey(effective, publicKey, sessionKey.getEncoded());

		byte[] out = new byte[wrappedKey.length + iv.length + aad.length + cipherText.length];
		System.arraycopy(wrappedKey, 0, out, 0, wrappedKey.length);
		System.arraycopy(iv, 0, out, wrappedKey.length, iv.length);
		System.arraycopy(aad, 0, out, wrappedKey.length + iv.length, aad.length);
		System.arraycopy(cipherText, 0, out, wrappedKey.length + iv.length + aad.length, cipherText.length);
		return out;
	}

	/**
	 * Encrypts a UTF-8 string and returns base64url without padding, the encoding every client decodes.
	 *
	 * @param type      key storage type
	 * @param publicKey machine encryption public key
	 * @param text      plain text
	 * @return base64url cipher text
	 * @throws GeneralSecurityException if encryption fails
	 */
	public static String encryptToBase64Url(ClientType type, byte[] publicKey, String text)
			throws GeneralSecurityException {
		return base64Url(encrypt(type, publicKey, text.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * Tells whether a public key is a TPM {@code TPMT_PUBLIC} structure, with the same test keymanager uses.
	 *
	 * @param publicKey raw public key bytes
	 * @return true for a TPM key
	 */
	public static boolean isTpmKey(byte[] publicKey) {
		try {
			return TPMT_PUBLIC.fromTpm(publicKey) != null;
		} catch (Throwable notTpm) {
			return false;
		}
	}

	/**
	 * Computes the MOSIP key fingerprint ({@code CryptoUtil.computeFingerPrint(key, null)}): SHA-256 as lowercase
	 * hex with a colon between bytes. Clients send it as {@code keyindex} and as the login JWT {@code kid}.
	 *
	 * @param key public key bytes
	 * @return fingerprint such as {@code 3a:0f:...}
	 */
	public static String fingerprint(byte[] key) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
			StringBuilder builder = new StringBuilder(digest.length * 3);
			for (int i = 0; i < digest.length; i++) {
				if (i > 0) {
					builder.append(':');
				}
				builder.append(String.format(Locale.ROOT, "%02x", digest[i]));
			}
			return builder.toString();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	/**
	 * Checks a client signature made with the machine signing key (login request JWT).
	 *
	 * @param signPublicKey machine signing public key (TPMT_PUBLIC or X.509 DER)
	 * @param signature     signature bytes
	 * @param data          signed data
	 * @return true when the signature is valid
	 */
	public static boolean verifyClientSignature(byte[] signPublicKey, byte[] signature, byte[] data) {
		try {
			if (isTpmKey(signPublicKey)) {
				TPMT_PUBLIC tpmPublic = TPMT_PUBLIC.fromTpm(signPublicKey);
				return tpmPublic.validateSignature(data, new TPMS_SIGNATURE_RSASSA(TPM_ALG_ID.SHA256, signature));
			}
			Signature verifier = Signature.getInstance("SHA256withRSA");
			verifier.initVerify(toRsaPublicKey(signPublicKey));
			verifier.update(data);
			return verifier.verify(signature);
		} catch (Exception invalid) {
			return false;
		}
	}

	/**
	 * Parses an X.509 DER RSA public key.
	 *
	 * @param der encoded key
	 * @return public key
	 * @throws GeneralSecurityException if the bytes are not an RSA public key
	 */
	public static PublicKey toRsaPublicKey(byte[] der) throws GeneralSecurityException {
		return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
	}

	/**
	 * AES-GCM with a 128-bit tag, as keymanager {@code CryptoCore.symmetricEncrypt/Decrypt(key, data, iv, aad)}.
	 *
	 * @param mode Cipher.ENCRYPT_MODE or Cipher.DECRYPT_MODE
	 * @param key  AES key bytes
	 * @param iv   IV
	 * @param aad  additional authenticated data, may be null
	 * @param data input
	 * @return output
	 * @throws GeneralSecurityException on cipher failure
	 */
	public static byte[] aesGcm(int mode, byte[] key, byte[] iv, byte[] aad, byte[] data)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(GCM);
		cipher.init(mode, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
		if (aad != null && aad.length > 0) {
			cipher.updateAAD(aad);
		}
		return cipher.doFinal(data);
	}

	/**
	 * Encodes bytes as base64url without padding ({@code CryptoUtil.encodeToURLSafeBase64}).
	 *
	 * @param data bytes
	 * @return text
	 */
	public static String base64Url(byte[] data) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
	}

	/**
	 * Decodes base64url, with or without padding, falling back to standard base64 like
	 * keymanager {@code ClientCryptoUtils.decodeBase64Data}.
	 *
	 * @param text encoded text
	 * @return bytes
	 */
	public static byte[] decodeBase64Any(String text) {
		String trimmed = text.trim();
		try {
			return Base64.getUrlDecoder().decode(trimmed);
		} catch (IllegalArgumentException notUrlSafe) {
			return Base64.getMimeDecoder().decode(trimmed);
		}
	}

	private static byte[] wrapKey(ClientType type, byte[] publicKey, byte[] keyBytes) throws GeneralSecurityException {
		switch (type) {
		case TPM:
			return TPMT_PUBLIC.fromTpm(publicKey).encrypt(keyBytes, "");
		case ANDROID:
			return rsaOaep(publicKey, keyBytes, MGF1ParameterSpec.SHA1);
		default:
			return rsaOaep(publicKey, keyBytes, MGF1ParameterSpec.SHA256);
		}
	}

	private static byte[] rsaOaep(byte[] publicKey, byte[] data, MGF1ParameterSpec mgf) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(OAEP);
		cipher.init(Cipher.ENCRYPT_MODE, toRsaPublicKey(publicKey),
				new OAEPParameterSpec("SHA-256", "MGF1", mgf, PSource.PSpecified.DEFAULT));
		return cipher.doFinal(data);
	}

	private static SecretKey newSessionKey() throws GeneralSecurityException {
		KeyGenerator generator = KeyGenerator.getInstance("AES");
		generator.init(256, RANDOM);
		return generator.generateKey();
	}

	private static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		RANDOM.nextBytes(bytes);
		return bytes;
	}
}
