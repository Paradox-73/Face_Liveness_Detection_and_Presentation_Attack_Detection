/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.mesh;

import io.mosip.liveness.core.vision.FaceMesh;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FaceMeasuresTest {

    /**
     * Builds a synthetic face in a non-mirrored image: subject's right eye on the image left (x 90-130), left eye on
     * the image right (x 170-210), eyes 20 px wide at y 100, lid opening {@code open} px, irises shifted by
     * {@code gazeX, gazeY} pixels (y up), mouth corners at x 110 and 190.
     */
    static FaceMesh face(double open, double gazeX, double gazeY, int points) {
        float[] xyz = new float[points * 3];
        eye(xyz, FaceMeasures.RIGHT_EYE_EAR, 90, 130, open);
        eye(xyz, FaceMeasures.LEFT_EYE_EAR, 170, 210, open);
        set(xyz, 159, 110, 100 - open / 2);
        set(xyz, 145, 110, 100 + open / 2);
        set(xyz, 386, 190, 100 - open / 2);
        set(xyz, 374, 190, 100 + open / 2);
        if (points == FaceMesh.POINTS_WITH_IRIS) {
            set(xyz, FaceMeasures.IRIS_RIGHT, 110 + gazeX, 100 - gazeY);
            set(xyz, FaceMeasures.IRIS_LEFT, 190 + gazeX, 100 - gazeY);
        }
        set(xyz, FaceMeasures.MOUTH_RIGHT, 110, 160);
        set(xyz, FaceMeasures.MOUTH_LEFT, 190, 160);
        return FaceMesh.of(xyz);
    }

    /** Six EAR points: first corner, two upper lid, second corner, two lower lid (as in {@link FaceMeasures}). */
    private static void eye(float[] xyz, int[] idx, double x0, double x1, double open) {
        double w = x1 - x0;
        set(xyz, idx[0], x0, 100);
        set(xyz, idx[1], x0 + w / 3, 100 - open / 2);
        set(xyz, idx[2], x0 + 2 * w / 3, 100 - open / 2);
        set(xyz, idx[3], x1, 100);
        set(xyz, idx[4], x0 + 2 * w / 3, 100 + open / 2);
        set(xyz, idx[5], x0 + w / 3, 100 + open / 2);
    }

    private static void set(float[] xyz, int index, double x, double y) {
        xyz[3 * index] = (float) x;
        xyz[3 * index + 1] = (float) y;
    }

    @Test
    void eyeAspectRatioFollowsTheLidOpening() {
        assertEquals(12.0 / 40, FaceMeasures.rightEyeAspectRatio(face(12, 0, 0, 478)), 1e-6);
        assertEquals(12.0 / 40, FaceMeasures.leftEyeAspectRatio(face(12, 0, 0, 478)), 1e-6);
        assertEquals(2.0 / 40, FaceMeasures.leftEyeAspectRatio(face(2, 0, 0, 478)), 1e-6);
    }

    @Test
    void interEyeDistanceAndMouthRatio() {
        FaceMesh withIris = face(12, 0, 0, 478);
        assertEquals(80, FaceMeasures.interEyeDistance(withIris), 1e-6);
        assertEquals(1.0, FaceMeasures.mouthWidthRatio(withIris), 1e-6);
        FaceMesh noIris = face(12, 0, 0, 468);
        assertEquals(80, FaceMeasures.interEyeDistance(noIris), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> FaceMeasures.gaze(noIris));
    }

    @Test
    void gazeSignsFollowTheSubject() {
        double[] centre = FaceMeasures.gaze(face(12, 0, 0, 478));
        assertEquals(0, centre[0], 1e-6);
        assertEquals(0, centre[1], 1e-6);
        // Irises towards the image right = the subject's own left: positive; half the half-width gives 0.5.
        assertEquals(0.5, FaceMeasures.gaze(face(12, 10, 0, 478))[0], 1e-6);
        assertEquals(-0.5, FaceMeasures.gaze(face(12, -10, 0, 478))[0], 1e-6);
        // Irises up by half the half-opening: +0.5.
        assertEquals(0.5, FaceMeasures.gaze(face(12, 0, 3, 478))[1], 1e-6);
        // Clamped to -1..1.
        assertEquals(1, FaceMeasures.gaze(face(12, 100, 0, 478))[0], 1e-6);
    }
}
