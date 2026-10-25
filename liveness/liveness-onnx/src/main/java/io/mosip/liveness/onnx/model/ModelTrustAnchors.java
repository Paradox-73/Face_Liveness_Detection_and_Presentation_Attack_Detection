/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;

/**
 * Public keys trusted to sign model bundles.
 *
 * <p>The development key below signs the bundle in this repository ({@code models/manifest.jws}). Its private
 * half is kept outside the repository and never committed. The clients trust only the keys named by
 * {@code mosip.registration.liveness.model.trust_anchor_pem}; demo configurations set it to this key explicitly.
 *
 * <p>The setting holds PEM {@code PUBLIC KEY} blocks, or {@code sha256:<64 hex>} fingerprints (SHA-256 of the key's
 * SubjectPublicKeyInfo) that pick keys shipped with the client: the development key and the PEM blocks of the class
 * path resource {@value #BUILT_IN_RESOURCE}. A fingerprint fits the desktop client's global-parameter column
 * (512 characters), which a PEM RSA 3072 key (about 600 characters) does not.
 */
public final class ModelTrustAnchors {

    /** Class path resource with further keys a fingerprint may pick; optional, added to a deployment's build. */
    public static final String BUILT_IN_RESOURCE = "liveness-model-trust-anchors.pem";

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN PUBLIC KEY-----.*?-----END PUBLIC KEY-----", Pattern.DOTALL);
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:([0-9a-fA-F]{64})");

    /**
     * X.509 SubjectPublicKeyInfo (PEM) of the development model-signing key (RSA 3072, key id
     * {@code sha256:99538367853153c2}).
     */
    public static final String DEVELOPMENT_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n"
            + "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAtO/phbjKkqNwCeynf58i\n"
            + "UGgw1kfPbHhSV2uukL5sR0nc5lq1NfHQj57B5JvHGu+nFDeF6dsEKwiLacEorI39\n"
            + "bAwU59I6W8JkYEqpJ/XaLg/EdhWy/Uoc7RbC6Kt1iqhovroDu5XvVXFKEStt/6D8\n"
            + "YYqGXE4vhkHqYfyO1LsIkaXrHt+ehtzK4OzW3Y0M4IDfHTCysliv3oHSif55rvws\n"
            + "3vpoeHEUAPnjL69z/qf0eY3j6+VqCp2SEMqyENgOJHQehDj3YF/SJvls6l63Fmp2\n"
            + "/wknrcJZLVX6v5qHsQeoxPOYvlefUcDwYtgE3PNNreqZECAUWhfMqaoUaIXQ2Zk8\n"
            + "tCnlDl3FNN6Aka98kHpD3eanm2NPjTQlbXoYOcf4Nd54Hig3q2TTGQin3zw+Ylk7\n"
            + "h2v9O7TG+OcLyaraoBjnLc6XV+4B5js6SlwG2hBivNHjEOVPO4bnwLzKp0jhMWNT\n"
            + "vUKd9JdxbWFF6S8cCA/JpSlNv89Q2zl/7DxCoGOUNgj3AgMBAAE=\n"
            + "-----END PUBLIC KEY-----\n";

    private ModelTrustAnchors() {
    }

    /**
     * Returns the development model-signing key.
     *
     * @return the RSA public key
     */
    public static PublicKey development() {
        return parsePem(DEVELOPMENT_KEY_PEM);
    }

    /**
     * Returns the keys a deployment trusts: the PEM blocks, and the shipped keys picked by fingerprint, in
     * {@code mosip.registration.liveness.model.trust_anchor_pem}.
     *
     * @param config liveness configuration
     * @return at least one key
     * @throws EngineException {@code CONFIG_INVALID} when the key is unset, holds no readable RSA public key, or names
     *                         a fingerprint of no shipped key; no other key is trusted in its place, so no model loads
     */
    public static List<PublicKey> configured(ConfigSource config) throws EngineException {
        Optional<String> pem = config.get(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM).map(String::trim)
                .filter(v -> !v.isEmpty());
        if (!pem.isPresent()) {
            throw new EngineException(EngineErrorCode.CONFIG_INVALID, LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM
                    + " is unset; no model is trusted until it names the model-signing public key");
        }
        List<PublicKey> keys = new ArrayList<>();
        String value = pem.get().replace("\\n", "");
        try {
            Matcher blocks = PEM_BLOCK.matcher(value);
            while (blocks.find()) {
                keys.add(parsePem(blocks.group()));
            }
            for (String token : blocks.replaceAll(" ").trim().split("[\\s,]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                Matcher fp = FINGERPRINT.matcher(token);
                if (!fp.matches()) {
                    throw new IllegalArgumentException("neither a PEM block nor sha256:<64 hex>");
                }
                keys.add(shipped(fp.group(1).toLowerCase(Locale.ROOT)));
            }
        } catch (IllegalArgumentException e) {
            throw new EngineException(EngineErrorCode.CONFIG_INVALID, LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM
                    + " is not a PEM RSA public key or the fingerprint of a shipped key: " + e.getMessage(), e);
        }
        if (keys.isEmpty()) {
            throw new EngineException(EngineErrorCode.CONFIG_INVALID, LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM
                    + " holds no key");
        }
        return keys;
    }

    /**
     * Returns the fingerprint with which the setting picks a shipped key.
     *
     * @param key the key
     * @return {@code sha256:} and the lower-case hex SHA-256 of the key's SubjectPublicKeyInfo
     */
    public static String fingerprint(PublicKey key) {
        try {
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : MessageDigest.getInstance("SHA-256").digest(key.getEncoded())) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** The development key or a key of {@value #BUILT_IN_RESOURCE} with this SHA-256 (lower-case hex). */
    private static PublicKey shipped(String hex) {
        List<PublicKey> candidates = new ArrayList<>();
        candidates.add(development());
        try (InputStream in = ModelTrustAnchors.class.getClassLoader().getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (in != null) {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n = in.read(buf); n > 0; n = in.read(buf)) {
                    text.write(buf, 0, n);
                }
                Matcher blocks = PEM_BLOCK.matcher(new String(text.toByteArray(), StandardCharsets.US_ASCII));
                while (blocks.find()) {
                    candidates.add(parsePem(blocks.group()));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(BUILT_IN_RESOURCE + " could not be read", e);
        }
        for (PublicKey k : candidates) {
            if (fingerprint(k).equals("sha256:" + hex)) {
                return k;
            }
        }
        throw new IllegalArgumentException("no shipped key has fingerprint sha256:" + hex);
    }

    /**
     * Parses a PEM {@code PUBLIC KEY} block (X.509 SubjectPublicKeyInfo) holding an RSA key.
     *
     * @param pem the PEM text
     * @return the key
     * @throws IllegalArgumentException if the text is not an RSA public key
     */
    public static PublicKey parsePem(String pem) {
        Objects.requireNonNull(pem, "pem");
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("not a PEM RSA public key", e);
        }
    }

    /**
     * Writes a public key as a PEM {@code PUBLIC KEY} block.
     *
     * @param key the key
     * @return PEM text with 64-character lines
     */
    public static String toPem(PublicKey key) {
        return pemBlock("PUBLIC KEY", key.getEncoded());
    }

    /**
     * Writes DER bytes as a PEM block.
     *
     * @param type block type, for example {@code "PUBLIC KEY"} or {@code "PRIVATE KEY"}
     * @param der  the encoded key
     * @return PEM text with 64-character lines
     */
    public static String pemBlock(String type, byte[] der) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(b64.length(), i + 64)).append('\n');
        }
        return sb.append("-----END ").append(type).append("-----\n").toString();
    }
}
