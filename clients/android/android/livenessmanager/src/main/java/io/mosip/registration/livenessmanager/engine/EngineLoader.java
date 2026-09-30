package io.mosip.registration.livenessmanager.engine;

import android.content.res.AssetManager;
import android.util.Log;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.engine.LivenessMetrics;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.onnx.OnnxLivenessEngine;
import io.mosip.liveness.onnx.OnnxLivenessEngines;
import io.mosip.liveness.onnx.OnnxModelSet;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;

/**
 * Loads the liveness engine once per process, on a background thread, and hands it to the sessions.
 *
 * <p>Loading verifies the signed model bundle and creates the ONNX Runtime sessions (about 1-3 s on a phone).
 * {@link #preload()} starts it early (when the app starts); {@link #await(long)} blocks the calling liveness
 * thread until the engine is ready. A model integrity failure (signature, hash or version) is kept until the app
 * restarts: liveness then fails closed with audit {@code REG-EVT-LV14}. Other load failures are retried at the
 * next {@link #preload()} or {@link #await(long)}.
 *
 * <p>Thread-safe.
 */
public final class EngineLoader implements AutoCloseable {

    private static final String TAG = "Liveness";

    /** Creates the engine; may block. */
    @FunctionalInterface
    public interface Factory {

        /**
         * Creates a ready engine.
         *
         * @return the engine
         * @throws EngineException if the models cannot be verified or loaded
         */
        LivenessEngine create() throws EngineException;
    }

    private final Factory factory;
    private volatile String acceleratorStatus = Accelerator.CPU.name();
    private LivenessEngine engine;
    private EngineException failure;
    private CountDownLatch loading;
    private boolean closed;

    /**
     * Creates a loader around any engine factory (tests pass scripted engines).
     *
     * @param factory engine factory
     */
    public EngineLoader(Factory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Creates the loader of the default ONNX engine over the model bundle in the APK assets.
     *
     * @param assets  application assets
     * @param config  liveness configuration (for {@code model.min_version})
     * @param decoder platform image decoder
     * @return the loader; nothing is loaded until {@link #preload()} or {@link #await(long)}
     */
    public static EngineLoader onnx(AssetManager assets, ConfigSource config, ImageDecoder decoder) {
        return onnx(assets, config, decoder, new LivenessMetrics(), AcceleratorCustomizer.ortProviders());
    }

    /**
     * Creates the loader of the default ONNX engine over the model bundle in the APK assets, with the execution
     * provider of {@link Accelerator#CONFIG_KEY} and the given metrics collector.
     *
     * <p>If the engine fails to load with an accelerator for any reason other than a model integrity failure, it
     * is loaded again on the CPU, so a provider problem never switches liveness off.
     *
     * @param assets    application assets
     * @param config    liveness configuration ({@code model.min_version}, {@code android.accelerator})
     * @param decoder   platform image decoder
     * @param metrics   metrics shared by all sessions of the engine
     * @param providers ONNX Runtime execution provider access
     * @return the loader; nothing is loaded until {@link #preload()} or {@link #await(long)}
     */
    public static EngineLoader onnx(AssetManager assets, ConfigSource config, ImageDecoder decoder,
                                    LivenessMetrics metrics, AcceleratorCustomizer.ExecutionProviders providers) {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(providers, "providers");
        EngineLoader[] self = new EngineLoader[1];
        self[0] = new EngineLoader(() -> {
            Accelerator accelerator = Accelerator.parse(config.get(Accelerator.CONFIG_KEY).orElse(null));
            long start = System.nanoTime();
            LivenessEngine e;
            try {
                e = buildOnnx(assets, config, decoder, metrics, accelerator, providers, self[0]);
            } catch (EngineException | RuntimeException | LinkageError failure) {
                boolean integrity = failure instanceof EngineException
                        && ((EngineException) failure).getErrorCode().isModelIntegrityFailure();
                if (accelerator == Accelerator.CPU || integrity) {
                    throw failure;
                }
                Log.w(TAG, "Liveness engine did not load with " + accelerator + " ("
                        + failure.getClass().getSimpleName() + "); loading it on the CPU");
                e = buildOnnx(assets, config, decoder, metrics, Accelerator.CPU, providers, self[0]);
                self[0].acceleratorStatus = "CPU (" + accelerator + " failed to load)";
            }
            Log.i(TAG, "Liveness engine ready: " + e.info().tag() + " in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms, execution "
                    + self[0].acceleratorStatus);
            return e;
        });
        return self[0];
    }

    private static LivenessEngine buildOnnx(AssetManager assets, ConfigSource config, ImageDecoder decoder,
                                            LivenessMetrics metrics, Accelerator accelerator,
                                            AcceleratorCustomizer.ExecutionProviders providers,
                                            EngineLoader loader) throws EngineException {
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        Set<AcceleratorCustomizer.Outcome> outcomes = EnumSet.noneOf(AcceleratorCustomizer.Outcome.class);
        AcceleratorCustomizer customizer = new AcceleratorCustomizer(accelerator, threads, providers, outcome -> {
            synchronized (outcomes) {
                outcomes.add(outcome);
            }
        });
        LivenessEngine e = OnnxLivenessEngines.builder(new AssetModelSource(assets))
                .minimumVersion(config.get(LivenessConfigKeys.MODEL_MIN_VERSION)
                        .orElse(LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION))
                .runtimeOptions(OnnxRuntimeOptions.builder().intraOpThreads(threads).customizer(customizer).build())
                .imageDecoder(decoder)
                .metrics(metrics)
                .build();
        synchronized (outcomes) {
            loader.acceleratorStatus = describe(accelerator, outcomes);
        }
        warmUp(e);
        return e;
    }

    /**
     * Runs the face detector and the PAD models once on a blank frame. The first inference of an ONNX Runtime
     * session is several times slower than the next ones (memory planning, kernel selection); on a slow tablet or
     * emulator the first frames of the first session would otherwise exceed {@code engine.inference_slow_ms} and
     * end it with {@code INFERENCE_TIMEOUT}. Best effort: a failure here is logged and ignored.
     *
     * @param engine the loaded engine
     */
    static void warmUp(LivenessEngine engine) {
        if (!(engine instanceof OnnxLivenessEngine)) {
            return;
        }
        long start = System.nanoTime();
        int w = 640;
        int h = 480;
        ImageBuffer blank = ImageBuffer.ofBytes(w, h, PixelFormat.RGB888, new byte[w * h * 3]);
        try {
            OnnxModelSet models = ((OnnxLivenessEngine) engine).modelSet();
            models.faceAnalyzer().analyze(blank);
            if (models.passivePad() != null) {
                models.passivePad().score(blank, FaceBox.of(w / 4.0, h / 4.0, w / 2.0, h / 2.0, 1.0));
            }
            Log.i(TAG, "Liveness models warmed up in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
        } catch (EngineException | RuntimeException e) {
            Log.w(TAG, "Liveness model warm-up skipped: " + e.getClass().getSimpleName());
        } finally {
            blank.wipe();
        }
    }

    /**
     * Describes how the models run, for Logcat and the diagnostic view.
     *
     * @param accelerator the configured provider
     * @param outcomes    outcomes of the model sessions
     * @return for example {@code XNNPACK}, {@code CPU (NNAPI unavailable)} or {@code XNNPACK (partly CPU)}
     */
    static String describe(Accelerator accelerator, Set<AcceleratorCustomizer.Outcome> outcomes) {
        if (accelerator == Accelerator.CPU || outcomes.isEmpty()) {
            return Accelerator.CPU.name();
        }
        boolean enabled = outcomes.contains(AcceleratorCustomizer.Outcome.ENABLED);
        if (enabled && outcomes.size() == 1) {
            return accelerator.name();
        }
        if (enabled) {
            return accelerator.name() + " (partly CPU)";
        }
        return "CPU (" + accelerator + (outcomes.contains(AcceleratorCustomizer.Outcome.UNAVAILABLE)
                ? " unavailable)" : " rejected)");
    }

    /**
     * Tells how the models run once the engine is loaded.
     *
     * @return {@code CPU}, the accelerator name, or a CPU fallback description
     */
    public String acceleratorStatus() {
        return acceleratorStatus;
    }

    /** Starts loading in the background unless the engine is loaded, loading or failed for good. */
    public synchronized void preload() {
        if (closed || engine != null || loading != null || isPermanentFailure()) {
            return;
        }
        failure = null;
        CountDownLatch latch = new CountDownLatch(1);
        loading = latch;
        Thread t = new Thread(() -> load(latch), "liveness-engine-load");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Returns the engine, waiting for it to load. Call it off the main thread.
     *
     * @param timeoutMs longest wait
     * @return the ready engine
     * @throws EngineException the load failure, or {@code NOT_READY} if loading takes longer than the timeout
     */
    public LivenessEngine await(long timeoutMs) throws EngineException {
        CountDownLatch latch;
        synchronized (this) {
            if (engine != null) {
                return engine;
            }
            if (failure != null && isPermanentFailure()) {
                throw failure;
            }
            preload();
            latch = loading;
        }
        try {
            if (latch != null && !latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new EngineException(EngineErrorCode.NOT_READY, "engine is still loading");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException(EngineErrorCode.NOT_READY, "interrupted while the engine loads");
        }
        synchronized (this) {
            if (engine != null) {
                return engine;
            }
            throw failure != null ? failure : new EngineException(EngineErrorCode.NOT_READY, "engine not loaded");
        }
    }

    /**
     * Tells whether the engine is loaded.
     *
     * @return {@code true} once {@link #await(long)} returns immediately
     */
    public synchronized boolean isReady() {
        return engine != null;
    }

    /** Releases the engine. Idempotent. */
    @Override
    public synchronized void close() {
        closed = true;
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    private void load(CountDownLatch latch) {
        LivenessEngine created = null;
        EngineException error = null;
        try {
            created = factory.create();
        } catch (EngineException e) {
            error = e;
        } catch (RuntimeException | LinkageError e) {
            error = new EngineException(EngineErrorCode.MODEL_LOAD, "engine could not be created: "
                    + e.getClass().getSimpleName(), e);
        }
        synchronized (this) {
            if (closed && created != null) {
                created.close();
                created = null;
            }
            engine = created;
            failure = error;
            loading = null;
        }
        if (error != null) {
            Log.e(TAG, "Liveness engine failed to load: " + error.getErrorCode());
        }
        latch.countDown();
    }

    private boolean isPermanentFailure() {
        return failure != null && failure.getErrorCode().isModelIntegrityFailure();
    }
}
