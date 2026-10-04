/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.engine;

import android.content.res.AssetManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineContext;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessEngineProvider;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.engine.LivenessMetrics;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.onnx.OnnxLivenessEngine;
import io.mosip.liveness.onnx.OnnxLivenessEngines;
import io.mosip.liveness.onnx.OnnxModelSet;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.liveness.onnx.model.SemanticVersion;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;

/**
 * Loads the liveness engine once per process, on a background thread, and hands it to the sessions.
 *
 * <p>The engine is the one named by {@code mosip.registration.liveness.engine}, as on the desktop client: the
 * built-in ONNX engine ({@code mosip-onnx-default}, the default) or a vendor engine whose jar is added to the APK
 * with a {@link LivenessEngineProvider} listed in
 * {@code META-INF/services/io.mosip.liveness.core.api.LivenessEngineProvider} ({@link ServiceLoader}). The ONNX
 * engine is always built here, with the Android model assets, execution provider and warm-up, even though the
 * liveness-onnx jar also lists a provider for it.
 *
 * <p>Loading verifies the signed model bundle and creates the ONNX Runtime sessions (about 1-3 s on a phone).
 * {@link #preload()} starts it early (when the app starts); {@link #await(long)} blocks the calling liveness
 * thread until the engine is ready. A model integrity failure (signature, hash or version) is kept until the app
 * restarts: liveness then fails closed with audit {@code REG-EVT-LV14}. Other load failures are retried at the
 * next {@link #preload()} or {@link #await(long)}.
 *
 * <p>The built-in engine loads the bundle a supervisor imported ({@link ModelBundleStore}, bonus B6) when there is
 * one, else the bundle in the APK assets, and refuses bundles below {@code model.min_version} or the store's version
 * floor. {@link #switchModels(ModelSource)} replaces the running engine by one built from another bundle, after a
 * self-test.
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

    /** Builds the built-in engine over a given model bundle; may block. */
    @FunctionalInterface
    interface ModelBuilder {

        /**
         * Creates a ready engine over a bundle.
         *
         * @param source the bundle
         * @return the engine
         * @throws EngineException if the models cannot be verified or loaded, or a vendor engine is configured
         */
        LivenessEngine build(ModelSource source) throws EngineException;
    }

    private final Factory factory;
    private final ModelBuilder modelBuilder;
    private final BooleanSupplier importSupported;
    private final AtomicInteger openCaptures = new AtomicInteger();
    private volatile String acceleratorStatus = Accelerator.CPU.name();
    private LivenessEngine engine;
    private EngineException failure;
    private CountDownLatch loading;
    private boolean closed;
    private int generation;

    /**
     * Creates a loader around any engine factory (tests pass scripted engines).
     *
     * @param factory engine factory
     */
    public EngineLoader(Factory factory) {
        this(factory, null);
    }

    /**
     * Creates a loader whose engine can also be rebuilt over another model bundle ({@link #switchModels}).
     *
     * @param factory      engine factory
     * @param modelBuilder builds the engine over a given bundle; {@code null} when models cannot be switched
     */
    EngineLoader(Factory factory, ModelBuilder modelBuilder) {
        this(factory, modelBuilder, () -> true);
    }

    private EngineLoader(Factory factory, ModelBuilder modelBuilder, BooleanSupplier importSupported) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.modelBuilder = modelBuilder;
        this.importSupported = importSupported;
    }

    /**
     * Creates the loader of the configured engine ({@link #fromConfig}) with a new metrics collector and the
     * device's ONNX Runtime execution providers.
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
     * Creates the loader of the configured engine ({@link #fromConfig}). Kept under this name for existing
     * callers; the built-in ONNX engine is still what loads unless {@code mosip.registration.liveness.engine}
     * names a vendor engine.
     *
     * @param assets    application assets
     * @param config    liveness configuration
     * @param decoder   platform image decoder
     * @param metrics   metrics shared by all sessions of the built-in engine
     * @param providers ONNX Runtime execution provider access
     * @return the loader; nothing is loaded until {@link #preload()} or {@link #await(long)}
     */
    public static EngineLoader onnx(AssetManager assets, ConfigSource config, ImageDecoder decoder,
                                    LivenessMetrics metrics, AcceleratorCustomizer.ExecutionProviders providers) {
        return fromConfig(assets, config, decoder, metrics, providers);
    }

    /**
     * Creates the loader of the engine named by {@code mosip.registration.liveness.engine}: the built-in ONNX
     * engine ({@link #onnxOnly}) for {@code mosip-onnx-default} or no value, otherwise the vendor engine of the
     * {@link LivenessEngineProvider} with that ID found by {@link ServiceLoader}. A vendor engine gets the
     * configuration, the model assets and the image decoder in its {@link EngineContext}. An ID that no provider
     * serves fails the load with {@code CONFIG_INVALID}: liveness fails closed, and the load is tried again at the
     * next capture.
     *
     * @param assets    application assets
     * @param config    liveness configuration
     * @param decoder   platform image decoder
     * @param metrics   metrics shared by all sessions of the built-in engine
     * @param providers ONNX Runtime execution provider access
     * @return the loader; nothing is loaded until {@link #preload()} or {@link #await(long)}
     */
    public static EngineLoader fromConfig(AssetManager assets, ConfigSource config, ImageDecoder decoder,
                                          LivenessMetrics metrics, AcceleratorCustomizer.ExecutionProviders providers) {
        return fromConfig(assets, config, decoder, metrics, providers, null);
    }

    /**
     * Creates the loader of the engine named by {@code mosip.registration.liveness.engine}, as the method above,
     * with the built-in engine reading imported bundles from a store.
     *
     * @param assets    application assets
     * @param config    liveness configuration
     * @param decoder   platform image decoder
     * @param metrics   metrics shared by all sessions of the built-in engine
     * @param providers ONNX Runtime execution provider access
     * @param store     imported model bundles; {@code null} for the APK bundle only
     * @return the loader; nothing is loaded until {@link #preload()} or {@link #await(long)}
     */
    public static EngineLoader fromConfig(AssetManager assets, ConfigSource config, ImageDecoder decoder,
                                          LivenessMetrics metrics, AcceleratorCustomizer.ExecutionProviders providers,
                                          ModelBundleStore store) {
        EngineLoader builtIn = onnxOnly(assets, config, decoder, metrics, providers, store);
        return select(config, builtIn, EngineLoader::serviceProviders,
                () -> EngineContext.builder().config(config).models(new AssetModelSource(assets))
                        .imageDecoder(decoder).build());
    }

    /**
     * Creates the loader that picks the engine by {@code mosip.registration.liveness.engine}.
     *
     * @param config    liveness configuration
     * @param builtIn   loader of the built-in ONNX engine, used for {@code mosip-onnx-default} or no value; its
     *                  factory runs on the returned loader's thread and its execution status is reported
     * @param providers vendor engine providers, listed when the engine loads
     * @param context   platform services handed to a vendor engine
     * @return the loader; nothing is loaded until {@link #preload()} or {@link #await(long)}
     */
    static EngineLoader select(ConfigSource config, EngineLoader builtIn,
                               Supplier<? extends Iterable<LivenessEngineProvider>> providers,
                               Supplier<EngineContext> context) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(builtIn, "builtIn");
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(context, "context");
        EngineLoader[] self = new EngineLoader[1];
        ModelBuilder models = builtIn.modelBuilder == null ? null : source -> {
            if (!LivenessConfigKeys.DEFAULT_ENGINE.equals(configuredEngineId(config))) {
                throw new EngineException(EngineErrorCode.CONFIG_INVALID, "model import needs the default engine");
            }
            LivenessEngine e = builtIn.modelBuilder.build(source);
            self[0].acceleratorStatus = builtIn.acceleratorStatus;
            return e;
        };
        self[0] = new EngineLoader(() -> {
            String id = configuredEngineId(config);
            if (LivenessConfigKeys.DEFAULT_ENGINE.equals(id)) {
                LivenessEngine e = builtIn.factory.create();
                self[0].acceleratorStatus = builtIn.acceleratorStatus;
                return e;
            }
            for (LivenessEngineProvider p : providers.get()) {
                if (p != null && id.equals(p.engineId())) {
                    long start = System.nanoTime();
                    LivenessEngine e = p.create(context.get());
                    if (e == null) {
                        throw new EngineException(EngineErrorCode.MODEL_LOAD,
                                "engine provider " + id + " returned no engine");
                    }
                    self[0].acceleratorStatus = "vendor engine " + id;
                    Log.i(TAG, "Liveness engine ready: " + e.info().tag() + " from " + p.getClass().getName()
                            + " in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
                    return e;
                }
            }
            Log.e(TAG, "No liveness engine provider for " + LivenessConfigKeys.ENGINE + "=" + id);
            throw new EngineException(EngineErrorCode.CONFIG_INVALID, "no liveness engine with ID " + id);
        }, models, () -> LivenessConfigKeys.DEFAULT_ENGINE.equals(configuredEngineId(config)));
        return self[0];
    }

    /**
     * Returns the configured engine ID.
     *
     * @param config liveness configuration
     * @return the trimmed value of {@code mosip.registration.liveness.engine}, or {@code mosip-onnx-default} when
     *         it is missing or blank
     */
    static String configuredEngineId(ConfigSource config) {
        String id = config.get(LivenessConfigKeys.ENGINE).map(String::trim).orElse("");
        return id.isEmpty() ? LivenessConfigKeys.DEFAULT_ENGINE : id;
    }

    /**
     * Lists the engine providers on the class path ({@code META-INF/services}). A broken provider entry is logged
     * and the providers found so far are kept.
     *
     * @return the providers that could be created
     */
    static List<LivenessEngineProvider> serviceProviders() {
        List<LivenessEngineProvider> all = new ArrayList<>();
        try {
            for (LivenessEngineProvider p : ServiceLoader.load(LivenessEngineProvider.class,
                    EngineLoader.class.getClassLoader())) {
                all.add(p);
            }
        } catch (ServiceConfigurationError | RuntimeException | LinkageError e) {
            Log.e(TAG, "Liveness engine providers could not be listed: " + e.getClass().getSimpleName());
        }
        return all;
    }

    /**
     * Creates the loader of the built-in ONNX engine over the model bundle in the APK assets, with the execution
     * provider of {@link Accelerator#CONFIG_KEY} and the given metrics collector, whatever
     * {@code mosip.registration.liveness.engine} says.
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
    public static EngineLoader onnxOnly(AssetManager assets, ConfigSource config, ImageDecoder decoder,
                                        LivenessMetrics metrics, AcceleratorCustomizer.ExecutionProviders providers) {
        return onnxOnly(assets, config, decoder, metrics, providers, null);
    }

    /**
     * Creates the loader of the built-in ONNX engine, as the method above, over the active bundle of a store of
     * imported bundles when it has one, else the APK bundle. The lowest accepted bundle version is
     * {@code model.min_version} or the store's version floor, whichever is higher (anti-rollback). An imported bundle
     * that later fails its checks is not replaced by the older APK bundle: the engine fails closed instead.
     *
     * @param assets    application assets
     * @param config    liveness configuration ({@code model.min_version}, {@code android.accelerator})
     * @param decoder   platform image decoder
     * @param metrics   metrics shared by all sessions of the engine
     * @param providers ONNX Runtime execution provider access
     * @param store     imported model bundles; {@code null} for the APK bundle only
     * @return the loader; nothing is loaded until {@link #preload()} or {@link #await(long)}
     */
    public static EngineLoader onnxOnly(AssetManager assets, ConfigSource config, ImageDecoder decoder,
                                        LivenessMetrics metrics, AcceleratorCustomizer.ExecutionProviders providers,
                                        ModelBundleStore store) {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(providers, "providers");
        EngineLoader[] self = new EngineLoader[1];
        ModelBuilder models = source -> {
            Accelerator accelerator = Accelerator.parse(config.get(Accelerator.CONFIG_KEY).orElse(null));
            String minimum = minimumModelVersion(config, store);
            long start = System.nanoTime();
            LivenessEngine e;
            try {
                e = buildOnnx(source, minimum, decoder, metrics, accelerator, providers, self[0]);
            } catch (EngineException | RuntimeException | LinkageError failure) {
                boolean integrity = failure instanceof EngineException
                        && ((EngineException) failure).getErrorCode().isModelIntegrityFailure();
                if (accelerator == Accelerator.CPU || integrity) {
                    throw failure;
                }
                Log.w(TAG, "Liveness engine did not load with " + accelerator + " ("
                        + failure.getClass().getSimpleName() + "); loading it on the CPU");
                e = buildOnnx(source, minimum, decoder, metrics, Accelerator.CPU, providers, self[0]);
                self[0].acceleratorStatus = "CPU (" + accelerator + " failed to load)";
            }
            Log.i(TAG, "Liveness engine ready: " + e.info().tag() + " in "
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms, execution "
                    + self[0].acceleratorStatus);
            return e;
        };
        self[0] = new EngineLoader(() -> models.build(activeModelSource(assets, store)), models);
        return self[0];
    }

    /**
     * Returns the bundle the built-in engine loads: the active imported bundle of the store when there is one, else
     * the bundle in the APK assets.
     *
     * @param assets application assets
     * @param store  imported model bundles, or {@code null}
     * @return the model source
     */
    public static ModelSource activeModelSource(AssetManager assets, ModelBundleStore store) {
        Optional<ModelBundleStore.Installed> imported = store == null ? Optional.empty() : store.active();
        if (imported.isPresent()) {
            Log.i(TAG, "Liveness models: imported bundle " + imported.get().bundle() + " "
                    + imported.get().version());
            return ModelSources.directory(imported.get().folder().toFile());
        }
        return new AssetModelSource(assets);
    }

    /**
     * Returns the lowest bundle version the built-in engine accepts: {@code model.min_version} or the store's
     * version floor, whichever is higher (anti-rollback).
     *
     * @param config liveness configuration
     * @param store  imported model bundles, or {@code null}
     * @return {@code MAJOR.MINOR.PATCH}; an invalid {@code model.min_version} is returned unchanged, so the engine
     *         reports {@code CONFIG_INVALID}
     */
    static String minimumModelVersion(ConfigSource config, ModelBundleStore store) {
        String configured = config.get(LivenessConfigKeys.MODEL_MIN_VERSION)
                .orElse(LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION);
        Optional<String> floor = store == null ? Optional.empty() : store.floor();
        if (!floor.isPresent()) {
            return configured;
        }
        try {
            return SemanticVersion.parse(floor.get()).compareTo(SemanticVersion.parse(configured.trim())) > 0
                    ? floor.get() : configured;
        } catch (IllegalArgumentException e) {
            return configured;
        }
    }

    private static LivenessEngine buildOnnx(ModelSource source, String minimumVersion, ImageDecoder decoder,
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
        LivenessEngine e = OnnxLivenessEngines.builder(source)
                .minimumVersion(minimumVersion)
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
     * Tells whether the running engine's models can be replaced by an imported bundle.
     *
     * @return {@code true} for the built-in ONNX engine ({@code mosip-onnx-default})
     */
    public boolean supportsModelImport() {
        return modelBuilder != null && importSupported.getAsBoolean();
    }

    /**
     * Records that a liveness capture screen opened; models are not swapped until it has closed.
     */
    public void captureStarted() {
        openCaptures.incrementAndGet();
    }

    /**
     * Records that a liveness capture screen closed (call once per {@link #captureStarted()}).
     */
    public void captureEnded() {
        openCaptures.updateAndGet(n -> Math.max(0, n - 1));
    }

    /**
     * Tells whether a liveness capture screen is open.
     *
     * @return {@code true} between {@link #captureStarted()} and {@link #captureEnded()}
     */
    public boolean captureInProgress() {
        return openCaptures.get() > 0;
    }

    /**
     * Returns the version of the model bundle the loaded engine runs. Does not wait or load.
     *
     * @return the version; empty before the engine is loaded or for a vendor engine
     */
    public synchronized Optional<String> bundleVersion() {
        return engine instanceof OnnxLivenessEngine
                ? Optional.ofNullable(((OnnxLivenessEngine) engine).bundleVersion()) : Optional.empty();
    }

    /**
     * Replaces the running engine by one built from another verified bundle (offline model import). The new
     * engine is built (signature, hashes and version checked again) and self-tested first; only then is it swapped
     * in and the old one closed. On any failure the running engine stays as it was and the exception is returned
     * to the caller, which audits it. A load still running in the background is discarded when it finishes.
     *
     * @param source the new bundle
     * @throws EngineException {@code CONFIG_INVALID} when a vendor engine is configured, {@code NOT_READY} after
     *                         {@link #close()}; the load or self-test error
     */
    public void switchModels(ModelSource source) throws EngineException {
        Objects.requireNonNull(source, "source");
        if (!supportsModelImport()) {
            throw new EngineException(EngineErrorCode.CONFIG_INVALID, "model import needs the default engine");
        }
        LivenessEngine created = null;
        try {
            created = modelBuilder.build(source);
            selfTest(created);
        } catch (EngineException e) {
            closeQuietly(created);
            throw e;
        } catch (RuntimeException | LinkageError e) {
            closeQuietly(created);
            throw new EngineException(EngineErrorCode.MODEL_LOAD,
                    "liveness engine could not start: " + e.getClass().getSimpleName(), e);
        }
        LivenessEngine old;
        CountDownLatch pending;
        synchronized (this) {
            if (closed) {
                closeQuietly(created);
                throw new EngineException(EngineErrorCode.NOT_READY, "engine loader is closed");
            }
            old = engine;
            engine = created;
            failure = null;
            generation++;
            pending = loading;
            loading = null;
        }
        if (pending != null) {
            pending.countDown();
        }
        closeQuietly(old);
        Log.i(TAG, "Liveness engine switched to " + created.info().tag());
    }

    /**
     * Checks that a new engine reports ready and, for the built-in engine, runs the face detector and the passive
     * PAD model once on a blank frame, so a model that does not run is found before the switch, not at the next
     * capture. Unlike {@link #warmUp}, a failure here is thrown.
     *
     * @param created the new engine
     * @throws EngineException {@code NOT_READY} or the inference error
     */
    static void selfTest(LivenessEngine created) throws EngineException {
        if (!created.isReady()) {
            throw new EngineException(EngineErrorCode.NOT_READY, "engine reports not ready after creation");
        }
        if (!(created instanceof OnnxLivenessEngine)) {
            return;
        }
        int w = 160;
        int h = 120;
        ImageBuffer blank = ImageBuffer.ofBytes(w, h, PixelFormat.RGB888, new byte[w * h * 3]);
        try {
            OnnxModelSet models = ((OnnxLivenessEngine) created).modelSet();
            models.detectorOnlyAnalyzer().analyze(blank);
            if (models.passivePad() != null) {
                models.passivePad().score(blank, FaceBox.of(40, 20, 80, 80, 1.0));
            }
        } finally {
            blank.wipe();
        }
    }

    private static void closeQuietly(LivenessEngine e) {
        if (e == null) {
            return;
        }
        try {
            e.close();
        } catch (RuntimeException ignored) {
            // close() must not throw; nothing more to do.
        }
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
        int started = generation;
        Thread t = new Thread(() -> load(latch, started), "liveness-engine-load");
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

    private void load(CountDownLatch latch, int started) {
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
            if (generation != started) {
                // switchModels installed another engine while this one loaded: keep that one.
                closeQuietly(created);
                latch.countDown();
                return;
            }
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
