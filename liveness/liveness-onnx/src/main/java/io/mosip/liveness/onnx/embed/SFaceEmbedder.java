/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.embed;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceEmbedder;
import io.mosip.liveness.core.vision.FaceEmbedding;
import io.mosip.liveness.onnx.image.ImageOps;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.image.TensorFormat;
import io.mosip.liveness.onnx.runtime.OrtModel;

import java.util.Map;
import java.util.Objects;

/**
 * Face embeddings with SFace (OpenCV Zoo {@code face_recognition_sface_2021dec}): the face is aligned on its five
 * landmarks to 112x112 ({@link FaceAligner}), fed as RGB with raw 0-255 values (OpenCV
 * {@code FaceRecognizerSF.feature}: {@code blobFromImage(img, 1, (112, 112), 0, swapRB=true)}), and the 128
 * outputs are scaled to unit length.
 *
 * <p>Compare embeddings with {@link FaceEmbedding#cosineSimilarity(FaceEmbedding)} or {@link #cosine}. The OpenCV
 * Zoo SFace sample treats a cosine of {@value #OPENCV_SAME_PERSON_COSINE} or more as the same person; the
 * engine's binding threshold is calibrated separately. Embeddings are biometric data: never log them.
 *
 * <p>Calls are serialised; one instance may be shared.
 */
public final class SFaceEmbedder implements FaceEmbedder {

    /** Length of the embedding. */
    public static final int DIMENSION = 128;
    /** Cosine similarity at or above which the OpenCV Zoo SFace sample treats two faces as the same person. */
    public static final double OPENCV_SAME_PERSON_COSINE = 0.363;

    private final String modelId;
    private final OrtModel model;
    private final float[] tensor = new float[3 * FaceAligner.SIZE * FaceAligner.SIZE];

    /**
     * Creates the embedder.
     *
     * @param modelId identifier recorded in every embedding (for example {@code "sface@2021dec-fp16w"}), so
     *                embeddings of different models are never compared
     * @param model   the loaded SFace model (input 1x3x112x112, output 1x128)
     */
    public SFaceEmbedder(String modelId, OrtModel model) {
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * Returns the model identifier recorded in every embedding this embedder makes.
     *
     * @return for example {@code "sface@2021dec-fp16w"}
     */
    public String modelId() {
        return modelId;
    }

    @Override
    public FaceEmbedding embed(ImageBuffer image, FaceBox face) throws EngineException {
        return embed(Rgb8Image.of(image), face);
    }

    /**
     * Computes the embedding of one face on an already converted image.
     *
     * @param image the image
     * @param face  a box with five landmarks
     * @return the unit-length embedding
     * @throws EngineException          if inference fails
     * @throws IllegalArgumentException if the box has no landmarks
     */
    public synchronized FaceEmbedding embed(Rgb8Image image, FaceBox face) throws EngineException {
        ImageOps.warpAffineToTensor(image, FaceAligner.transform(face), FaceAligner.SIZE, FaceAligner.SIZE,
                TensorFormat.RGB_RAW, tensor);
        Map<String, float[]> out = model.run(tensor, new long[] {1, 3, FaceAligner.SIZE, FaceAligner.SIZE});
        float[] v = out.get(model.outputNames().get(0));
        if (v == null || v.length != DIMENSION) {
            throw new EngineException(EngineErrorCode.INFERENCE, "SFace output has an unexpected shape");
        }
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        if (!(norm > 0) || Double.isInfinite(norm)) {
            throw new EngineException(EngineErrorCode.INFERENCE, "SFace returned an invalid embedding");
        }
        double inv = 1.0 / Math.sqrt(norm);
        float[] unit = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            unit[i] = (float) (v[i] * inv);
        }
        return FaceEmbedding.of(modelId, unit);
    }

    /**
     * Cosine similarity of two vectors.
     *
     * @param a first vector
     * @param b second vector, same length
     * @return -1 to 1; 0 if either vector is all zeros
     * @throws IllegalArgumentException if the lengths differ
     */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vectors differ in length");
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return Math.max(-1, Math.min(1, dot / Math.sqrt(na * nb)));
    }

    @Override
    public String toString() {
        return "SFaceEmbedder[" + modelId + "]";
    }
}
