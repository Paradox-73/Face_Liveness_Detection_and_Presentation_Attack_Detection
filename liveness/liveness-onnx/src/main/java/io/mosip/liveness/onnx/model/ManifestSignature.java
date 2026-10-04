/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.model;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Detached JWS signature over {@code manifest.json} ({@code manifest.jws}, RFC 7515 appendix F): a compact JWS
 * {@code header..signature} whose payload is the exact manifest bytes, signed with RS256 (RSA PKCS#1 v1.5 with
 * SHA-256, the algorithm MOSIP uses for its own JWS).
 *
 * <p>Only {@code RS256} is accepted, the header must not carry {@code crit}, and RSA keys must have at least
 * 2048 bits. The header's {@code kid} is {@code "sha256:"} plus the first 16 hex digits of the SHA-256 of the
 * public key (X.509 SubjectPublicKeyInfo), so a verifier with several trust anchors picks the right one.
 * Everything uses {@code java.security}, available on the desktop JRE and on Android.
 */
public final class ManifestSignature {

    /** The only accepted JWS algorithm. */
    public static final String ALGORITHM = "RS256";

    private static final int MIN_RSA_BITS = 2048;

    private ManifestSignature() {
    }

    /**
     * Signs manifest bytes.
     *
     * @param manifest   exact bytes of {@code manifest.json}
     * @param privateKey RSA private key
     * @param publicKey  the matching public key (its identifier goes into the header)
     * @return the compact detached JWS, {@code header..signature}
     * @throws GeneralSecurityException if signing fails
     */
    public static String sign(byte[] manifest, PrivateKey privateKey, PublicKey publicKey)
            throws GeneralSecurityException {
        Objects.requireNonNull(manifest, "manifest");
        checkKey(publicKey);
        String header = "{\"alg\":\"" + ALGORITHM + "\",\"kid\":\"" + keyId(publicKey) + "\"}";
        String encodedHeader = b64(header.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(signingInput(encodedHeader, manifest));
        return encodedHeader + ".." + b64(signer.sign());
    }

    /**
     * Verifies a detached JWS against the manifest bytes.
     *
     * @param manifest     exact bytes of {@code manifest.json}
     * @param jws          content of {@code manifest.jws}; surrounding white space is ignored
     * @param trustAnchors accepted public keys; at least one
     * @throws EngineException with {@link EngineErrorCode#MODEL_INTEGRITY} if the JWS is malformed, uses another
     *                         algorithm, names an unknown key or does not verify
     */
    public static void verify(byte[] manifest, String jws, List<PublicKey> trustAnchors) throws EngineException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(trustAnchors, "trustAnchors");
        if (jws == null) {
            throw integrity("manifest signature missing");
        }
        String[] parts = jws.trim().split("\\.", -1);
        if (parts.length != 3 || !parts[1].isEmpty() || parts[0].isEmpty() || parts[2].isEmpty()) {
            throw integrity("manifest signature is not a detached compact JWS");
        }
        String kid = checkHeader(parts[0]);
        byte[] signature = decode(parts[2]);
        byte[] input = signingInput(parts[0], manifest);
        for (PublicKey key : trustAnchors) {
            if (kid != null && !kid.equals(keyId(key))) {
                continue;
            }
            if (verifyWith(key, input, signature)) {
                return;
            }
        }
        throw integrity(kid == null ? "manifest signature does not verify" : "manifest signature does not verify with "
                + "a trusted key (kid " + kid + ")");
    }

    private static String checkHeader(String encodedHeader) throws EngineException {
        Map<?, ?> header;
        try {
            Object parsed = Json.parse(new String(decode(encodedHeader), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) {
                throw integrity("manifest signature header is not a JSON object");
            }
            header = (Map<?, ?>) parsed;
        } catch (IllegalArgumentException e) {
            throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "manifest signature header is not valid JSON",
                    e);
        }
        if (!ALGORITHM.equals(header.get("alg"))) {
            throw integrity("manifest signature algorithm must be " + ALGORITHM);
        }
        if (header.containsKey("crit") || (header.containsKey("b64") && !Boolean.TRUE.equals(header.get("b64")))) {
            throw integrity("manifest signature header uses unsupported parameters");
        }
        Object kid = header.get("kid");
        if (kid != null && !(kid instanceof String)) {
            throw integrity("manifest signature kid must be a string");
        }
        return (String) kid;
    }

    private static boolean verifyWith(PublicKey key, byte[] input, byte[] signature) {
        try {
            checkKey(key);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(input);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns the key identifier written into the JWS header.
     *
     * @param key a public key
     * @return {@code "sha256:"} plus 16 lower-case hex digits
     */
    public static String keyId(PublicKey key) {
        return "sha256:" + Hex.encode(sha256(key.getEncoded())).substring(0, 16);
    }

    private static void checkKey(PublicKey key) {
        if (!(key instanceof RSAPublicKey) || ((RSAPublicKey) key).getModulus().bitLength() < MIN_RSA_BITS) {
            throw new IllegalArgumentException("an RSA key of at least " + MIN_RSA_BITS + " bits is required");
        }
    }

    private static byte[] signingInput(String encodedHeader, byte[] payload) {
        return (encodedHeader + "." + b64(payload)).getBytes(StandardCharsets.US_ASCII);
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] decode(String part) throws EngineException {
        try {
            return Base64.getUrlDecoder().decode(part);
        } catch (IllegalArgumentException e) {
            throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "manifest signature is not base64url", e);
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static EngineException integrity(String message) {
        return new EngineException(EngineErrorCode.MODEL_INTEGRITY, message);
    }
}
