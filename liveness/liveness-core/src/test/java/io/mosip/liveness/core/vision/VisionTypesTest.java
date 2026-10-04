/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.vision;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionTypesTest {

    @Test
    void faceBoxOverlap() {
        FaceBox a = FaceBox.of(0, 0, 10, 10, 0.9);
        assertEquals(1.0, a.iou(FaceBox.of(0, 0, 10, 10, 0.5)), 1e-9);
        assertEquals(0.0, a.iou(FaceBox.of(20, 20, 5, 5, 0.5)), 1e-9);
        assertEquals(25.0 / 175.0, a.iou(FaceBox.of(5, 5, 10, 10, 0.5)), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> FaceBox.of(0, 0, 0, 10, 0.5));
        assertThrows(IllegalArgumentException.class, () -> FaceBox.of(0, 0, 10, 10, 1.5));
    }

    @Test
    void faceBoxLandmarksComeInFives() {
        FaceBox box = FaceBox.of(0, 0, 100, 100, 0.9);
        assertFalse(box.hasLandmarks());
        assertFalse(box.landmark(Landmark5.NOSE_TIP).isPresent());
        FaceBox withLm = box.withLandmarks(Arrays.asList(FacePoint.of(30, 40), FacePoint.of(70, 40), FacePoint.of(50, 60),
                FacePoint.of(35, 80), FacePoint.of(65, 80)));
        assertEquals(FacePoint.of(50, 60), withLm.landmark(Landmark5.NOSE_TIP).get());
        assertEquals(40.0, withLm.landmark(Landmark5.RIGHT_EYE).get().distanceTo(withLm.landmark(Landmark5.LEFT_EYE).get()),
                1e-9);
        assertThrows(IllegalArgumentException.class, () -> box.withLandmarks(Collections.singletonList(FacePoint.of(1, 1))));
    }

    @Test
    void observationPutsTheSubjectFirst() {
        DetectedFace subject = DetectedFace.builder(FaceBox.of(10, 10, 80, 80, 0.95)).pose(HeadPose.of(20, 0, 0))
                .eyeAspectRatios(0.3, 0.31).mouthWidthRatio(0.9).quality(FaceQuality.of(62, 120, 128)).build();
        DetectedFace other = DetectedFace.builder(FaceBox.of(0, 0, 10, 10, 0.6)).build();
        FaceObservation obs = FaceObservation.of(640, 480, Arrays.asList(subject, other));
        assertEquals(2, obs.faceCount());
        assertEquals(subject, obs.primary().get());
        assertEquals(20.0, obs.primary().get().pose().get().yawDeg(), 1e-9);
        assertFalse(other.leftEyeAspectRatio().isPresent());
        assertFalse(FaceObservation.none(640, 480).primary().isPresent());
        assertThrows(IllegalArgumentException.class, () -> DetectedFace.builder(FaceBox.of(0, 0, 1, 1, 1)).gaze(1.5, 0));
    }

    @Test
    void headPoseLimits() {
        assertTrue(HeadPose.of(4, -5, 3).isWithin(5));
        assertFalse(HeadPose.of(6, 0, 0).isWithin(5));
        assertTrue(HeadPose.FRONTAL.isWithin(0));
        assertThrows(IllegalArgumentException.class, () -> HeadPose.of(Double.NaN, 0, 0));
    }

    @Test
    void meshHas468Or478Points() {
        FaceMesh mesh = FaceMesh.of(new float[FaceMesh.POINTS_WITH_IRIS * 3]);
        assertTrue(mesh.hasIris());
        assertEquals(478, mesh.pointCount());
        assertFalse(FaceMesh.of(new float[FaceMesh.POINTS_WITHOUT_IRIS * 3]).hasIris());
        assertThrows(IllegalArgumentException.class, () -> FaceMesh.of(new float[10]));
    }

    @Test
    void padScoreRoundsToAnIntegerScore() {
        Map<String, Double> parts = new LinkedHashMap<>();
        parts.put("minifasnet_v2", 0.70);
        parts.put("minifasnet_v1se", 0.74);
        PadScore s = PadScore.of(0.72, parts);
        assertEquals(72, s.score100());
        assertEquals(0.74, s.modelProbabilities().get("minifasnet_v1se"), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> PadScore.of(1.01));
    }

    @Test
    void embeddingsCompareByCosineAndNeverPrintValues() {
        FaceEmbedding a = FaceEmbedding.of("sface", new float[]{1, 0, 0});
        FaceEmbedding b = FaceEmbedding.of("sface", new float[]{0.6f, 0.8f, 0});
        assertEquals(0.6, a.cosineSimilarity(b), 1e-6);
        assertEquals(1.0, a.cosineSimilarity(a), 1e-9);
        assertEquals("FaceEmbedding[model=sface, dimension=3]", a.toString());
        assertThrows(IllegalArgumentException.class, () -> a.cosineSimilarity(FaceEmbedding.of("other", new float[]{1, 0, 0})));
        float[] v = {1, 2};
        FaceEmbedding c = FaceEmbedding.of("sface", v);
        v[0] = 9;
        assertEquals(1.0f, c.vector()[0]);
    }
}
