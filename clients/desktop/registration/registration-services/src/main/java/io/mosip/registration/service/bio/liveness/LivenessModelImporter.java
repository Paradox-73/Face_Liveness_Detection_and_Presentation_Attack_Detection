/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.onnx.bundle.ModelBundleImporter;
import io.mosip.liveness.onnx.bundle.ModelBundleImporter.Result;
import io.mosip.liveness.onnx.bundle.ModelBundleImporter.Status;
import io.mosip.registration.config.AppConfig;

/**
 * Secure offline model update (bonus B6) on the desktop: the shared {@link ModelBundleImporter} of
 * {@code liveness-onnx}, wired to this client's engine registry, face verification state, audit service and log.
 * The checks, store layout and audits are documented there.
 */
@Component
public class LivenessModelImporter {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessModelImporter.class);

	/** Builds and swaps in an engine for a verified bundle; {@link LivenessEngineRegistry#switchModels} by default. */
	@FunctionalInterface
	interface EngineSwitch {
		void switchTo(ModelSource source) throws EngineException;
	}

	private final ModelBundleImporter importer;

	/**
	 * Creates the importer.
	 *
	 * @param config       liveness configuration ({@code model.min_version}, trust anchors)
	 * @param engines      the engine registry that loads and switches the models
	 * @param orchestrator tells whether a face verification is running
	 * @param audit        audit sink
	 */
	@Autowired
	public LivenessModelImporter(LivenessConfig config, LivenessEngineRegistry engines,
			LivenessOrchestrator orchestrator, DesktopAuditSink audit) {
		this(config, engines, orchestrator, audit, Clock.system(), engines::switchModels);
	}

	LivenessModelImporter(LivenessConfig config, LivenessEngineRegistry engines, LivenessOrchestrator orchestrator,
			AuditSink audit, Clock clock, EngineSwitch engineSwitch) {
		this.importer = new ModelBundleImporter(config, engines::modelStore, engines::activeModelSource,
				new ModelBundleImporter.Engines() {
					@Override
					public boolean supportsModelImport() {
						return engines.isDefaultEngine();
					}

					@Override
					public boolean captureInProgress() {
						return orchestrator != null
								&& orchestrator.current().map(LivenessRun::isSessionActive).orElse(false);
					}

					@Override
					public Optional<String> bundleVersion() {
						return engines.bundleVersion();
					}

					@Override
					public void switchModels(ModelSource source) throws EngineException {
						engineSwitch.switchTo(source);
					}
				}, audit, clock, new ModelBundleImporter.Log() {
					@Override
					public void info(String message) {
						LOGGER.info(message);
					}

					@Override
					public void error(String message) {
						LOGGER.error(message);
					}
				});
	}

	/**
	 * Returns the key of the text shown to the user for an import result (labels bundle).
	 *
	 * @param status the result
	 * @return {@code liveness.models.result.<status in lower case>}
	 */
	public static String messageKey(Status status) {
		return "liveness.models.result." + status.name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Replaces the trust anchors (tests sign bundles with a generated key).
	 *
	 * @param anchors accepted signing keys; empty means the configured keys ({@code model.trust_anchor_pem})
	 */
	void setTrustAnchors(List<PublicKey> anchors) {
		importer.setTrustAnchors(anchors);
	}

	/**
	 * Describes the running model bundle, for the import screen.
	 *
	 * @return name, version and whether it was imported; empty when no bundle can be read
	 */
	public Optional<Result> current() {
		return importer.current();
	}

	/**
	 * Verifies a bundle, stores it and switches the engine to it.
	 *
	 * @param location folder with {@code manifest.json}, {@code manifest.jws} and the model files, or a {@code .zip}
	 *                 file with the same content
	 * @param userId   user who imports (for the log; the audit service records the session user)
	 * @param roles    roles of that user
	 * @return the result; never throws for a bad bundle
	 */
	public Result importBundle(Path location, String userId, Collection<String> roles) {
		return importer.importBundle(location, userId, roles);
	}
}
