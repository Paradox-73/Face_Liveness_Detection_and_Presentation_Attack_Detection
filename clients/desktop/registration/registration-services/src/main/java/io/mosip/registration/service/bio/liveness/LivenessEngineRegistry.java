/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.EngineContext;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessEngineProvider;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.engine.LivenessMetrics;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.onnx.OnnxLivenessEngine;
import io.mosip.liveness.onnx.OnnxLivenessEngines;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.liveness.onnx.model.SemanticVersion;
import io.mosip.registration.config.AppConfig;

/**
 * Creates the configured liveness engine once and keeps it for the life of the client.
 *
 * <p><b>Engine choice</b> ({@code mosip.registration.liveness.engine}): the default {@code mosip-onnx-default}
 * engine is built with {@link OnnxLivenessEngines} from the signed model bundle on the class path
 * ({@code liveness-onnx-*-models.jar}), checked against {@code model.min_version}. Any other ID is looked up
 * among {@link LivenessEngineProvider} Spring beans (a vendor jar with a configuration class in
 * {@code io.mosip.registration.api.config}) and then through {@link ServiceLoader}. A bundle imported with
 * {@link LivenessModelImporter} takes the place of the class-path bundle, and the minimum version is raised to the
 * model store's version floor so an older bundle can never be loaded again (anti-rollback).
 *
 * <p><b>Loading</b> starts in the background when the Spring context starts and the master switch is on, so the
 * first capture does not wait for the models (1-2 s); {@link #engine()} also loads on demand. A self-test then
 * runs one inference. A failure is logged with its cause, audited ({@code REG-EVT-LV13}, plus
 * {@code REG-EVT-LV14} for a model hash, signature or version failure) and makes every liveness session fail
 * closed with an engine error. A model integrity failure is final until the client restarts; other failures are
 * retried at the next request after {@link #RETRY_AFTER_MS}.
 *
 * <p>Thread-safe.
 */
@Component
public class LivenessEngineRegistry {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessEngineRegistry.class);

	/** Minimum time between two load attempts after a failure that is not a model integrity failure. */
	public static final long RETRY_AFTER_MS = 30_000L;

	/** JVM system property that turns the start-up preload off when {@code false}. */
	public static final String PRELOAD_PROPERTY = "mosip.liveness.preload";

	private static final int SELF_TEST_WIDTH = 160;
	private static final int SELF_TEST_HEIGHT = 120;

	/** Load state of the engine. */
	public enum Status {
		/** Nothing loaded yet. */
		NOT_LOADED,
		/** Models are being verified and loaded. */
		LOADING,
		/** The engine passed its self-test and can open sessions. */
		READY,
		/** Loading or the self-test failed; sessions fail closed. */
		FAILED
	}

	private final LivenessConfig config;
	private final DesktopImageDecoder imageDecoder;
	private final DesktopAuditSink auditSink;
	private final DesktopDiagnosticSink diagnosticSink;
	private final LivenessMetrics metrics = new LivenessMetrics();
	private final Object lock = new Object();

	@Autowired(required = false)
	private List<LivenessEngineProvider> providerBeans = Collections.emptyList();

	private volatile ModelSource modelSource = ModelSources.classpath(LivenessEngineRegistry.class.getClassLoader());
	private volatile LivenessModelStore modelStore;
	private volatile Status status = Status.NOT_LOADED;
	private volatile LivenessEngine engine;
	private volatile EngineException failure;
	private volatile long failedAtMillis;

	/**
	 * Creates the registry.
	 *
	 * @param config         liveness configuration
	 * @param imageDecoder   image decoder handed to the engine
	 * @param auditSink      audit sink for engine-level events
	 * @param diagnosticSink diagnostic sink handed to the engine
	 */
	@Autowired
	public LivenessEngineRegistry(LivenessConfig config, DesktopImageDecoder imageDecoder, DesktopAuditSink auditSink,
			DesktopDiagnosticSink diagnosticSink) {
		this.config = Objects.requireNonNull(config, "config");
		this.imageDecoder = Objects.requireNonNull(imageDecoder, "imageDecoder");
		this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
		this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
	}

	/**
	 * Starts loading the engine on a background thread when the master switch is on. The JVM system property
	 * {@value #PRELOAD_PROPERTY}{@code =false} turns this off (unit tests that start a Spring context); the engine
	 * then loads at first use.
	 */
	@PostConstruct
	public void preload() {
		if (!Boolean.parseBoolean(System.getProperty(PRELOAD_PROPERTY, "true"))) {
			return;
		}
		if (!config.isMasterSwitchOn()) {
			LOGGER.info("Liveness is switched off; the liveness engine loads on first use only");
			return;
		}
		Thread t = new Thread(() -> {
			try {
				engine();
			} catch (EngineException e) {
				// Already logged and audited by engine().
			}
		}, "liveness-engine-loader");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Returns the engine, loading it on first use. Blocks while another thread loads it.
	 *
	 * @return a ready engine
	 * @throws EngineException the load or self-test failure; the same exception while it is final or within the
	 *                         retry delay
	 */
	public LivenessEngine engine() throws EngineException {
		LivenessEngine current = engine;
		if (current != null) {
			return current;
		}
		synchronized (lock) {
			if (engine != null) {
				return engine;
			}
			EngineException last = failure;
			if (last != null && (last.getErrorCode().isModelIntegrityFailure()
					|| System.currentTimeMillis() - failedAtMillis < RETRY_AFTER_MS)) {
				throw last;
			}
			status = Status.LOADING;
			long start = System.currentTimeMillis();
			LivenessEngine created = null;
			try {
				created = create();
				selfTest(created);
				engine = created;
				failure = null;
				status = Status.READY;
				LOGGER.info("Liveness engine {} ready in {} ms; models {}", created.info().tag(),
						System.currentTimeMillis() - start, created.info().models());
				return created;
			} catch (EngineException e) {
				closeQuietly(created);
				fail(e);
				throw e;
			} catch (RuntimeException | LinkageError e) {
				closeQuietly(created);
				EngineException wrapped = new EngineException(EngineErrorCode.MODEL_LOAD,
						"liveness engine could not start: " + e.getClass().getSimpleName(), e);
				fail(wrapped);
				throw wrapped;
			}
		}
	}

	/**
	 * Returns the load state.
	 *
	 * @return the status
	 */
	public Status status() {
		return status;
	}

	/**
	 * Returns the engine description once loaded.
	 *
	 * @return engine and model versions; empty before the engine is ready
	 */
	public Optional<EngineInfo> info() {
		LivenessEngine e = engine;
		return e == null ? Optional.<EngineInfo>empty() : Optional.of(e.info());
	}

	/**
	 * Returns the last load failure.
	 *
	 * @return the failure; empty when the engine is ready or not yet loaded
	 */
	public Optional<EngineException> failure() {
		return Optional.ofNullable(failure);
	}

	/**
	 * Returns the metrics the sessions of the default engine update.
	 *
	 * @return the metrics; they stay empty for a vendor engine that keeps its own
	 */
	public LivenessMetrics metrics() {
		return metrics;
	}

	/** Releases the engine and its native resources. */
	@PreDestroy
	public void close() {
		synchronized (lock) {
			LivenessEngine e = engine;
			engine = null;
			status = Status.NOT_LOADED;
			closeQuietly(e);
		}
	}

	/**
	 * Replaces the model bundle source (tests and tools).
	 *
	 * @param source model bundle source
	 */
	void setModelSource(ModelSource source) {
		this.modelSource = Objects.requireNonNull(source, "source");
	}

	/**
	 * Replaces the provider beans (tests).
	 *
	 * @param providers providers to consider before the service loader
	 */
	void setProviderBeans(List<LivenessEngineProvider> providers) {
		this.providerBeans = providers == null ? Collections.<LivenessEngineProvider>emptyList() : providers;
	}

	private LivenessEngine create() throws EngineException {
		String id = config.engineId();
		if (OnnxLivenessEngines.ENGINE_ID.equals(id)) {
			return buildDefault(activeModelSource());
		}
		EngineContext context = EngineContext.builder().config(config).models(modelSource).imageDecoder(imageDecoder)
				.auditSink(auditSink).diagnosticSink(diagnosticSink).build();
		for (LivenessEngineProvider p : providers()) {
			if (id.equals(p.engineId())) {
				LOGGER.info("Creating liveness engine {} with {}", id, p.getClass().getName());
				return p.create(context);
			}
		}
		throw new EngineException(EngineErrorCode.CONFIG_INVALID, "no liveness engine with ID " + id);
	}

	/**
	 * Replaces the running default engine by one built from another verified bundle (offline model import). The new
	 * engine is built and self-tested first; only then is it swapped in and the old one closed. On any failure the
	 * running engine stays as it was and the exception is returned to the caller, which audits it.
	 *
	 * @param source the new bundle; checked again against its signature, hashes and the version floor
	 * @return the new engine's description
	 * @throws EngineException {@code CONFIG_INVALID} when a vendor engine is configured; the load or self-test error
	 */
	public EngineInfo switchModels(ModelSource source) throws EngineException {
		Objects.requireNonNull(source, "source");
		if (!isDefaultEngine()) {
			throw new EngineException(EngineErrorCode.CONFIG_INVALID, "model import needs the default engine");
		}
		synchronized (lock) {
			LivenessEngine created = null;
			try {
				created = buildDefault(source);
				selfTest(created);
			} catch (EngineException e) {
				closeQuietly(created);
				throw e;
			} catch (RuntimeException | LinkageError e) {
				closeQuietly(created);
				throw new EngineException(EngineErrorCode.MODEL_LOAD,
						"liveness engine could not start: " + e.getClass().getSimpleName(), e);
			}
			LivenessEngine old = engine;
			engine = created;
			failure = null;
			status = Status.READY;
			closeQuietly(old);
			LOGGER.info("Liveness engine switched to {}; models {}", created.info().tag(), created.info().models());
			return created.info();
		}
	}

	/**
	 * Tells whether the configured engine is the built-in ONNX engine, the only one whose models can be imported.
	 *
	 * @return {@code true} for {@code mosip-onnx-default}
	 */
	public boolean isDefaultEngine() {
		return OnnxLivenessEngines.ENGINE_ID.equals(config.engineId());
	}

	/**
	 * Returns the version of the running model bundle.
	 *
	 * @return the version; empty before the engine is loaded or for a vendor engine
	 */
	public Optional<String> bundleVersion() {
		LivenessEngine e = engine;
		return e instanceof OnnxLivenessEngine ? Optional.ofNullable(((OnnxLivenessEngine) e).bundleVersion())
				: Optional.<String>empty();
	}

	/**
	 * Returns the store of imported model bundles.
	 *
	 * @return the store in {@link LivenessConfig#modelStoreDirectory()}, unless a test replaced it
	 */
	public LivenessModelStore modelStore() {
		LivenessModelStore s = modelStore;
		return s != null ? s : new LivenessModelStore(config.modelStoreDirectory());
	}

	/**
	 * Replaces the model store (tests).
	 *
	 * @param store the store
	 */
	void setModelStore(LivenessModelStore store) {
		this.modelStore = Objects.requireNonNull(store, "store");
	}

	/**
	 * Returns the bundle the default engine loads: the active imported bundle when there is one, else the bundle on
	 * the class path. An imported bundle that later fails its checks is not replaced by the older class-path
	 * bundle: the engine fails closed instead.
	 */
	ModelSource activeModelSource() {
		Optional<LivenessModelStore.Installed> imported = modelStore().active();
		if (imported.isPresent()) {
			LOGGER.info("Liveness models: imported bundle {} {}", imported.get().bundle(), imported.get().version());
			return ModelSources.directory(imported.get().folder().toFile());
		}
		return modelSource;
	}

	/**
	 * Returns the lowest bundle version the engine accepts: {@code model.min_version} or the store's version floor,
	 * whichever is higher (anti-rollback).
	 *
	 * @return {@code MAJOR.MINOR.PATCH}
	 */
	String minimumModelVersion() {
		String configured = config.modelMinVersion();
		Optional<String> floor = modelStore().floor();
		if (!floor.isPresent()) {
			return configured;
		}
		try {
			return SemanticVersion.parse(floor.get()).compareTo(SemanticVersion.parse(configured)) > 0 ? floor.get()
					: configured;
		} catch (IllegalArgumentException e) {
			return configured; // the builder reports an invalid min_version as CONFIG_INVALID
		}
	}

	private LivenessEngine buildDefault(ModelSource source) throws EngineException {
		return OnnxLivenessEngines.builder(source).minimumVersion(minimumModelVersion()).imageDecoder(imageDecoder)
				.metrics(metrics).build();
	}

	private List<LivenessEngineProvider> providers() {
		List<LivenessEngineProvider> all = new ArrayList<>(providerBeans);
		try {
			for (LivenessEngineProvider p : ServiceLoader.load(LivenessEngineProvider.class,
					LivenessEngineRegistry.class.getClassLoader())) {
				all.add(p);
			}
		} catch (RuntimeException | LinkageError e) {
			LOGGER.error("Liveness engine providers could not be listed: {}", e.getClass().getSimpleName());
		}
		return all;
	}

	/**
	 * Checks that the engine reports ready and, for the default engine, runs the detector and both PAD models once
	 * on a blank image, so a broken native runtime shows at start-up rather than at the first capture.
	 */
	private static void selfTest(LivenessEngine created) throws EngineException {
		if (!created.isReady()) {
			throw new EngineException(EngineErrorCode.NOT_READY, "engine reports not ready after creation");
		}
		if (created instanceof OnnxLivenessEngine) {
			ImageBuffer blank = ImageBuffer.ofBytes(SELF_TEST_WIDTH, SELF_TEST_HEIGHT, PixelFormat.BGR888,
					new byte[SELF_TEST_WIDTH * SELF_TEST_HEIGHT * 3]);
			OnnxLivenessEngine onnx = (OnnxLivenessEngine) created;
			onnx.modelSet().detectorOnlyAnalyzer().analyze(blank);
			onnx.modelSet().passivePad().score(blank, FaceBox.of(40, 20, 80, 80, 1.0));
		}
	}

	private void fail(EngineException e) {
		failure = e;
		failedAtMillis = System.currentTimeMillis();
		status = Status.FAILED;
		LOGGER.error("Liveness engine {} is not available ({}): {}. Face captures that need liveness fail until "
				+ "this is fixed.", config.engineId(), e.getErrorCode(), e.getMessage());
		// An audit event needs a workflow; engine-level events are recorded under the resident workflow.
		AuditPayloadBuilder audit = new AuditPayloadBuilder(Workflow.REGISTRATION, "", Clock.system());
		auditSink.emit(audit.engineError(e.getErrorCode(), e.getClass().getSimpleName()));
		if (e.getErrorCode().isModelIntegrityFailure()) {
			auditSink.emit(audit.modelIntegrityFailed(config.engineId()));
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
}
