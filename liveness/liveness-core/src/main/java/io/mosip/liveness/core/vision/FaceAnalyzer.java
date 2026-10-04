/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.vision;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;

import java.util.Set;

/**
 * Finds faces in an image and measures landmarks, head pose, eye and mouth state, gaze and quality.
 *
 * <p>Implemented by model adapters (for example YuNet plus Face Mesh in {@code liveness-onnx}). An instance may
 * be shared across sessions only if the implementation says it is thread-safe; the session calls it from its
 * single worker thread.
 */
public interface FaceAnalyzer {

    /**
     * Lists the measures this analyser provides.
     *
     * @return an unmodifiable set
     */
    Set<AnalyzerFeature> features();

    /**
     * Analyses an image.
     *
     * @param image upright image in any raw format
     * @return every face found, subject first; never {@code null}
     * @throws EngineException if inference fails
     */
    FaceObservation analyze(ImageBuffer image) throws EngineException;

    /**
     * Analyses an image, using the subject's box from the previous frame as a hint so the detector can be
     * skipped or run on a smaller region. The default ignores the hint.
     *
     * @param image upright image in any raw format
     * @param hint  the subject's box in the previous frame
     * @return every face found, subject first; never {@code null}
     * @throws EngineException if inference fails
     */
    default FaceObservation analyze(ImageBuffer image, FaceBox hint) throws EngineException {
        return analyze(image);
    }
}
