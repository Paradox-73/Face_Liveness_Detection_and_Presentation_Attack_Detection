/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.runtime;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Settings for the ONNX Runtime sessions of one model set.
 *
 * <p>Defaults: 2 intra-op threads (leaves cores for the UI and the frame reader), 1 inter-op thread, all graph
 * optimisations, plain CPU execution. Telemetry is always switched off. {@link ExecutionProvider#XNNPACK} and, on
 * Android, {@link ExecutionProvider#NNAPI} can be chosen with {@link Builder#executionProvider(ExecutionProvider)}
 * or with the configuration key {@value LivenessConfigKeys#ENGINE_EXECUTION_PROVIDER} ({@link #fromConfig}); a
 * provider the installed runtime does not include falls back to CPU, and {@link #effectiveExecutionProvider()}
 * tells which one is used. Other settings can be added through {@link Builder#customizer(SessionCustomizer)}.
 */
public final class OnnxRuntimeOptions {

    /** Inference back ends that the engine can ask ONNX Runtime for. */
    public enum ExecutionProvider {
        /** The default CPU kernels of ONNX Runtime. */
        CPU(OrtProvider.CPU),
        /** XNNPACK CPU kernels (ARM and x86); included in {@code onnxruntime-android}. */
        XNNPACK(OrtProvider.XNNPACK),
        /** Android Neural Networks API; Android only, falls back to CPU for operators it cannot run. */
        NNAPI(OrtProvider.NNAPI);

        private final OrtProvider ort;

        ExecutionProvider(OrtProvider ort) {
            this.ort = ort;
        }

        /**
         * Parses a configuration value, ignoring case and surrounding spaces.
         *
         * @param value for example {@code "xnnpack"}; {@code null} or blank means {@link #CPU}
         * @return the provider
         * @throws IllegalArgumentException if the value names no provider
         */
        public static ExecutionProvider parse(String value) {
            if (value == null || value.trim().isEmpty()) {
                return CPU;
            }
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private final int intraOpThreads;
    private final int interOpThreads;
    private final ExecutionProvider executionProvider;
    private final SessionCustomizer customizer;

    private OnnxRuntimeOptions(Builder b) {
        this.intraOpThreads = b.intraOpThreads;
        this.interOpThreads = b.interOpThreads;
        this.executionProvider = b.executionProvider;
        this.customizer = b.customizer;
    }

    /**
     * Returns the default options.
     *
     * @return 2 intra-op threads, 1 inter-op thread, CPU, no customizer
     */
    public static OnnxRuntimeOptions defaults() {
        return builder().build();
    }

    /**
     * Starts a builder with the default values.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts a builder whose execution provider comes from {@value LivenessConfigKeys#ENGINE_EXECUTION_PROVIDER}.
     * An unknown value is ignored (CPU), so a typing error in the configuration cannot stop the engine loading.
     *
     * @param config liveness configuration
     * @return a builder with the configured provider and the default threads
     */
    public static Builder fromConfig(ConfigSource config) {
        Objects.requireNonNull(config, "config");
        String value = config.get(LivenessConfigKeys.ENGINE_EXECUTION_PROVIDER)
                .orElse(LivenessConfigKeys.DEFAULT_ENGINE_EXECUTION_PROVIDER);
        ExecutionProvider provider;
        try {
            provider = ExecutionProvider.parse(value);
        } catch (IllegalArgumentException e) {
            provider = ExecutionProvider.CPU;
        }
        return builder().executionProvider(provider);
    }

    /**
     * Lists the execution providers that the installed ONNX Runtime includes.
     *
     * @return the available providers; at least {@link ExecutionProvider#CPU} when the runtime loads, empty if it
     *         cannot be loaded
     */
    public static Set<ExecutionProvider> availableExecutionProviders() {
        EnumSet<ExecutionProvider> out = EnumSet.noneOf(ExecutionProvider.class);
        EnumSet<OrtProvider> ort;
        try {
            ort = OrtEnvironment.getAvailableProviders();
        } catch (RuntimeException | LinkageError e) {
            return Collections.unmodifiableSet(out);
        }
        for (ExecutionProvider p : ExecutionProvider.values()) {
            if (ort.contains(p.ort)) {
                out.add(p);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Returns the number of threads one inference may use.
     *
     * @return intra-op threads
     */
    public int intraOpThreads() {
        return intraOpThreads;
    }

    /**
     * Returns the number of threads for running independent graph branches.
     *
     * @return inter-op threads
     */
    public int interOpThreads() {
        return interOpThreads;
    }

    /**
     * Returns the execution provider that was asked for.
     *
     * @return the requested provider
     */
    public ExecutionProvider executionProvider() {
        return executionProvider;
    }

    /**
     * Returns the execution provider that sessions created with these options use: the requested one if the
     * installed runtime includes it, otherwise CPU.
     *
     * @return the provider in effect
     */
    public ExecutionProvider effectiveExecutionProvider() {
        if (executionProvider == ExecutionProvider.CPU
                || !availableExecutionProviders().contains(executionProvider)) {
            return ExecutionProvider.CPU;
        }
        return executionProvider;
    }

    /**
     * Creates session options with these settings.
     *
     * @return new session options; the caller closes them
     * @throws OrtException if the runtime rejects a setting
     */
    OrtSession.SessionOptions toSessionOptions() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
            options.setIntraOpNumThreads(intraOpThreads);
            options.setInterOpNumThreads(interOpThreads);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            addExecutionProvider(options);
            if (customizer != null) {
                customizer.customize(options);
            }
            return options;
        } catch (OrtException | RuntimeException e) {
            options.close();
            throw e;
        }
    }

    private void addExecutionProvider(OrtSession.SessionOptions options) throws OrtException {
        switch (effectiveExecutionProvider()) {
            case XNNPACK:
                // XNNPACK runs its own thread pool; give it the thread budget of one inference.
                options.addXnnpack(Collections.singletonMap("intra_op_num_threads", Integer.toString(intraOpThreads)));
                break;
            case NNAPI:
                options.addNnapi();
                break;
            default:
                break;
        }
    }

    @Override
    public String toString() {
        return "OnnxRuntimeOptions[intraOp=" + intraOpThreads + ", interOp=" + interOpThreads + ", provider="
                + executionProvider + ", customizer=" + (customizer != null) + "]";
    }

    /** Hook to change session options beyond the settings of this class. */
    @FunctionalInterface
    public interface SessionCustomizer {

        /**
         * Adjusts the options before a session is created.
         *
         * @param options options already holding the thread, optimisation and provider settings
         * @throws OrtException if the runtime rejects a setting
         */
        void customize(OrtSession.SessionOptions options) throws OrtException;
    }

    /** Builder for {@link OnnxRuntimeOptions}. */
    public static final class Builder {

        private int intraOpThreads = 2;
        private int interOpThreads = 1;
        private ExecutionProvider executionProvider = ExecutionProvider.CPU;
        private SessionCustomizer customizer;

        private Builder() {
        }

        /**
         * Sets the threads one inference may use.
         *
         * @param threads 1 to 64
         * @return this builder
         */
        public Builder intraOpThreads(int threads) {
            this.intraOpThreads = checkThreads(threads);
            return this;
        }

        /**
         * Sets the threads for independent graph branches.
         *
         * @param threads 1 to 64
         * @return this builder
         */
        public Builder interOpThreads(int threads) {
            this.interOpThreads = checkThreads(threads);
            return this;
        }

        /**
         * Sets the execution provider; one the runtime does not include falls back to CPU.
         *
         * @param provider the provider
         * @return this builder
         */
        public Builder executionProvider(ExecutionProvider provider) {
            this.executionProvider = Objects.requireNonNull(provider, "provider");
            return this;
        }

        /**
         * Sets a hook that adjusts every session's options.
         *
         * @param customizer the hook, or {@code null} for none
         * @return this builder
         */
        public Builder customizer(SessionCustomizer customizer) {
            this.customizer = customizer;
            return this;
        }

        /**
         * Creates the options.
         *
         * @return the options
         */
        public OnnxRuntimeOptions build() {
            return new OnnxRuntimeOptions(this);
        }

        private static int checkThreads(int threads) {
            if (threads < 1 || threads > 64) {
                throw new IllegalArgumentException("threads must be 1-64");
            }
            return threads;
        }
    }
}
