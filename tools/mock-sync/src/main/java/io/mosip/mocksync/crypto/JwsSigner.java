/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mocksync.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertificateEncodingException;
import java.util.Base64;

/**
 * RS256 JSON Web Signatures made with the MockSync KERNEL SIGN key.
 * <p>
 * Registration clients verify the {@code response-signature} header and the {@code file-signature} header with
 * keymanager {@code SignatureServiceImpl.jwtVerify}: the header is a detached JWS
 * ({@code header..signature}) and the client inserts base64url of the data it re-serialised as the payload.
 * The JWS header carries the signing certificate in {@code x5c}, which keymanager prefers over the certificate
 * stored from {@code getCertificate?applicationId=KERNEL&referenceId=SIGN}.
 */
public final class JwsSigner {

	private final CertifiedKey signKey;
	private final String detachedHeader;

	/**
	 * Creates a signer for one key.
	 *
	 * @param signKey KERNEL SIGN key and certificate
	 */
	public JwsSigner(CertifiedKey signKey) {
		this.signKey = signKey;
		try {
			String x5c = Base64.getEncoder().encodeToString(signKey.getCertificate().getEncoded());
			this.detachedHeader = base64Url("{\"x5c\":[\"" + x5c + "\"],\"alg\":\"RS256\"}");
		} catch (CertificateEncodingException e) {
			throw new IllegalStateException("Cannot encode signing certificate", e);
		}
	}

	/**
	 * Signs a payload and returns the detached compact serialisation {@code header..signature}.
	 *
	 * @param payload exact bytes the client will re-create and verify
	 * @return detached JWS
	 */
	public String signDetached(byte[] payload) {
		String signingInput = detachedHeader + "." + ClientCrypto.base64Url(payload);
		return detachedHeader + ".." + sign(signingInput);
	}

	/**
	 * Signs a JSON claims object as a compact JWT ({@code header.payload.signature}) with header
	 * {@code {"alg":"RS256","typ":"JWT"}}.
	 *
	 * @param claimsJson claims as JSON text
	 * @return compact JWT
	 */
	public String signJwt(String claimsJson) {
		String signingInput = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "." + base64Url(claimsJson);
		return signingInput + "." + sign(signingInput);
	}

	private String sign(String signingInput) {
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(signKey.getPrivateKey());
			signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			return ClientCrypto.base64Url(signature.sign());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("RS256 signing failed", e);
		}
	}

	private static String base64Url(String text) {
		return ClientCrypto.base64Url(text.getBytes(StandardCharsets.UTF_8));
	}
}
