/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.ChallengeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Identity and capabilities of a {@link LivenessEngine}, recorded in audits ({@code engine@version}) and in
 * packet evidence ({@code LIVENESS_ENGINE}, {@code LIVENESS_MODEL_SHA}).
 */
public final class EngineInfo {

    private final String id;
    private final String version;
    private final String vendor;
    private final List<ModelInfo> models;
    private final Set<ChallengeType> supportedChallenges;
    private final boolean requiresStream;

    private EngineInfo(Builder b) {
        this.id = b.id;
        this.version = b.version;
        this.vendor = b.vendor;
        this.models = Collections.unmodifiableList(new ArrayList<>(b.models));
        this.supportedChallenges = Collections.unmodifiableSet(b.supportedChallenges.isEmpty()
                ? EnumSet.noneOf(ChallengeType.class) : EnumSet.copyOf(b.supportedChallenges));
        this.requiresStream = b.requiresStream;
    }

    /**
     * Starts a builder.
     *
     * @param id      engine identifier used in {@code mosip.registration.liveness.engine}
     * @param version engine version
     * @return a builder
     */
    public static Builder builder(String id, String version) {
        return new Builder(id, version);
    }

    /**
     * Returns the engine identifier.
     *
     * @return for example {@code "mosip-onnx-default"}
     */
    public String id() {
        return id;
    }

    /**
     * Returns the engine version.
     *
     * @return the version
     */
    public String version() {
        return version;
    }

    /**
     * Returns the vendor name.
     *
     * @return the vendor, or an empty string
     */
    public String vendor() {
        return vendor;
    }

    /**
     * Returns the models the engine loaded.
     *
     * @return an unmodifiable list, possibly empty
     */
    public List<ModelInfo> models() {
        return models;
    }

    /**
     * Returns the challenge types the engine can detect.
     *
     * @return an unmodifiable set, possibly empty (passive-only engine)
     */
    public Set<ChallengeType> supportedChallenges() {
        return supportedChallenges;
    }

    /**
     * Tells whether the engine needs a preview stream (all frame-based engines do).
     *
     * @return {@code true} if a stream is required
     */
    public boolean requiresStream() {
        return requiresStream;
    }

    /**
     * Returns {@code id@version}, the form used in audits and packet evidence.
     *
     * @return the engine tag
     */
    public String tag() {
        return id + "@" + version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EngineInfo)) {
            return false;
        }
        EngineInfo e = (EngineInfo) o;
        return requiresStream == e.requiresStream && id.equals(e.id) && version.equals(e.version)
                && vendor.equals(e.vendor) && models.equals(e.models) && supportedChallenges.equals(e.supportedChallenges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version, vendor, models, supportedChallenges, requiresStream);
    }

    @Override
    public String toString() {
        return "EngineInfo[" + tag() + ", models=" + models + ", challenges=" + supportedChallenges + "]";
    }

    /** Builder for {@link EngineInfo}. */
    public static final class Builder {

        private final String id;
        private final String version;
        private String vendor = "";
        private final List<ModelInfo> models = new ArrayList<>();
        private final Set<ChallengeType> supportedChallenges = EnumSet.noneOf(ChallengeType.class);
        private boolean requiresStream = true;

        private Builder(String id, String version) {
            this.id = Objects.requireNonNull(id, "id");
            this.version = Objects.requireNonNull(version, "version");
            if (id.isEmpty() || version.isEmpty() || id.indexOf('@') >= 0) {
                throw new IllegalArgumentException("id and version must not be empty and id must not contain '@'");
            }
        }

        /**
         * Sets the vendor name; default empty.
         *
         * @param vendor vendor name
         * @return this builder
         */
        public Builder vendor(String vendor) {
            this.vendor = Objects.requireNonNull(vendor, "vendor");
            return this;
        }

        /**
         * Adds a loaded model.
         *
         * @param model model description
         * @return this builder
         */
        public Builder model(ModelInfo model) {
            models.add(Objects.requireNonNull(model, "model"));
            return this;
        }

        /**
         * Adds supported challenge types.
         *
         * @param types challenge types the engine can detect
         * @return this builder
         */
        public Builder supportedChallenges(Collection<ChallengeType> types) {
            supportedChallenges.addAll(types);
            return this;
        }

        /**
         * Sets whether a preview stream is required; default {@code true}.
         *
         * @param requiresStream whether a stream is required
         * @return this builder
         */
        public Builder requiresStream(boolean requiresStream) {
            this.requiresStream = requiresStream;
            return this;
        }

        /**
         * Creates the engine description.
         *
         * @return the description
         */
        public EngineInfo build() {
            return new EngineInfo(this);
        }
    }
}
