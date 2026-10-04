/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import io.mosip.liveness.core.vision.FaceEmbedding;

import java.util.Objects;
import java.util.Optional;

/**
 * One analysed BDB: the subject's embedding and, when the BDB was an image, its quality. Biometric data:
 * {@link #toString()} never prints the embedding.
 */
public final class FaceSample {

    private final FaceEmbedding embedding;
    private final FaceQualityScore quality;

    FaceSample(FaceEmbedding embedding, FaceQualityScore quality) {
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.quality = quality;
    }

    /**
     * Returns the subject's embedding.
     *
     * @return the embedding
     */
    public FaceEmbedding embedding() {
        return embedding;
    }

    /**
     * Returns the image quality.
     *
     * @return the quality, or empty when the sample came from a stored template
     */
    public Optional<FaceQualityScore> quality() {
        return Optional.ofNullable(quality);
    }

    /**
     * Tells whether the sample came from a stored template rather than an image.
     *
     * @return {@code true} for a template
     */
    public boolean fromTemplate() {
        return quality == null;
    }

    @Override
    public String toString() {
        return "FaceSample[" + embedding.modelId() + (fromTemplate() ? ", template" : ", " + quality) + "]";
    }
}
