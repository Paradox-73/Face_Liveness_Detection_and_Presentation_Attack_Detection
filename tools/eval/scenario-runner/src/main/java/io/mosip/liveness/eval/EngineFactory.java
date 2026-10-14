/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.config.PolicyKey;
import io.mosip.liveness.core.engine.DefaultLivenessEngine;
import io.mosip.liveness.core.engine.EngineParameters;
import io.mosip.liveness.onnx.OnnxLivenessEngines;
import io.mosip.liveness.onnx.OnnxModelSet;
import io.mosip.liveness.onnx.model.Hex;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Loads the signed ONNX model bundle once and builds {@link DefaultLivenessEngine}s on it, one per set of engine
 * parameters, with timing decorators around the decoder and every model adapter.
 *
 * <p>The assembly mirrors {@link OnnxLivenessEngines.Builder#build()} (same engine identifier, version, supported
 * challenges and parameter order: shipped defaults, then the bundle's signed {@code engineParams}, then the
 * scenario's overrides). It is repeated here, instead of calling that builder, so that every adapter call can be
 * wrapped in a {@link TimedModels} decorator for the per-stage latency figures, and so that one loaded model set
 * serves engines with different parameters.
 */
final class EngineFactory implements AutoCloseable {

    private final OnnxModelSet models;
    private final String bundleVersion;
    private final Map<String, String> signedParams;
    private final StageTimer timer = new StageTimer();
    private final ImageIoDecoder decoder = new ImageIoDecoder();

    private EngineFactory(OnnxModelSet models, String bundleVersion, Map<String, String> signedParams) {
        this.models = models;
        this.bundleVersion = bundleVersion;
        this.signedParams = Collections.unmodifiableMap(new TreeMap<>(signedParams));
    }

    /**
     * Verifies and loads the model bundle.
     *
     * @param modelsDir folder with {@code manifest.json}, {@code manifest.jws} and the model files, or
     *                  {@code null} for the bundle on the class path (the {@code liveness-onnx} models jar)
     * @param threads   ONNX Runtime intra-op threads per inference
     * @param provider  ONNX Runtime execution provider (falls back to CPU when the runtime lacks it)
     * @return the factory
     * @throws EngineException if the bundle fails its signature or hash checks, or a model cannot be loaded
     */
    static EngineFactory load(File modelsDir, int threads, OnnxRuntimeOptions.ExecutionProvider provider)
            throws EngineException {
        ModelSource source = modelsDir == null ? ModelSources.classpath(EngineFactory.class.getClassLoader())
                : ModelSources.directory(modelsDir);
        ModelRegistry registry = ModelRegistry.builder(source)
                .minimumVersion(PolicyKey.MODEL_MIN_VERSION.defaultValue()).open();
        OnnxRuntimeOptions options = OnnxRuntimeOptions.builder().intraOpThreads(threads)
                .executionProvider(provider).build();
        return new EngineFactory(OnnxModelSet.load(registry, options), registry.bundleVersion(),
                registry.manifest().engineParams());
    }

    /**
     * Builds an engine that shares the loaded models.
     *
     * @param params detection parameters (defaults plus scenario overrides)
     * @return the engine
     */
    DefaultLivenessEngine engine(EngineParameters params) {
        EngineInfo.Builder info = EngineInfo.builder(OnnxLivenessEngines.ENGINE_ID, OnnxLivenessEngines.ENGINE_VERSION)
                .vendor(OnnxLivenessEngines.VENDOR).supportedChallenges(OnnxLivenessEngines.supportedChallenges(models
                        .faceAnalyzer().features())).requiresStream(true);
        for (ModelInfo m : models.models()) {
            info.model(m);
        }
        return DefaultLivenessEngine.builder(info.build())
                .faceAnalyzer(new TimedModels.Analyzer(models.faceAnalyzer(), timer))
                .passivePad(new TimedModels.Pad(models.passivePad(), timer))
                .faceEmbedder(new TimedModels.Embedder(models.faceEmbedder(), timer))
                .imageDecoder(new TimedModels.Decoder(decoder, timer))
                .parameters(params)
                .build();
    }

    /**
     * Builds engine parameters: the shipped defaults, then the bundle's signed values, then the overrides.
     *
     * @param overrides parameter key to value
     * @return the parameters
     * @throws IllegalArgumentException if a key is unknown or a value is out of range
     */
    EngineParameters parameters(Map<String, String> overrides) {
        return parameters(signedParams, overrides);
    }

    /**
     * Merges signed parameters and overrides (overrides win) and validates them.
     *
     * @param signed    the bundle's signed {@code engineParams}
     * @param overrides parameter key to value
     * @return the parameters
     * @throws IllegalArgumentException if a key is unknown or a value is out of range
     */
    static EngineParameters parameters(Map<String, String> signed, Map<String, String> overrides) {
        Map<String, String> merged = new TreeMap<>(signed);
        merged.putAll(overrides);
        return EngineParameters.fromMap(merged);
    }

    /**
     * Returns the bundle's signed engine parameters.
     *
     * @return key to value, possibly empty
     */
    Map<String, String> signedParams() {
        return signedParams;
    }

    /**
     * Returns the timer the decorators write to.
     *
     * @return the shared stage timer
     */
    StageTimer timer() {
        return timer;
    }

    /**
     * Returns the untimed decoder (also used for the simulated signed capture).
     *
     * @return the decoder
     */
    ImageIoDecoder decoder() {
        return decoder;
    }

    /**
     * Returns the loaded model set, for the pack inspector.
     *
     * @return the models
     */
    OnnxModelSet models() {
        return models;
    }

    /**
     * Returns the bundle version.
     *
     * @return for example {@code "1.0.0"}
     */
    String bundleVersion() {
        return bundleVersion;
    }

    /**
     * Returns one SHA-256 over the identifier, version and file hash of every loaded model, so that results can be
     * tied to the exact model set.
     *
     * @return lower-case hex
     */
    String modelSetSha256() {
        StringBuilder sb = new StringBuilder();
        List<ModelInfo> infos = models.models();
        for (ModelInfo m : infos) {
            sb.append(m.id()).append(':').append(m.version()).append(':').append(m.sha256()).append('\n');
        }
        return Hex.sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        models.close();
    }
}
