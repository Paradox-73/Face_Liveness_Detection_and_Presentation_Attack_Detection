/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.vision;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;

/**
 * Computes a face feature vector (for example SFace in {@code liveness-onnx}), used for the same-person check
 * during a session and for stream-to-capture binding.
 */
public interface FaceEmbedder {

    /**
     * Computes the embedding of one face.
     *
     * @param image upright image in any raw format
     * @param face  the face, with its five landmarks (the embedder aligns the face on them)
     * @return the embedding
     * @throws EngineException          if inference fails
     * @throws IllegalArgumentException if the box has no landmarks
     */
    FaceEmbedding embed(ImageBuffer image, FaceBox face) throws EngineException;
}
