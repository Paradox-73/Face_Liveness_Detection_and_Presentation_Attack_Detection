/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.model;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The verified model bundle: reads {@code manifest.json} and {@code manifest.jws} from a {@link ModelSource},
 * checks the signature against built-in trust anchors and the bundle version against
 * {@code model.min_version}, and hands out model bytes only after their size and SHA-256 match the manifest.
 *
 * <p>Every failure is fatal for the models concerned (fail closed) and is reported as an {@link EngineException}
 * whose code has {@link EngineErrorCode#isModelIntegrityFailure()} set ({@code MODEL_MISSING},
 * {@code MODEL_INTEGRITY}, {@code MODEL_VERSION}), so the engine audits {@code REG-EVT-LV14}.
 * {@link #models()} gives identifier, version and SHA-256 of every model for audits and packet evidence.
 *
 * <p>Thread-safe: the registry is immutable after {@link Builder#open()}.
 */
public final class ModelRegistry {

    /** File name of the manifest inside the bundle. */
    public static final String MANIFEST_FILE = "manifest.json";
    /** File name of the detached manifest signature inside the bundle. */
    public static final String SIGNATURE_FILE = "manifest.jws";

    private final ModelSource source;
    private final ModelManifest manifest;
    private final List<ModelInfo> models;

    private ModelRegistry(ModelSource source, ModelManifest manifest) {
        this.source = source;
        this.manifest = manifest;
        List<ModelInfo> infos = new ArrayList<>();
        for (ModelEntry e : manifest.models()) {
            infos.add(e.toModelInfo());
        }
        this.models = Collections.unmodifiableList(infos);
    }

    /**
     * Opens a bundle with the built-in development trust anchor and the default minimum version.
     *
     * @param source where the bundle files are read from
     * @return the verified registry
     * @throws EngineException if the manifest is missing, unsigned, tampered with or too old
     */
    public static ModelRegistry open(ModelSource source) throws EngineException {
        return builder(source).open();
    }

    /**
     * Starts a builder.
     *
     * @param source where the bundle files are read from
     * @return a builder using the development trust anchor and minimum version
     *         {@value LivenessConfigKeys#DEFAULT_MODEL_MIN_VERSION}
     */
    public static Builder builder(ModelSource source) {
        return new Builder(source);
    }

    /**
     * Returns the verified manifest.
     *
     * @return the manifest
     */
    public ModelManifest manifest() {
        return manifest;
    }

    /**
     * Returns the bundle name.
     *
     * @return the name
     */
    public String bundleName() {
        return manifest.bundle();
    }

    /**
     * Returns the bundle version.
     *
     * @return the version, for example {@code "1.0.0"}
     */
    public String bundleVersion() {
        return manifest.version().toString();
    }

    /**
     * Returns identifier, version and SHA-256 of every model in the bundle, for audits and packet evidence.
     *
     * @return an unmodifiable list in manifest order
     */
    public List<ModelInfo> models() {
        return models;
    }

    /**
     * Tells whether the bundle lists a model.
     *
     * @param id model identifier
     * @return {@code true} if listed
     */
    public boolean contains(String id) {
        return manifest.entry(id).isPresent();
    }

    /**
     * Returns the manifest entry of a model.
     *
     * @param id model identifier
     * @return the entry
     * @throws EngineException with {@link EngineErrorCode#MODEL_MISSING} if the bundle does not list the model
     */
    public ModelEntry entry(String id) throws EngineException {
        return manifest.entry(id).orElseThrow(() -> new EngineException(EngineErrorCode.MODEL_MISSING,
                "model bundle " + manifest.bundle() + " has no model " + id));
    }

    /**
     * Reads a model file and checks its size and SHA-256 against the signed manifest.
     *
     * @param id model identifier
     * @return the verified file bytes (a fresh array owned by the caller)
     * @throws EngineException with {@link EngineErrorCode#MODEL_MISSING} if the model is not listed or the file is
     *                         missing, or {@link EngineErrorCode#MODEL_INTEGRITY} if size or hash differ
     */
    public byte[] load(String id) throws EngineException {
        ModelEntry entry = entry(id);
        byte[] bytes = read(source, entry.file(), "model " + id);
        if (bytes.length != entry.bytes()) {
            throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "model " + id + " has " + bytes.length
                    + " bytes, manifest says " + entry.bytes());
        }
        byte[] expected = decodeHex(entry.sha256());
        if (!MessageDigest.isEqual(expected, ManifestSignature.sha256(bytes))) {
            throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "model " + id + " SHA-256 does not match the "
                    + "signed manifest");
        }
        return bytes;
    }

    /**
     * Loads and checks every model of the bundle, discarding the bytes (start-up self-test and signer tool).
     *
     * @throws EngineException on the first model that fails
     */
    public void verifyAll() throws EngineException {
        for (ModelEntry e : manifest.models()) {
            load(e.id());
        }
    }

    private static byte[] read(ModelSource source, String path, String what) throws EngineException {
        try {
            byte[] bytes = source.read(path);
            if (bytes == null) {
                throw new EngineException(EngineErrorCode.MODEL_MISSING, what + " not found: " + path);
            }
            return bytes;
        } catch (FileNotFoundException e) {
            throw new EngineException(EngineErrorCode.MODEL_MISSING, what + " not found: " + path, e);
        } catch (IOException | RuntimeException e) {
            throw new EngineException(EngineErrorCode.MODEL_MISSING, what + " could not be read: " + path, e);
        }
    }

    private static byte[] decodeHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    @Override
    public String toString() {
        return "ModelRegistry[" + manifest.bundle() + "@" + manifest.version() + ", models=" + models + "]";
    }

    /** Builder for {@link ModelRegistry}. */
    public static final class Builder {

        private final ModelSource source;
        private final List<PublicKey> trustAnchors = new ArrayList<>();
        private String minimumVersion = LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION;

        private Builder(ModelSource source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        /**
         * Adds a trusted signing key. Once any key is added, the built-in development key is no longer trusted
         * unless it is added too.
         *
         * @param key RSA public key of at least 2048 bits
         * @return this builder
         */
        public Builder trustAnchor(PublicKey key) {
            trustAnchors.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        /**
         * Sets the lowest accepted bundle version ({@code mosip.registration.liveness.model.min_version}).
         *
         * @param version {@code MAJOR.MINOR.PATCH}
         * @return this builder
         */
        public Builder minimumVersion(String version) {
            this.minimumVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        /**
         * Reads and verifies the manifest.
         *
         * @return the registry
         * @throws EngineException with {@code MODEL_MISSING} if the manifest or its signature is absent,
         *                         {@code MODEL_INTEGRITY} if the signature or the manifest is invalid,
         *                         {@code MODEL_VERSION} if the bundle is older than the minimum version, or
         *                         {@code CONFIG_INVALID} if the minimum version is not a valid version
         */
        public ModelRegistry open() throws EngineException {
            SemanticVersion minimum;
            try {
                minimum = SemanticVersion.parse(minimumVersion.trim());
            } catch (IllegalArgumentException e) {
                throw new EngineException(EngineErrorCode.CONFIG_INVALID, "invalid model.min_version", e);
            }
            byte[] manifestBytes = read(source, MANIFEST_FILE, "model manifest");
            byte[] signature = read(source, SIGNATURE_FILE, "model manifest signature");
            List<PublicKey> anchors = trustAnchors.isEmpty()
                    ? Collections.singletonList(ModelTrustAnchors.development()) : trustAnchors;
            ManifestSignature.verify(manifestBytes, new String(signature, StandardCharsets.US_ASCII), anchors);
            ModelManifest manifest;
            try {
                manifest = ModelManifest.parse(manifestBytes);
            } catch (IllegalArgumentException e) {
                throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "signed model manifest is invalid", e);
            }
            if (!manifest.version().isAtLeast(minimum)) {
                throw new EngineException(EngineErrorCode.MODEL_VERSION, "model bundle " + manifest.version()
                        + " is older than the minimum " + minimum);
            }
            return new ModelRegistry(source, manifest);
        }
    }
}
