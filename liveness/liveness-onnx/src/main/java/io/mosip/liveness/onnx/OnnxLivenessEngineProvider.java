/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineContext;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessEngineProvider;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;

/**
 * {@link LivenessEngineProvider} for the default ONNX engine ({@value OnnxLivenessEngines#ENGINE_ID}), registered
 * in {@code META-INF/services/io.mosip.liveness.core.api.LivenessEngineProvider} so a client finds it with
 * {@link java.util.ServiceLoader}:
 *
 * <pre>{@code
 * for (LivenessEngineProvider p : ServiceLoader.load(LivenessEngineProvider.class)) {
 *     if (p.engineId().equals(config.get(LivenessConfigKeys.ENGINE).orElse(LivenessConfigKeys.DEFAULT_ENGINE))) {
 *         engine = p.create(context);   // blocks while the models load
 *     }
 * }
 * }</pre>
 *
 * <p>{@link #create(EngineContext)} reads the bundle from {@link EngineContext#models()}, requires at least
 * {@code mosip.registration.liveness.model.min_version}, uses the ONNX Runtime back end named by
 * {@code mosip.registration.liveness.engine.execution_provider} (default CPU), trusts the built-in development key, and returns an
 * {@link OnnxLivenessEngine}. It loads synchronously, so the engine is ready when it returns. A bundle that fails
 * verification throws an {@link EngineException} whose code has
 * {@link io.mosip.liveness.core.EngineErrorCode#isModelIntegrityFailure()} set; the client audits
 * {@code REG-EVT-LV14} with the workflow that needed the engine, because an audit event needs one.
 */
public final class OnnxLivenessEngineProvider implements LivenessEngineProvider {

    /** Creates the provider; loads nothing. */
    public OnnxLivenessEngineProvider() {
        // ServiceLoader needs a public no-argument constructor; models load in create().
    }

    @Override
    public String engineId() {
        return OnnxLivenessEngines.ENGINE_ID;
    }

    @Override
    public LivenessEngine create(EngineContext context) throws EngineException {
        String minimumVersion = context.config().get(LivenessConfigKeys.MODEL_MIN_VERSION)
                .orElse(LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION);
        return OnnxLivenessEngines.builder(context.models()).minimumVersion(minimumVersion)
                .runtimeOptions(OnnxRuntimeOptions.fromConfig(context.config()).build())
                .imageDecoder(context.imageDecoder()).build();
    }

    @Override
    public String toString() {
        return "OnnxLivenessEngineProvider[" + engineId() + "]";
    }
}
