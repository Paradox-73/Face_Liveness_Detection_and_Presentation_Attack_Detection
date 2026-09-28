package io.mosip.liveness.core.vision;

import java.util.Objects;

/**
 * A face feature vector (for example 128 values from SFace), used to check that the same person stays in front
 * of the camera and that the signed capture shows the person who passed liveness.
 *
 * <p>Embeddings are biometric data: they never go to logs, audits or the diagnostic log, and
 * {@link #toString()} does not print them. The vector is copied on creation and on {@link #vector()}.
 */
public final class FaceEmbedding {

    private final String modelId;
    private final float[] vector;

    private FaceEmbedding(String modelId, float[] vector) {
        this.modelId = modelId;
        this.vector = vector;
    }

    /**
     * Creates an embedding.
     *
     * @param modelId identifier of the model that produced it; embeddings of different models are not comparable
     * @param vector  feature values; copied; not empty, all finite
     * @return the embedding
     * @throws IllegalArgumentException if the vector is empty or holds a value that is not finite
     */
    public static FaceEmbedding of(String modelId, float[] vector) {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(vector, "vector");
        if (vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        for (float v : vector) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                throw new IllegalArgumentException("vector values must be finite");
            }
        }
        return new FaceEmbedding(modelId, vector.clone());
    }

    /**
     * Returns the identifier of the model that produced the embedding.
     *
     * @return the model identifier
     */
    public String modelId() {
        return modelId;
    }

    /**
     * Returns the number of values.
     *
     * @return vector length
     */
    public int dimension() {
        return vector.length;
    }

    /**
     * Returns a copy of the feature values.
     *
     * @return a new array
     */
    public float[] vector() {
        return vector.clone();
    }

    /**
     * Returns the cosine similarity with another embedding of the same model.
     *
     * @param other embedding to compare with
     * @return value -1 to 1; 1 means the same direction; 0 if either vector is all zeros
     * @throws IllegalArgumentException if the models or dimensions differ
     */
    public double cosineSimilarity(FaceEmbedding other) {
        if (!modelId.equals(other.modelId) || vector.length != other.vector.length) {
            throw new IllegalArgumentException("embeddings from different models or dimensions are not comparable");
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < vector.length; i++) {
            dot += (double) vector[i] * other.vector[i];
            normA += (double) vector[i] * vector[i];
            normB += (double) other.vector[i] * other.vector[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return Math.max(-1, Math.min(1, dot / (Math.sqrt(normA) * Math.sqrt(normB))));
    }

    @Override
    public String toString() {
        return "FaceEmbedding[model=" + modelId + ", dimension=" + vector.length + "]";
    }
}
