package io.mosip.liveness.onnx.runtime;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Settings for the ONNX Runtime sessions of one model set.
 *
 * <p>Defaults: 2 intra-op threads (leaves cores for the UI and the frame reader), 1 inter-op thread, all graph
 * optimisations, CPU execution. Telemetry is always switched off. Android can add an execution provider such as
 * XNNPACK through {@link Builder#customizer(SessionCustomizer)}.
 */
public final class OnnxRuntimeOptions {

    private final int intraOpThreads;
    private final int interOpThreads;
    private final SessionCustomizer customizer;

    private OnnxRuntimeOptions(Builder b) {
        this.intraOpThreads = b.intraOpThreads;
        this.interOpThreads = b.interOpThreads;
        this.customizer = b.customizer;
    }

    /**
     * Returns the default options.
     *
     * @return 2 intra-op threads, 1 inter-op thread, no customizer
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
            if (customizer != null) {
                customizer.customize(options);
            }
            return options;
        } catch (OrtException | RuntimeException e) {
            options.close();
            throw e;
        }
    }

    @Override
    public String toString() {
        return "OnnxRuntimeOptions[intraOp=" + intraOpThreads + ", interOp=" + interOpThreads + ", customizer="
                + (customizer != null) + "]";
    }

    /** Hook to change session options, for example to add an Android execution provider. */
    @FunctionalInterface
    public interface SessionCustomizer {

        /**
         * Adjusts the options before a session is created.
         *
         * @param options options already holding the thread and optimisation settings
         * @throws OrtException if the runtime rejects a setting
         */
        void customize(OrtSession.SessionOptions options) throws OrtException;
    }

    /** Builder for {@link OnnxRuntimeOptions}. */
    public static final class Builder {

        private int intraOpThreads = 2;
        private int interOpThreads = 1;
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
