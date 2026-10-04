/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import java.util.Locale;
import java.util.Objects;

/**
 * Identity of one model file an engine uses, as listed in the signed model bundle manifest.
 */
public final class ModelInfo {

    private final String id;
    private final String version;
    private final String sha256;

    private ModelInfo(String id, String version, String sha256) {
        this.id = id;
        this.version = version;
        this.sha256 = sha256;
    }

    /**
     * Creates a model description.
     *
     * @param id      model identifier, for example {@code "minifasnet_v2"}
     * @param version model or bundle version
     * @param sha256  SHA-256 of the model file as 64 hex digits (any case; stored lower-case)
     * @return the description
     * @throws IllegalArgumentException if the identifier or version is empty or the digest is not 64 hex digits
     */
    public static ModelInfo of(String id, String version, String sha256) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sha256, "sha256");
        if (id.isEmpty() || version.isEmpty()) {
            throw new IllegalArgumentException("id and version must not be empty");
        }
        String hex = sha256.toLowerCase(Locale.ROOT);
        if (hex.length() != 64) {
            throw new IllegalArgumentException("sha256 must be 64 hex digits");
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException("sha256 must be 64 hex digits");
            }
        }
        return new ModelInfo(id, version, hex);
    }

    /**
     * Returns the model identifier.
     *
     * @return the identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the model version.
     *
     * @return the version
     */
    public String version() {
        return version;
    }

    /**
     * Returns the SHA-256 of the model file.
     *
     * @return 64 lower-case hex digits
     */
    public String sha256() {
        return sha256;
    }

    /**
     * Returns the first 8 hex digits of the SHA-256, as written to packet evidence ({@code LIVENESS_MODEL_SHA}).
     *
     * @return 8 lower-case hex digits
     */
    public String sha256Prefix() {
        return sha256.substring(0, 8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ModelInfo)) {
            return false;
        }
        ModelInfo m = (ModelInfo) o;
        return id.equals(m.id) && version.equals(m.version) && sha256.equals(m.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version, sha256);
    }

    @Override
    public String toString() {
        return id + "@" + version + "#" + sha256Prefix();
    }
}
