/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.detect;

import io.mosip.liveness.core.vision.FaceBox;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YuNetDetectorTest {

    private static YuNetFaceDetector.Candidate candidate(float x, float score) {
        return new YuNetFaceDetector.Candidate(x, 0, 10, 10, score, new float[10]);
    }

    @Test
    void nonMaximumSuppressionKeepsTheBestOfOverlappingBoxes() {
        YuNetFaceDetector.Candidate best = candidate(0, 0.9f);
        YuNetFaceDetector.Candidate overlapping = candidate(1, 0.8f);
        YuNetFaceDetector.Candidate separate = candidate(50, 0.7f);
        List<YuNetFaceDetector.Candidate> kept = YuNetFaceDetector.nonMaximumSuppression(
                Arrays.asList(best, overlapping, separate), 0.3, 10);
        assertEquals(2, kept.size());
        assertSame(best, kept.get(0));
        assertSame(separate, kept.get(1));
        assertEquals(1, YuNetFaceDetector.nonMaximumSuppression(Arrays.asList(best, separate), 0.3, 1).size());
    }

    @Test
    void candidatesScaleBackToImagePixels() {
        float[] lm = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        FaceBox box = new YuNetFaceDetector.Candidate(10, 20, 30, 40, 0.95f, lm).toFaceBox(2, 0.5);
        assertEquals(20, box.x(), 1e-6);
        assertEquals(10, box.y(), 1e-6);
        assertEquals(60, box.width(), 1e-6);
        assertEquals(20, box.height(), 1e-6);
        assertEquals(18, box.landmarks().get(4).x(), 1e-6);
        assertEquals(5, box.landmarks().get(4).y(), 1e-6);
        assertEquals(0.82, candidate(0, 1).iou(candidate(1, 1)), 0.01);
    }

    @Test
    void optionsAreValidated() {
        YuNetFaceDetector.Options d = YuNetFaceDetector.Options.defaults();
        assertEquals(0.7, d.scoreThreshold());
        assertEquals(0.3, d.nmsThreshold());
        assertEquals(320, d.maxInputSide());
        assertEquals(10, d.maxFaces());
        assertThrows(IllegalArgumentException.class, () -> YuNetFaceDetector.Options.of(1.5, 0.3, 320, 1));
        assertThrows(IllegalArgumentException.class, () -> YuNetFaceDetector.Options.of(0.5, 0.3, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> YuNetFaceDetector.Options.of(0.5, 0.3, 320, 0));
    }
}
