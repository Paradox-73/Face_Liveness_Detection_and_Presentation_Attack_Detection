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
 * Passive presentation attack detection on a single image (for example the MiniFASNet V2 + V1SE ensemble in
 * {@code liveness-onnx}).
 *
 * <p>The implementation crops around the box itself (the MiniFASNet crops use scales 2.7 and 4.0), so it
 * receives the full image rather than a crop.
 */
public interface PassivePad {

    /**
     * Scores one face.
     *
     * @param image upright image in any raw format
     * @param face  the face to score, as found by the {@link FaceAnalyzer}
     * @return the live probability
     * @throws EngineException if inference fails
     */
    PadScore score(ImageBuffer image, FaceBox face) throws EngineException;
}
