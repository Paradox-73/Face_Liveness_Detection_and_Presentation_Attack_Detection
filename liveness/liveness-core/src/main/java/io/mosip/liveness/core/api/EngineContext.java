/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.config.ConfigSource;

import java.util.Objects;

/**
 * Platform services a client hands to a {@link LivenessEngineProvider} when it creates an engine.
 *
 * <p>These are long-lived and shared by all sessions of the engine: configuration, model files, the image
 * decoder, and sinks for engine-level audit events (for example {@code REG-EVT-LV14} when a model fails
 * verification at load time) and diagnostics.
 */
public final class EngineContext {

    private final ConfigSource config;
    private final ModelSource models;
    private final ImageDecoder imageDecoder;
    private final AuditSink auditSink;
    private final DiagnosticSink diagnosticSink;
    private final Clock clock;

    private EngineContext(Builder b) {
        this.config = b.config;
        this.models = b.models;
        this.imageDecoder = b.imageDecoder;
        this.auditSink = b.auditSink;
        this.diagnosticSink = b.diagnosticSink;
        this.clock = b.clock;
    }

    /**
     * Starts a builder.
     *
     * @return a builder with a no-op audit and diagnostic sink and the system clock
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the configuration source.
     *
     * @return the configuration
     */
    public ConfigSource config() {
        return config;
    }

    /**
     * Returns the model bundle files.
     *
     * @return the model source
     */
    public ModelSource models() {
        return models;
    }

    /**
     * Returns the platform image decoder.
     *
     * @return the decoder
     */
    public ImageDecoder imageDecoder() {
        return imageDecoder;
    }

    /**
     * Returns the sink for engine-level audit events.
     *
     * @return the audit sink
     */
    public AuditSink auditSink() {
        return auditSink;
    }

    /**
     * Returns the sink for engine-level diagnostics.
     *
     * @return the diagnostic sink
     */
    public DiagnosticSink diagnosticSink() {
        return diagnosticSink;
    }

    /**
     * Returns the clock.
     *
     * @return the clock
     */
    public Clock clock() {
        return clock;
    }

    /** Builder for {@link EngineContext}. */
    public static final class Builder {

        private ConfigSource config;
        private ModelSource models;
        private ImageDecoder imageDecoder;
        private AuditSink auditSink = AuditSink.noop();
        private DiagnosticSink diagnosticSink = DiagnosticSink.noop();
        private Clock clock = Clock.system();

        private Builder() {
        }

        /**
         * Sets the configuration source; required.
         *
         * @param config configuration
         * @return this builder
         */
        public Builder config(ConfigSource config) {
            this.config = config;
            return this;
        }

        /**
         * Sets the model bundle files; required.
         *
         * @param models model source
         * @return this builder
         */
        public Builder models(ModelSource models) {
            this.models = models;
            return this;
        }

        /**
         * Sets the platform image decoder; required.
         *
         * @param imageDecoder decoder
         * @return this builder
         */
        public Builder imageDecoder(ImageDecoder imageDecoder) {
            this.imageDecoder = imageDecoder;
            return this;
        }

        /**
         * Sets the audit sink for engine-level events.
         *
         * @param auditSink audit sink
         * @return this builder
         */
        public Builder auditSink(AuditSink auditSink) {
            this.auditSink = auditSink;
            return this;
        }

        /**
         * Sets the diagnostic sink.
         *
         * @param diagnosticSink diagnostic sink
         * @return this builder
         */
        public Builder diagnosticSink(DiagnosticSink diagnosticSink) {
            this.diagnosticSink = diagnosticSink;
            return this;
        }

        /**
         * Sets the clock; production code keeps the system clock.
         *
         * @param clock clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Validates the fields and creates the context.
         *
         * @return the context
         * @throws NullPointerException if a field is {@code null}
         */
        public EngineContext build() {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(models, "models");
            Objects.requireNonNull(imageDecoder, "imageDecoder");
            Objects.requireNonNull(auditSink, "auditSink");
            Objects.requireNonNull(diagnosticSink, "diagnosticSink");
            Objects.requireNonNull(clock, "clock");
            return new EngineContext(this);
        }
    }
}
