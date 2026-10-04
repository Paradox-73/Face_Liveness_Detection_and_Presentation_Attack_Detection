/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.mesh;

import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceMesh;
import io.mosip.liveness.core.vision.FacePoint;
import io.mosip.liveness.core.vision.HeadPose;

import java.util.List;

/**
 * Estimates yaw, pitch and roll by fitting a reference 3-D face ({@link CanonicalFace}) to measured landmarks.
 *
 * <p><b>Method.</b> Image points are moved into a camera frame with x to the right, y up and z towards the
 * camera. The rotation {@code R} that best maps the reference points onto the measured ones is found by least
 * squares, then split as {@code R = Ry(yaw) * Rx(-pitch) * Rz(-roll)}:
 * <ul>
 *   <li>{@link #fromMesh(FaceMesh)}: 30 rigid Face Mesh points with their x, y and relative depth z; rotation by
 *       Horn's closed-form quaternion method (the 3-D Procrustes / Kabsch fit), so no camera model is needed.</li>
 *   <li>{@link #fromLandmarks5(FaceBox)}: the five detector points, 2-D only; a weak-perspective fit (linear least
 *       squares for the 2x3 projection, rows then orthonormalised). Coarser: the mouth corners move with
 *       expressions. Used when no mesh is available.</li>
 * </ul>
 * Signs follow {@link HeadPose}: yaw positive when the subject turns to their own left, pitch positive when
 * looking up, roll positive when the head tilts to the subject's own left shoulder. The image must not be
 * mirrored.
 */
public final class HeadPoseEstimator {

    private HeadPoseEstimator() {
    }

    /**
     * Estimates the pose from a Face Mesh.
     *
     * @param mesh landmarks in image pixels with relative depth
     * @return the pose
     */
    public static HeadPose fromMesh(FaceMesh mesh) {
        int n = CanonicalFace.RIGID_INDICES.length;
        double[] observed = new double[n * 3];
        for (int i = 0; i < n; i++) {
            int idx = CanonicalFace.RIGID_INDICES[i];
            observed[3 * i] = mesh.x(idx);
            observed[3 * i + 1] = -mesh.y(idx);
            observed[3 * i + 2] = -mesh.z(idx);
        }
        return toPose(fitRotation(CanonicalFace.RIGID_XYZ, observed));
    }

    /**
     * Estimates the pose from the five detector landmarks.
     *
     * @param face a box with five landmarks
     * @return the pose
     * @throws IllegalArgumentException if the box has no landmarks
     */
    public static HeadPose fromLandmarks5(FaceBox face) {
        if (!face.hasLandmarks()) {
            throw new IllegalArgumentException("five landmarks required");
        }
        List<FacePoint> pts = face.landmarks();
        double[] observed2d = new double[10];
        for (int i = 0; i < 5; i++) {
            observed2d[2 * i] = pts.get(i).x();
            observed2d[2 * i + 1] = -pts.get(i).y();
        }
        return toPose(fitWeakPerspective(CanonicalFace.LANDMARK5_XYZ, observed2d));
    }

    /**
     * Least-squares rotation mapping {@code model} points onto {@code observed} points (Horn 1987).
     *
     * @param model    x, y, z per point
     * @param observed x, y, z per point, same count
     * @return row-major 3x3 rotation
     */
    static double[] fitRotation(double[] model, double[] observed) {
        int n = model.length / 3;
        double[] mc = centroid3(model);
        double[] oc = centroid3(observed);
        double[][] s = new double[3][3];
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    s[a][b] += (model[3 * i + a] - mc[a]) * (observed[3 * i + b] - oc[b]);
                }
            }
        }
        double[][] k = {
            {s[0][0] + s[1][1] + s[2][2], s[1][2] - s[2][1], s[2][0] - s[0][2], s[0][1] - s[1][0]},
            {s[1][2] - s[2][1], s[0][0] - s[1][1] - s[2][2], s[0][1] + s[1][0], s[2][0] + s[0][2]},
            {s[2][0] - s[0][2], s[0][1] + s[1][0], -s[0][0] + s[1][1] - s[2][2], s[1][2] + s[2][1]},
            {s[0][1] - s[1][0], s[2][0] + s[0][2], s[1][2] + s[2][1], -s[0][0] - s[1][1] + s[2][2]},
        };
        double[] q = SymmetricEigen.largestEigenvector(k);
        return quaternionToMatrix(q[0], q[1], q[2], q[3]);
    }

    /**
     * Weak-perspective fit: the 2x3 matrix {@code A} minimising the squared error of {@code A * model} against the
     * 2-D points, with rows orthonormalised into a rotation.
     *
     * @param model      x, y, z per point (at least four points, not coplanar)
     * @param observed2d x, y per point
     * @return row-major 3x3 rotation
     */
    static double[] fitWeakPerspective(double[] model, double[] observed2d) {
        int n = model.length / 3;
        double[] mc = centroid3(model);
        double[] oc = {0, 0};
        for (int i = 0; i < n; i++) {
            oc[0] += observed2d[2 * i] / n;
            oc[1] += observed2d[2 * i + 1] / n;
        }
        double[][] ptp = new double[3][3];
        double[][] qtp = new double[2][3];
        for (int i = 0; i < n; i++) {
            double[] p = {model[3 * i] - mc[0], model[3 * i + 1] - mc[1], model[3 * i + 2] - mc[2]};
            double[] q = {observed2d[2 * i] - oc[0], observed2d[2 * i + 1] - oc[1]};
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    ptp[a][b] += p[a] * p[b];
                }
                qtp[0][a] += q[0] * p[a];
                qtp[1][a] += q[1] * p[a];
            }
        }
        double[][] inv = invert3(ptp);
        double[] r1 = multiply(qtp[0], inv);
        double[] r2 = multiply(qtp[1], inv);
        normalize(r1);
        double d = dot(r2, r1);
        for (int a = 0; a < 3; a++) {
            r2[a] -= d * r1[a];
        }
        normalize(r2);
        double[] r3 = {r1[1] * r2[2] - r1[2] * r2[1], r1[2] * r2[0] - r1[0] * r2[2], r1[0] * r2[1] - r1[1] * r2[0]};
        return new double[] {r1[0], r1[1], r1[2], r2[0], r2[1], r2[2], r3[0], r3[1], r3[2]};
    }

    /**
     * Splits a rotation {@code R = Ry(a) * Rx(b) * Rz(c)} into a pose: yaw = a, pitch = -b, roll = -c.
     *
     * @param r row-major 3x3 rotation from the reference face to the camera frame
     * @return the pose in degrees
     */
    static HeadPose toPose(double[] r) {
        double b = Math.asin(Math.max(-1, Math.min(1, -r[5])));
        double a = Math.atan2(r[2], r[8]);
        double c = Math.atan2(r[3], r[4]);
        return HeadPose.of(Math.toDegrees(a), -Math.toDegrees(b), -Math.toDegrees(c));
    }

    /**
     * Builds the rotation of a pose (inverse of {@link #toPose(double[])}); used by tests.
     *
     * @param yawDeg   yaw
     * @param pitchDeg pitch
     * @param rollDeg  roll
     * @return row-major 3x3 rotation
     */
    static double[] toRotation(double yawDeg, double pitchDeg, double rollDeg) {
        double a = Math.toRadians(yawDeg);
        double b = Math.toRadians(-pitchDeg);
        double c = Math.toRadians(-rollDeg);
        double[] ry = {Math.cos(a), 0, Math.sin(a), 0, 1, 0, -Math.sin(a), 0, Math.cos(a)};
        double[] rx = {1, 0, 0, 0, Math.cos(b), -Math.sin(b), 0, Math.sin(b), Math.cos(b)};
        double[] rz = {Math.cos(c), -Math.sin(c), 0, Math.sin(c), Math.cos(c), 0, 0, 0, 1};
        return multiply3(ry, multiply3(rx, rz));
    }

    private static double[] quaternionToMatrix(double w, double x, double y, double z) {
        return new double[] {
            w * w + x * x - y * y - z * z, 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), w * w - x * x + y * y - z * z, 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), w * w - x * x - y * y + z * z,
        };
    }

    private static double[] centroid3(double[] pts) {
        int n = pts.length / 3;
        double[] c = new double[3];
        for (int i = 0; i < n; i++) {
            c[0] += pts[3 * i] / n;
            c[1] += pts[3 * i + 1] / n;
            c[2] += pts[3 * i + 2] / n;
        }
        return c;
    }

    private static double[][] invert3(double[][] m) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        if (Math.abs(det) < 1e-12) {
            throw new IllegalArgumentException("reference points are coplanar");
        }
        double[][] inv = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                int r0 = (j + 1) % 3;
                int r1 = (j + 2) % 3;
                int c0 = (i + 1) % 3;
                int c1 = (i + 2) % 3;
                inv[i][j] = (m[r0][c0] * m[r1][c1] - m[r0][c1] * m[r1][c0]) / det;
            }
        }
        return inv;
    }

    private static double[] multiply(double[] row, double[][] m) {
        double[] out = new double[3];
        for (int j = 0; j < 3; j++) {
            out[j] = row[0] * m[0][j] + row[1] * m[1][j] + row[2] * m[2][j];
        }
        return out;
    }

    private static double[] multiply3(double[] a, double[] b) {
        double[] out = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[3 * i + j] = a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j];
            }
        }
        return out;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void normalize(double[] v) {
        double len = Math.sqrt(dot(v, v));
        if (len < 1e-12) {
            throw new IllegalArgumentException("degenerate landmarks");
        }
        v[0] /= len;
        v[1] /= len;
        v[2] /= len;
    }
}
