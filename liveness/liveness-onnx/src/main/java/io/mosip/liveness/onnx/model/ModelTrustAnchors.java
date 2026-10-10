/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.model;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;

/**
 * Public keys trusted to sign model bundles.
 *
 * <p>The development key below signs the bundle in this repository ({@code models/manifest.jws}). Its private
 * half is kept outside the repository and never committed. A production deployment passes its own key to
 * {@link ModelRegistry.Builder#trustAnchor(PublicKey)}, for example one taken from a certificate the client
 * already synchronises.
 */
public final class ModelTrustAnchors {

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
     * Returns the keys a deployment trusts: the PEM blocks of {@code mosip.registration.liveness.model.trust_anchor_pem},
     * or the development key when that key is unset (the documented fallback for development and demos).
     *
     * @param config liveness configuration
     * @return at least one key
     * @throws EngineException {@code CONFIG_INVALID} when the key is set but holds no readable RSA public key; the
     *                         development key is never used in that case
     */
    public static List<PublicKey> configured(ConfigSource config) throws EngineException {
        Optional<String> pem = config.get(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM).map(String::trim)
                .filter(v -> !v.isEmpty());
        if (!pem.isPresent()) {
            return Collections.singletonList(development());
        }
        List<PublicKey> keys = new ArrayList<>();
        try {
            for (String block : pem.get().replace("\\n", "").split("-----END PUBLIC KEY-----")) {
                if (!block.trim().isEmpty()) {
                    keys.add(parsePem(block));
                }
            }
        } catch (IllegalArgumentException e) {
            throw new EngineException(EngineErrorCode.CONFIG_INVALID,
                    LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM + " is not a PEM RSA public key", e);
        }
        if (keys.isEmpty()) {
            throw new EngineException(EngineErrorCode.CONFIG_INVALID, LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM
                    + " holds no key");
        }
        return keys;
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
