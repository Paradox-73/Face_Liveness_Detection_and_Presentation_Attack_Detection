package io.mosip.liveness.onnx.mesh;

import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceMesh;
import io.mosip.liveness.core.vision.FacePoint;
import io.mosip.liveness.core.vision.HeadPose;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pose estimation on synthetic faces: the reference face is rotated by a known pose, projected into a
 * non-mirrored image (x right, y down, depth growing away from the camera) and the estimate must return the pose.
 */
class HeadPoseEstimatorTest {

    private static final double[][] POSES = {
        {0, 0, 0}, {25, 0, 0}, {-25, 0, 0}, {0, 20, 0}, {0, -20, 0}, {0, 0, 15}, {0, 0, -15}, {18, -12, 7}, {-30, 15, -10},
    };

    @Test
    void meshFitRecoversKnownPoses() {
        for (double[] p : POSES) {
            HeadPose pose = HeadPoseEstimator.fromMesh(syntheticMesh(p[0], p[1], p[2]));
            assertEquals(p[0], pose.yawDeg(), 0.01, "yaw of " + java.util.Arrays.toString(p));
            assertEquals(p[1], pose.pitchDeg(), 0.01, "pitch of " + java.util.Arrays.toString(p));
            assertEquals(p[2], pose.rollDeg(), 0.01, "roll of " + java.util.Arrays.toString(p));
        }
    }

    @Test
    void fivePointFitRecoversKnownPosesApproximately() {
        for (double[] p : POSES) {
            HeadPose pose = HeadPoseEstimator.fromLandmarks5(syntheticBox(p[0], p[1], p[2]));
            assertEquals(p[0], pose.yawDeg(), 1.0, "yaw of " + java.util.Arrays.toString(p));
            assertEquals(p[1], pose.pitchDeg(), 1.0, "pitch of " + java.util.Arrays.toString(p));
            assertEquals(p[2], pose.rollDeg(), 1.0, "roll of " + java.util.Arrays.toString(p));
        }
    }

    @Test
    void signConventionsFollowTheSubject() {
        // Turning to the subject's own left moves the nose towards the image right in a non-mirrored image.
        FaceMesh left = syntheticMesh(20, 0, 0);
        FaceMesh frontal = syntheticMesh(0, 0, 0);
        assertTrue(left.x(1) - (left.x(33) + left.x(263)) / 2 > frontal.x(1) - (frontal.x(33) + frontal.x(263)) / 2);
        assertTrue(HeadPoseEstimator.fromMesh(left).yawDeg() > 0);
        // Looking up moves the nose up (smaller image y).
        FaceMesh up = syntheticMesh(0, 20, 0);
        assertTrue(up.y(1) - up.y(168) < frontal.y(1) - frontal.y(168));
        assertTrue(HeadPoseEstimator.fromMesh(up).pitchDeg() > 0);
        // Tilting to the subject's left shoulder lowers the subject's left eye (image right).
        FaceMesh tilt = syntheticMesh(0, 0, 10);
        assertTrue(tilt.y(263) > tilt.y(33));
        assertTrue(HeadPoseEstimator.fromMesh(tilt).rollDeg() > 0);
    }

    @Test
    void rotationRoundTrip() {
        HeadPose pose = HeadPoseEstimator.toPose(HeadPoseEstimator.toRotation(33, -21, 12));
        assertEquals(33, pose.yawDeg(), 1e-9);
        assertEquals(-21, pose.pitchDeg(), 1e-9);
        assertEquals(12, pose.rollDeg(), 1e-9);
    }

    @Test
    void largestEigenvectorOfASymmetricMatrix() {
        double[][] m = {{2, 1, 0, 0}, {1, 2, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, -1}};
        double[] v = SymmetricEigen.largestEigenvector(m);
        assertEquals(Math.abs(v[0]), Math.abs(v[1]), 1e-9);
        assertEquals(1 / Math.sqrt(2), Math.abs(v[0]), 1e-9);
        assertEquals(0, v[2], 1e-9);
    }

    @Test
    void boxWithoutLandmarksIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> HeadPoseEstimator.fromLandmarks5(FaceBox.of(0, 0, 10, 10, 1)));
    }

    /** Projects the rigid reference points (all other mesh points at the centroid) for a pose. */
    static FaceMesh syntheticMesh(double yaw, double pitch, double roll) {
        double[] r = HeadPoseEstimator.toRotation(yaw, pitch, roll);
        float[] xyz = new float[FaceMesh.POINTS_WITH_IRIS * 3];
        double scale = 12;
        for (int i = 0; i < CanonicalFace.RIGID_INDICES.length; i++) {
            double[] cam = rotate(r, CanonicalFace.RIGID_XYZ, i);
            int idx = CanonicalFace.RIGID_INDICES[i];
            xyz[3 * idx] = (float) (320 + scale * cam[0]);
            xyz[3 * idx + 1] = (float) (240 - scale * cam[1]);
            xyz[3 * idx + 2] = (float) (-scale * cam[2]);
        }
        return FaceMesh.of(xyz);
    }

    static FaceBox syntheticBox(double yaw, double pitch, double roll) {
        double[] r = HeadPoseEstimator.toRotation(yaw, pitch, roll);
        List<FacePoint> pts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double[] cam = rotate(r, CanonicalFace.LANDMARK5_XYZ, i);
            pts.add(FacePoint.of(320 + 12 * cam[0], 240 - 12 * cam[1]));
        }
        return FaceBox.of(250, 150, 140, 180, 0.9).withLandmarks(pts);
    }

    private static double[] rotate(double[] r, double[] xyz, int i) {
        double x = xyz[3 * i];
        double y = xyz[3 * i + 1];
        double z = xyz[3 * i + 2];
        return new double[] {r[0] * x + r[1] * y + r[2] * z, r[3] * x + r[4] * y + r[5] * z, r[6] * x + r[7] * y + r[8] * z};
    }
}
