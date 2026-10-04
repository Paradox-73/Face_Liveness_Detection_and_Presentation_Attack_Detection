/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceAnalyzer;
import io.mosip.liveness.core.vision.FaceEmbedder;
import io.mosip.liveness.core.vision.FaceEmbedding;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.facematch.FaceMatchException.Problem;
import io.mosip.liveness.facematch.iso.FaceIsoFormatException;
import io.mosip.liveness.facematch.iso.FaceIsoRecord;
import io.mosip.liveness.onnx.OnnxModelSet;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Platform-neutral face matching behind {@link SFaceBioApi}: turns a BDB (ISO/IEC 19794-5 record, plain JPEG /
 * JPEG 2000 / PNG image, or a {@link FaceTemplate}) into an SFace embedding and a quality score, and compares
 * embeddings. It has no MOSIP types, so it can be tested and reused on its own.
 *
 * <p>Models: YuNet (face box and five landmarks) and SFace (embedding) from the signed liveness model bundle; Face
 * Mesh and the PAD models are not loaded. Similarity is {@code round(100 * max(0, cosine))}, the same scale the
 * liveness engine uses for {@code binding.min_similarity}.
 *
 * <p>Thread-safe: the model adapters serialise their calls. Decoded pixels are wiped after use.
 */
public final class SFaceMatcher implements AutoCloseable {

    private final FaceAnalyzer analyzer;
    private final FaceEmbedder embedder;
    private final String modelId;
    private final FaceImageDecoder decoder;
    private final List<ModelInfo> models;
    private final AutoCloseable resources;

    /**
     * Creates a matcher from loaded adapters.
     *
     * @param analyzer  face detector with five landmarks
     * @param embedder  face embedder
     * @param modelId   identifier the embedder writes into its embeddings
     * @param decoder   platform image decoder
     * @param models    loaded models, for {@link #models()}
     * @param resources released by {@link #close()}, or {@code null}
     */
    public SFaceMatcher(FaceAnalyzer analyzer, FaceEmbedder embedder, String modelId, FaceImageDecoder decoder,
                        List<ModelInfo> models, AutoCloseable resources) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.models = Objects.requireNonNull(models, "models");
        this.resources = resources;
    }

    /**
     * Verifies the model bundle and loads YuNet and SFace.
     *
     * @param source  bundle files (the class path on the desktop, a directory on Android)
     * @param decoder platform image decoder
     * @return the matcher; close it to release the native sessions
     * @throws EngineException if the bundle fails verification or a model cannot be loaded
     */
    public static SFaceMatcher load(ModelSource source, FaceImageDecoder decoder) throws EngineException {
        ModelRegistry registry = ModelRegistry.open(source);
        OnnxModelSet set = OnnxModelSet.builder(registry).runtimeOptions(OnnxRuntimeOptions.defaults())
                .withMesh(false).withPassivePad(false).withEmbedder(true).load();
        return new SFaceMatcher(set.faceAnalyzer(), set.faceEmbedder(), set.faceEmbedder().modelId(), decoder,
                set.models(), set);
    }

    /**
     * Returns the identifier of the embedding model; templates of another model are not compared.
     *
     * @return for example {@code "sface@2021dec-fp16w"}
     */
    public String modelId() {
        return modelId;
    }

    /**
     * Returns the loaded models with versions and SHA-256.
     *
     * @return the models
     */
    public List<ModelInfo> models() {
        return models;
    }

    /**
     * Analyses a BDB for matching: reads a template directly, or decodes the image, finds the subject's face and
     * computes its embedding.
     *
     * @param bdb BDB bytes
     * @return the embedding, plus the quality for an image
     * @throws FaceMatchException if the BDB cannot be read, has no face, is a template of another model, or the
     *                            model fails
     */
    public FaceSample analyse(byte[] bdb) throws FaceMatchException {
        if (FaceTemplate.isTemplate(bdb)) {
            return new FaceSample(readTemplate(bdb), null);
        }
        ImageBuffer image = decode(bdb);
        try {
            FaceObservation observation = observe(image);
            FaceQualityScore quality = FaceQualityScore.of(observation);
            Optional<DetectedFace> subject = observation.primary();
            if (!subject.isPresent() || !subject.get().box().hasLandmarks()) {
                throw new FaceMatchException(Problem.NO_FACE, "no face found in the image", null);
            }
            return new FaceSample(embedder.embed(image, subject.get().box()), quality);
        } catch (EngineException e) {
            throw new FaceMatchException(Problem.ENGINE, "face embedding failed: " + e.getErrorCode(), e);
        } finally {
            image.wipe();
        }
    }

    /**
     * Measures the quality of a face image without computing its embedding.
     *
     * @param bdb BDB bytes (face record or image)
     * @return the quality; score 0 when no face is found
     * @throws FaceMatchException if the BDB cannot be read (a template has no image) or the model fails
     */
    public FaceQualityScore quality(byte[] bdb) throws FaceMatchException {
        if (FaceTemplate.isTemplate(bdb)) {
            throw new FaceMatchException(Problem.UNREADABLE, "a template has no image to assess", null);
        }
        ImageBuffer image = decode(bdb);
        try {
            return FaceQualityScore.of(observe(image));
        } finally {
            image.wipe();
        }
    }

    /**
     * Compares two embeddings.
     *
     * @param a first embedding
     * @param b second embedding of the same model
     * @return {@code round(100 * max(0, cosine))}, 0-100
     * @throws IllegalArgumentException if the embeddings come from different models
     */
    public static int similarity(FaceEmbedding a, FaceEmbedding b) {
        return (int) Math.round(100 * Math.max(0, a.cosineSimilarity(b)));
    }

    private FaceEmbedding readTemplate(byte[] bdb) throws FaceMatchException {
        FaceEmbedding embedding;
        try {
            embedding = FaceTemplate.decode(bdb);
        } catch (IllegalArgumentException e) {
            throw new FaceMatchException(Problem.UNREADABLE, "the face template is invalid", e);
        }
        if (!embedding.modelId().equals(modelId)) {
            throw new FaceMatchException(Problem.OTHER_MODEL, "the face template was made by " + embedding.modelId()
                    + ", not " + modelId + "; enrol again", null);
        }
        return embedding;
    }

    private ImageBuffer decode(byte[] bdb) throws FaceMatchException {
        if (bdb == null || bdb.length == 0) {
            throw new FaceMatchException(Problem.UNREADABLE, "the BDB is empty", null);
        }
        try {
            byte[] encoded = FaceIsoRecord.isFaceRecord(bdb) ? FaceIsoRecord.parse(bdb).image() : bdb;
            return decoder.decode(encoded);
        } catch (FaceIsoFormatException | ImageDecodeException e) {
            throw new FaceMatchException(Problem.UNREADABLE, "the face image cannot be read: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new FaceMatchException(Problem.UNREADABLE, "the face image cannot be decoded", e);
        }
    }

    private FaceObservation observe(ImageBuffer image) throws FaceMatchException {
        try {
            return analyzer.analyze(image);
        } catch (EngineException e) {
            throw new FaceMatchException(Problem.ENGINE, "face detection failed: " + e.getErrorCode(), e);
        }
    }

    /** Releases the native model sessions. */
    @Override
    public void close() {
        if (resources != null) {
            try {
                resources.close();
            } catch (Exception ignored) {
                // Best effort at shutdown; nothing useful can be done.
            }
        }
    }

    @Override
    public String toString() {
        return "SFaceMatcher[" + modelId + ", decoder=" + decoder + "]";
    }
}
