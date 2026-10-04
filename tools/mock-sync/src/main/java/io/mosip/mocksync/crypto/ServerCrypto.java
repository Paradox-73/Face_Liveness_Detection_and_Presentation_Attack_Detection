/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Decrypts data that clients encrypt to MockSync's own certificates.
 */
public final class ServerCrypto {

	/** Separator between the wrapped key and the data in keymanager cryptomanager output. */
	public static final byte[] KEY_SPLITTER = "#KEY_SPLITTER#".getBytes(StandardCharsets.US_ASCII);

	private static final int NONCE_LENGTH = 12;
	private static final int AAD_LENGTH = 32;
	private static final int THUMBPRINT_LENGTH = 32;
	private static final int CRYPTOCORE_IV_LENGTH = 16;

	private ServerCrypto() {
	}

	/**
	 * RSA-OAEP SHA-256 / MGF1 SHA-256 decryption, as keymanager {@code CryptoCore.asymmetricDecrypt}.
	 *
	 * @param key        private key
	 * @param cipherText wrapped data
	 * @return plain data
	 * @throws GeneralSecurityException on failure
	 */
	public static byte[] rsaOaepSha256(PrivateKey key, byte[] cipherText) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
		cipher.init(Cipher.DECRYPT_MODE, key,
				new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
		return cipher.doFinal(cipherText);
	}

	/**
	 * Decrypts data the desktop client encrypted with commons-packet-manager
	 * {@code OfflinePacketCryptoServiceImpl.encrypt(refId, data)} to a {@code REGISTRATION/<refId>} certificate:
	 * {@code nonce(12) | aad(32) | thumbprint(32) | RSA-wrapped AES key | #KEY_SPLITTER# | AES-GCM cipher text}.
	 *
	 * @param key  private key of the REGISTRATION certificate
	 * @param data encrypted bytes
	 * @return plain bytes
	 * @throws GeneralSecurityException if the data does not decrypt
	 */
	public static byte[] decryptPacketData(PrivateKey key, byte[] data) throws GeneralSecurityException {
		if (data.length < NONCE_LENGTH + AAD_LENGTH + THUMBPRINT_LENGTH) {
			throw new GeneralSecurityException("Encrypted data too short");
		}
		byte[] nonce = Arrays.copyOfRange(data, 0, NONCE_LENGTH);
		byte[] aad = Arrays.copyOfRange(data, NONCE_LENGTH, NONCE_LENGTH + AAD_LENGTH);
		byte[] rest = Arrays.copyOfRange(data, NONCE_LENGTH + AAD_LENGTH, data.length);
		int split = indexOf(rest, KEY_SPLITTER);
		if (split < THUMBPRINT_LENGTH) {
			throw new GeneralSecurityException("Key splitter not found");
		}
		byte[] wrappedKey = Arrays.copyOfRange(rest, THUMBPRINT_LENGTH, split);
		byte[] cipherText = Arrays.copyOfRange(rest, split + KEY_SPLITTER.length, rest.length);
		byte[] aesKey = rsaOaepSha256(key, wrappedKey);
		return ClientCrypto.aesGcm(Cipher.DECRYPT_MODE, aesKey, nonce, aad, cipherText);
	}

	/**
	 * Decrypts {@code CryptoCore.symmetricEncrypt(key, data, aad)} output: AES-GCM cipher text followed by a
	 * 16-byte random IV. Used for the operator onboarding request sent to the IDA mock.
	 *
	 * @param aesKey AES key bytes
	 * @param data   cipher text with the IV at the end
	 * @return plain bytes
	 * @throws GeneralSecurityException on failure
	 */
	public static byte[] decryptWithTrailingIv(byte[] aesKey, byte[] data) throws GeneralSecurityException {
		if (data.length <= CRYPTOCORE_IV_LENGTH) {
			throw new GeneralSecurityException("Encrypted data too short");
		}
		byte[] iv = Arrays.copyOfRange(data, data.length - CRYPTOCORE_IV_LENGTH, data.length);
		byte[] cipherText = Arrays.copyOfRange(data, 0, data.length - CRYPTOCORE_IV_LENGTH);
		return ClientCrypto.aesGcm(Cipher.DECRYPT_MODE, aesKey, iv, null, cipherText);
	}

	private static int indexOf(byte[] data, byte[] pattern) {
		outer: for (int i = 0; i <= data.length - pattern.length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (data[i + j] != pattern[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
