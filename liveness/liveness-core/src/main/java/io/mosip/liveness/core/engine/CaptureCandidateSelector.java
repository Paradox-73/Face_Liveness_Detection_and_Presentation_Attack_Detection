/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.DetectedFace;

/**
 * Chooses the frame a session stores: a storable face (eyes open, no smile) beats any other, then the most frontal
 * one. Session thread only.
 */
final class CaptureCandidateSelector {

    private final EngineParameters params;
    private FaceFrame frame;
    private ImageBuffer image;
    private DetectedFace face;
    private double angle;
    private boolean frontal;
    private boolean storable;

    CaptureCandidateSelector(EngineParameters params) {
        this.params = params;
    }

    /** Keeps the frame when it is better than the one kept so far. */
    void offer(FaceFrame f, ImageBuffer img, DetectedFace detected, FaceSignals s, boolean isStorable) {
        double a = s.hasPose() ? s.maxPoseAngle() : 0;
        boolean isFrontal = a <= params.get(EngineParameter.CAPTURE_FRONTAL_MAX_DEG);
        boolean better = frame == null || (isStorable && !storable)
                || (isStorable == storable && (isFrontal || (!frontal && a <= angle)));
        if (better) {
            frame = f;
            image = img;
            face = detected;
            angle = a;
            frontal = isFrontal;
            storable = isStorable;
        }
    }

    /**
     * Tells whether a passive frame is preferred for storage: eyes open (no blink caught mid-way) and no smile when
     * the analyser gives a smile probability. Without a neutral baseline the mouth ratio cannot tell a smile; no
     * action has been asked for yet in the passive phase.
     */
    boolean isStorable(FaceSignals s) {
        return (Double.isNaN(s.ear) || s.ear >= params.get(EngineParameter.EYES_OPEN_MIN_EAR))
                && (Double.isNaN(s.smileScore) || s.smileScore < params.get(EngineParameter.SMILE_SCORE_THRESHOLD));
    }

    boolean isPresent() {
        return frame != null;
    }

    FaceFrame frame() {
        return frame;
    }

    ImageBuffer image() {
        return image;
    }

    DetectedFace face() {
        return face;
    }

    /** Forgets the kept frame (the capture pose starts a new choice); the frame is not wiped. */
    void reset() {
        frame = null;
        image = null;
        face = null;
    }

    /** Wipes and forgets the kept frame (the session is closed). */
    void wipe() {
        if (frame != null) {
            frame.wipe();
            image.wipe();
        }
        reset();
    }
}
