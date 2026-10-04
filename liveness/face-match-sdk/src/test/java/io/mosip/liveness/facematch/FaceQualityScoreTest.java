/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.FaceQuality;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The documented quality formula: confidence x size x exposure x single face. */
class FaceQualityScoreTest {

    @Test
    void aGoodFaceScoresItsConfidence() {
        FaceQualityScore q = FaceQualityScore.of(one(face(0.93, 80, 120)));
        assertEquals(93, q.score());
        assertEquals(1, q.faces());
        assertTrue(q.flags().isEmpty());
        assertEquals(80, q.interEyeDistance(), 1e-9);
        assertEquals(120, q.brightness(), 1e-9);
    }

    @Test
    void noFaceScoresZero() {
        FaceQualityScore q = FaceQualityScore.of(FaceObservation.none(640, 480));
        assertEquals(0, q.score());
        assertEquals(Collections.singletonList("NO_FACE"), q.flags());
    }

    @Test
    void smallDarkBrightAndCrowdedImagesArePenalised() {
        assertEquals(50, FaceQualityScore.of(one(face(1.0, 30, 120))).score(), "half the ISO 60 px");
        assertTrue(FaceQualityScore.of(one(face(1.0, 30, 120))).flags().contains("FACE_TOO_SMALL"));
        FaceQualityScore dark = FaceQualityScore.of(one(face(1.0, 60, 0)));
        assertEquals(50, dark.score());
        assertEquals(Collections.singletonList("TOO_DARK"), dark.flags());
        FaceQualityScore bright = FaceQualityScore.of(one(face(1.0, 60, 255)));
        assertEquals(50, bright.score());
        assertEquals(Collections.singletonList("TOO_BRIGHT"), bright.flags());
        assertEquals(75, FaceQualityScore.of(one(face(1.0, 60, 25))).score());

        FaceObservation two = FaceObservation.of(640, 480, Arrays.asList(face(0.9, 70, 100), face(0.8, 40, 100)));
        FaceQualityScore crowded = FaceQualityScore.of(two);
        assertEquals(45, crowded.score());
        assertEquals(2, crowded.faces());
        assertTrue(crowded.flags().contains("MULTIPLE_FACES"));
        assertTrue(crowded.toString().contains("MULTIPLE_FACES"));
    }

    private static FaceObservation one(DetectedFace f) {
        return FaceObservation.of(640, 480, Collections.singletonList(f));
    }

    private static DetectedFace face(double confidence, double ied, double brightness) {
        return DetectedFace.builder(FaceBox.of(200, 100, 200, 240, confidence))
                .quality(FaceQuality.of(ied, 100, brightness)).build();
    }
}
