package io.mosip.registration.livenessmanager.engine;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;

/**
 * Adds the configured {@link Accelerator} to every ONNX Runtime session of the liveness models, through the
 * engine's {@link OnnxRuntimeOptions.SessionCustomizer} hook, and falls back to the CPU when it cannot.
 *
 * <p>Fallback, in order: a provider this ONNX Runtime build does not contain is skipped; a provider that rejects
 * its options is skipped for that model; either way the model runs on the CPU and the outcome is reported to the
 * status callback (Logcat and the diagnostic view). If the engine still fails to load with an accelerator,
 * {@link EngineLoader#onnx} loads it again on the CPU only.
 *
 * <p>XNNPACK brings its own thread pool: the session's intra-op pool is set to one thread and XNNPACK gets the
 * configured thread count, as ONNX Runtime recommends.
 */
public final class AcceleratorCustomizer implements OnnxRuntimeOptions.SessionCustomizer {

    /** Access to ONNX Runtime's execution providers; replaced in tests. */
    public interface ExecutionProviders {

        /**
         * Returns the providers compiled into this ONNX Runtime build.
         *
         * @return provider names ({@code OrtProvider} names such as {@code XNNPACK})
         */
        Set<String> available();

        /**
         * Adds a provider to the session options.
         *
         * @param options     session options
         * @param accelerator the provider
         * @param threads     threads for providers that have their own pool
         * @throws OrtException if the runtime rejects it
         */
        void add(OrtSession.SessionOptions options, Accelerator accelerator, int threads) throws OrtException;
    }

    /** What happened when a session was created. */
    public enum Outcome {
        /** The accelerator was added. */
        ENABLED,
        /** CPU was configured; nothing to add. */
        NOT_REQUESTED,
        /** The ONNX Runtime build has no such provider; the CPU is used. */
        UNAVAILABLE,
        /** The provider rejected the session options; the CPU is used. */
        REJECTED
    }

    private final Accelerator accelerator;
    private final int threads;
    private final ExecutionProviders providers;
    private final Consumer<Outcome> status;

    /**
     * Creates the customizer.
     *
     * @param accelerator the configured provider
     * @param threads     threads for providers with their own pool (at least 1)
     * @param providers   ONNX Runtime access ({@link #ortProviders()} in the app)
     * @param status      receives the outcome of every session creation
     */
    public AcceleratorCustomizer(Accelerator accelerator, int threads, ExecutionProviders providers,
                                 Consumer<Outcome> status) {
        this.accelerator = Objects.requireNonNull(accelerator, "accelerator");
        this.threads = Math.max(1, threads);
        this.providers = Objects.requireNonNull(providers, "providers");
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * Returns the real ONNX Runtime provider access.
     *
     * @return the providers of the loaded ONNX Runtime
     */
    public static ExecutionProviders ortProviders() {
        return new OrtExecutionProviders();
    }

    @Override
    public void customize(OrtSession.SessionOptions options) {
        if (accelerator == Accelerator.CPU) {
            status.accept(Outcome.NOT_REQUESTED);
            return;
        }
        Set<String> available;
        try {
            available = providers.available();
        } catch (RuntimeException | LinkageError e) {
            available = Collections.emptySet();
        }
        if (!available.contains(accelerator.name())) {
            status.accept(Outcome.UNAVAILABLE);
            return;
        }
        try {
            providers.add(options, accelerator, threads);
            status.accept(Outcome.ENABLED);
        } catch (OrtException | RuntimeException e) {
            status.accept(Outcome.REJECTED);
        }
    }

    /** {@link ExecutionProviders} over {@code OrtEnvironment} and {@code OrtSession.SessionOptions}. */
    private static final class OrtExecutionProviders implements ExecutionProviders {

        @Override
        public Set<String> available() {
            EnumSet<OrtProvider> set = OrtEnvironment.getAvailableProviders();
            java.util.HashSet<String> names = new java.util.HashSet<>();
            for (OrtProvider p : set) {
                names.add(p.name());
            }
            return names;
        }

        @Override
        public void add(OrtSession.SessionOptions options, Accelerator accelerator, int threads)
                throws OrtException {
            switch (accelerator) {
                case XNNPACK:
                    options.addXnnpack(Collections.singletonMap("intra_op_num_threads", String.valueOf(threads)));
                    options.setIntraOpNumThreads(1);
                    break;
                case NNAPI:
                    options.addNnapi();
                    break;
                default:
                    break;
            }
        }
    }
}
