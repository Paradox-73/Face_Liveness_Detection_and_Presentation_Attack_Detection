/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.onnx.detect.YuNetFaceDetector;
import io.mosip.liveness.onnx.embed.SFaceEmbedder;
import io.mosip.liveness.onnx.mesh.FaceMeshLandmarker;
import io.mosip.liveness.onnx.model.ModelEntry;
import io.mosip.liveness.onnx.model.ModelIds;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.pad.MiniFasModel;
import io.mosip.liveness.onnx.pad.MiniFasPassivePad;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;
import io.mosip.liveness.onnx.runtime.OrtModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Loads the models of a verified {@link ModelRegistry} into ONNX Runtime and builds the adapters: the
 * {@link OnnxFaceAnalyzer}, the {@link MiniFasPassivePad} and the {@link SFaceEmbedder}.
 *
 * <pre>{@code
 * ModelRegistry registry = ModelRegistry.builder(ModelSources.classpath(loader)).minimumVersion(minVersion).open();
 * try (OnnxModelSet models = OnnxModelSet.load(registry, OnnxRuntimeOptions.defaults())) {
 *     FaceObservation obs = models.faceAnalyzer().analyze(image);
 *     PadScore pad = models.passivePad().score(image, obs.primary().get().box());
 * }
 * }</pre>
 *
 * <p>Model bytes come only from {@link ModelRegistry#load(String)}, which checks them against the signed
 * manifest first. {@link #close()} releases the native sessions.
 */
public final class OnnxModelSet implements AutoCloseable {

    private final List<OrtModel> sessions;
    private final List<ModelInfo> models;
    private final OnnxFaceAnalyzer faceAnalyzer;
    private final OnnxFaceAnalyzer detectorOnlyAnalyzer;
    private final MiniFasPassivePad passivePad;
    private final SFaceEmbedder faceEmbedder;

    private OnnxModelSet(List<OrtModel> sessions, List<ModelInfo> models, OnnxFaceAnalyzer faceAnalyzer,
                         OnnxFaceAnalyzer detectorOnlyAnalyzer, MiniFasPassivePad passivePad,
                         SFaceEmbedder faceEmbedder) {
        this.sessions = sessions;
        this.models = models;
        this.faceAnalyzer = faceAnalyzer;
        this.detectorOnlyAnalyzer = detectorOnlyAnalyzer;
        this.passivePad = passivePad;
        this.faceEmbedder = faceEmbedder;
    }

    /**
     * Loads every component with default detector and analyser settings.
     *
     * @param registry verified model bundle
     * @param options  runtime settings
     * @return the loaded set
     * @throws EngineException if a model fails verification or cannot be loaded
     */
    public static OnnxModelSet load(ModelRegistry registry, OnnxRuntimeOptions options) throws EngineException {
        return builder(registry).runtimeOptions(options).load();
    }

    /**
     * Starts a builder.
     *
     * @param registry verified model bundle
     * @return a builder that loads every component with default settings
     */
    public static Builder builder(ModelRegistry registry) {
        return new Builder(registry);
    }

    /**
     * Returns the face analyser.
     *
     * @return the analyser
     */
    public OnnxFaceAnalyzer faceAnalyzer() {
        return faceAnalyzer;
    }

    /**
     * Returns an analyser that shares the detector but skips Face Mesh: boxes, five landmarks, five-point pose
     * and quality only. The engine can use it on frames that need no eye, mouth or gaze measures (for example
     * the passive phase) to save the Face Mesh time.
     *
     * @return the detector-only analyser
     */
    public OnnxFaceAnalyzer detectorOnlyAnalyzer() {
        return detectorOnlyAnalyzer;
    }

    /**
     * Returns the passive PAD ensemble.
     *
     * @return the ensemble
     * @throws IllegalStateException if the set was built without PAD
     */
    public MiniFasPassivePad passivePad() {
        if (passivePad == null) {
            throw new IllegalStateException("passive PAD was not loaded");
        }
        return passivePad;
    }

    /**
     * Returns the face embedder.
     *
     * @return the embedder
     * @throws IllegalStateException if the set was built without the embedder
     */
    public SFaceEmbedder faceEmbedder() {
        if (faceEmbedder == null) {
            throw new IllegalStateException("face embedder was not loaded");
        }
        return faceEmbedder;
    }

    /**
     * Returns identifier, version and SHA-256 of every loaded model, for audits and packet evidence.
     *
     * @return an unmodifiable list
     */
    public List<ModelInfo> models() {
        return models;
    }

    /** Stops the passive PAD's helper threads and releases every native session. */
    @Override
    public void close() {
        if (passivePad != null) {
            passivePad.close();
        }
        for (OrtModel m : sessions) {
            m.close();
        }
    }

    @Override
    public String toString() {
        return "OnnxModelSet" + models;
    }

    /** Builder for {@link OnnxModelSet}. */
    public static final class Builder {

        private final ModelRegistry registry;
        private OnnxRuntimeOptions runtimeOptions = OnnxRuntimeOptions.defaults();
        private YuNetFaceDetector.Options detectorOptions = YuNetFaceDetector.Options.defaults();
        private boolean mesh = true;
        private boolean pad = true;
        private boolean embedder = true;

        private Builder(ModelRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
        }

        /**
         * Sets the runtime settings.
         *
         * @param options thread counts and session customizer
         * @return this builder
         */
        public Builder runtimeOptions(OnnxRuntimeOptions options) {
            this.runtimeOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Sets the detector settings.
         *
         * @param options score, overlap and input size
         * @return this builder
         */
        public Builder detectorOptions(YuNetFaceDetector.Options options) {
            this.detectorOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Chooses whether to load Face Mesh; without it the analyser is detector-only (head turns and looks).
         *
         * @param load {@code true} to load it (default)
         * @return this builder
         */
        public Builder withMesh(boolean load) {
            this.mesh = load;
            return this;
        }

        /**
         * Chooses whether to load the MiniFASNet ensemble.
         *
         * @param load {@code true} to load it (default)
         * @return this builder
         */
        public Builder withPassivePad(boolean load) {
            this.pad = load;
            return this;
        }

        /**
         * Chooses whether to load SFace.
         *
         * @param load {@code true} to load it (default)
         * @return this builder
         */
        public Builder withEmbedder(boolean load) {
            this.embedder = load;
            return this;
        }

        /**
         * Verifies and loads the chosen models.
         *
         * @return the loaded set
         * @throws EngineException if a model fails verification or cannot be loaded; models already loaded are
         *                         released first
         */
        public OnnxModelSet load() throws EngineException {
            List<OrtModel> sessions = new ArrayList<>();
            List<ModelInfo> infos = new ArrayList<>();
            try {
                YuNetFaceDetector detector =
                        new YuNetFaceDetector(session(ModelIds.YUNET, sessions, infos), detectorOptions);
                FaceMeshLandmarker landmarker = mesh
                        ? new FaceMeshLandmarker(session(ModelIds.FACE_MESH, sessions, infos)) : null;
                MiniFasPassivePad passivePad = pad ? new MiniFasPassivePad(Arrays.asList(
                        miniFas(ModelIds.MINIFASNET_V2, 2.7, sessions, infos),
                        miniFas(ModelIds.MINIFASNET_V1SE, 4.0, sessions, infos))) : null;
                SFaceEmbedder sface = null;
                if (embedder) {
                    ModelEntry entry = registry.entry(ModelIds.SFACE);
                    sface = new SFaceEmbedder(entry.id() + "@" + entry.version(),
                            session(ModelIds.SFACE, sessions, infos));
                }
                return new OnnxModelSet(Collections.unmodifiableList(sessions), Collections.unmodifiableList(infos),
                        new OnnxFaceAnalyzer(detector, landmarker, OnnxFaceAnalyzer.Options.defaults()),
                        new OnnxFaceAnalyzer(detector, null, OnnxFaceAnalyzer.Options.defaults()), passivePad, sface);
            } catch (EngineException | RuntimeException e) {
                for (OrtModel m : sessions) {
                    m.close();
                }
                throw e;
            }
        }

        private MiniFasModel miniFas(String id, double defaultScale, List<OrtModel> sessions, List<ModelInfo> infos)
                throws EngineException {
            double scale = registry.entry(id).doubleParam("cropScale", defaultScale);
            return new MiniFasModel(id, session(id, sessions, infos), scale);
        }

        private OrtModel session(String id, List<OrtModel> sessions, List<ModelInfo> infos) throws EngineException {
            ModelEntry entry = registry.entry(id);
            OrtModel model = OrtModel.create(id, registry.load(id), runtimeOptions);
            sessions.add(model);
            infos.add(entry.toModelInfo());
            return model;
        }
    }
}
