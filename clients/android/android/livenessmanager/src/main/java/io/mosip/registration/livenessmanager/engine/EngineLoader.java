package io.mosip.registration.livenessmanager.engine;

import android.content.res.AssetManager;
import android.util.Log;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.onnx.OnnxLivenessEngines;
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
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(decoder, "decoder");
        return new EngineLoader(() -> {
            long start = System.nanoTime();
            int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            LivenessEngine e = OnnxLivenessEngines.builder(new AssetModelSource(assets))
                    .minimumVersion(config.get(LivenessConfigKeys.MODEL_MIN_VERSION)
                            .orElse(LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION))
                    .runtimeOptions(OnnxRuntimeOptions.builder().intraOpThreads(threads).build())
                    .imageDecoder(decoder)
                    .build();
            Log.i(TAG, "Liveness engine ready: " + e.info().tag() + " in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
            return e;
        });
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
