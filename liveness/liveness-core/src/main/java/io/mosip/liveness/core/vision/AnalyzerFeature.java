/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.vision;

/**
 * Measures a {@link FaceAnalyzer} can provide. The session removes challenges from the pool whose measures the
 * analyser lacks (for example the detector-only fallback supports head turns and looks, not blink or smile).
 */
public enum AnalyzerFeature {

    /** Five landmarks per face ({@link FaceBox#landmarks()}). */
    LANDMARKS_5,
    /** Dense landmarks ({@link DetectedFace#mesh()}). */
    MESH,
    /** Yaw, pitch and roll ({@link DetectedFace#pose()}); needed for TURN and LOOK challenges. */
    HEAD_POSE,
    /** Eye aspect ratio per eye; needed for BLINK. */
    EYE_OPENNESS,
    /** Mouth width ratio or smile score; needed for SMILE. */
    MOUTH,
    /** Iris-based gaze direction; needed for GAZE challenges. */
    GAZE,
    /** Inter-eye distance, sharpness and brightness ({@link DetectedFace#quality()}). */
    QUALITY
}
