package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceEmbedder;
import io.mosip.liveness.core.vision.FaceEmbedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the session's reference face and checks that the same person stays in front of the camera (design
 * sections 4.8 "same person" and 4.10 step 5).
 *
 * <p>The reference is the mean of the unit-length embeddings of a few neutral passive frames. Similarity is
 * reported on a 0-100 scale as {@code round(100 * max(0, cosine))} and compared with
 * {@code binding.min_similarity}. Without an embedder (or without landmarks, which the embedder needs for
 * alignment) the checks are skipped and similarity is absent.
 */
final class IdentityTracker {

    private final FaceEmbedder embedder;
    private final int referenceFrames;
    private final List<float[]> vectors = new ArrayList<>();
    private FaceEmbedding reference;
    private int lastSimilarity = -1;

    IdentityTracker(FaceEmbedder embedder, int referenceFrames) {
        this.embedder = embedder;
        this.referenceFrames = referenceFrames;
    }

    boolean isEnabled() {
        return embedder != null;
    }

    boolean needsReference() {
        return embedder != null && vectors.size() < referenceFrames;
    }

    boolean hasReference() {
        return reference != null;
    }

    /**
     * Adds a neutral frame to the reference.
     *
     * @param image the frame
     * @param box   the subject's box with landmarks
     * @throws EngineException if the embedder fails
     */
    void addReference(ImageBuffer image, FaceBox box) throws EngineException {
        if (!needsReference() || !box.hasLandmarks()) {
            return;
        }
        FaceEmbedding e = embedder.embed(image, box);
        vectors.add(unit(e.vector()));
        float[] mean = new float[e.dimension()];
        for (float[] v : vectors) {
            for (int i = 0; i < mean.length; i++) {
                mean[i] += v[i] / vectors.size();
            }
        }
        reference = FaceEmbedding.of(e.modelId(), mean);
    }

    /**
     * Compares a face with the reference.
     *
     * @param image the image
     * @param box   the face box with landmarks
     * @return similarity 0-100, or -1 when there is no reference or no landmarks
     * @throws EngineException if the embedder fails
     */
    int similarity(ImageBuffer image, FaceBox box) throws EngineException {
        if (reference == null || !box.hasLandmarks()) {
            return -1;
        }
        FaceEmbedding e = embedder.embed(image, box);
        lastSimilarity = toScore(reference.cosineSimilarity(e));
        return lastSimilarity;
    }

    /**
     * Returns the last similarity computed.
     *
     * @return 0-100, or -1 if none
     */
    int lastSimilarity() {
        return lastSimilarity;
    }

    static int toScore(double cosine) {
        return (int) Math.round(100 * Math.max(0, Math.min(1, cosine)));
    }

    private static float[] unit(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }
}
