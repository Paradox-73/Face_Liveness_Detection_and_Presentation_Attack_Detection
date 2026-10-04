/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceMesh;
import io.mosip.liveness.core.vision.FacePoint;
import io.mosip.liveness.core.vision.FaceQuality;
import io.mosip.liveness.core.vision.HeadPose;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic faces: a small 3-D head model rotated by yaw and pitch and projected with a pinhole camera, so head
 * turns produce real parallax. A "planar" face uses the same model flattened to depth 0 (a photo or screen), so
 * its landmarks move by a plane mapping only. No face images are involved.
 */
final class TestFaces {

    static final double IED_PX = 80;
    static final double CX = 320;
    static final double CY = 240;
    private static final double DISTANCE = 8;

    /** Landmark5 model in inter-eye-distance units: x right in the image, y down, z towards the camera negative. */
    private static final double[][] MODEL5 = {
        {-0.5, 0, 0}, {0.5, 0, 0}, {0, 0.45, -0.45}, {-0.4, 0.9, -0.05}, {0.4, 0.9, -0.05}
    };

    private double yaw;
    private double pitch;
    private double ear = 0.30;
    private double mouth = 1.0;
    private boolean planar;
    private boolean mesh;
    private boolean landmarks = true;
    private double shiftX;
    private double brightness = 128;
    private double sharpness = 100;
    private double confidence = 0.95;
    private double iedPx = IED_PX;
    private boolean pose = true;

    static TestFaces neutral() {
        return new TestFaces();
    }

    TestFaces yaw(double v) {
        yaw = v;
        return this;
    }

    TestFaces pitch(double v) {
        pitch = v;
        return this;
    }

    TestFaces ear(double v) {
        ear = v;
        return this;
    }

    TestFaces mouth(double v) {
        mouth = v;
        return this;
    }

    TestFaces planar() {
        planar = true;
        return this;
    }

    TestFaces withMesh() {
        mesh = true;
        return this;
    }

    TestFaces noLandmarks() {
        landmarks = false;
        return this;
    }

    TestFaces noPose() {
        pose = false;
        return this;
    }

    TestFaces shiftX(double px) {
        shiftX = px;
        return this;
    }

    TestFaces brightness(double v) {
        brightness = v;
        return this;
    }

    TestFaces sharpness(double v) {
        sharpness = v;
        return this;
    }

    TestFaces confidence(double v) {
        confidence = v;
        return this;
    }

    TestFaces ied(double px) {
        iedPx = px;
        return this;
    }

    /** Returns the face performing a challenge action at full strength. */
    static TestFaces acting(ChallengeType type) {
        switch (type) {
            case TURN_LEFT:
                return neutral().yaw(25);
            case TURN_RIGHT:
                return neutral().yaw(-25);
            case LOOK_UP:
                return neutral().pitch(20);
            case LOOK_DOWN:
                return neutral().pitch(-20);
            case SMILE:
                return neutral().mouth(1.3);
            case BLINK:
                return neutral().ear(0.08);
            default:
                throw new IllegalArgumentException("no synthetic action for " + type);
        }
    }

    DetectedFace build() {
        double s = iedPx;
        FaceBox box = FaceBox.of(CX + shiftX - s, CY - 0.8 * s, 2 * s, 2.2 * s, confidence);
        if (landmarks) {
            List<FacePoint> pts = new ArrayList<>();
            for (double[] p : MODEL5) {
                pts.add(project(p[0], p[1], planar ? 0 : p[2]));
            }
            box = box.withLandmarks(pts);
        }
        DetectedFace.Builder b = DetectedFace.builder(box).eyeAspectRatios(ear, ear).mouthWidthRatio(mouth)
                .quality(FaceQuality.of(iedPx, sharpness, brightness));
        if (pose) {
            b.pose(HeadPose.of(yaw, pitch, 0));
        }
        if (mesh) {
            b.mesh(buildMesh());
        }
        return b.build();
    }

    private FacePoint project(double x, double y, double z) {
        double ry = Math.toRadians(yaw);
        double rp = Math.toRadians(pitch);
        // Yaw about the vertical axis: a positive yaw (subject's own left) moves the nose tip to image right.
        double x1 = x * Math.cos(ry) - z * Math.sin(ry);
        double z1 = x * Math.sin(ry) + z * Math.cos(ry);
        // Pitch about the horizontal axis: a positive pitch (looking up) moves the nose tip up.
        double y2 = y * Math.cos(rp) + z1 * Math.sin(rp);
        double z2 = -y * Math.sin(rp) + z1 * Math.cos(rp);
        double f = DISTANCE / (DISTANCE + z2);
        return FacePoint.of(CX + shiftX + iedPx * x1 * f, CY + iedPx * y2 * f);
    }

    private FaceMesh buildMesh() {
        float[] xyz = new float[FaceMesh.POINTS_WITH_IRIS * 3];
        int n = PlanarityChecker.RIGID_MESH_POINTS.length;
        for (int k = 0; k < n; k++) {
            int idx = PlanarityChecker.RIGID_MESH_POINTS[k];
            // Spread the rigid points over the face and give them a curved depth with a protruding nose.
            double x = -0.9 + 1.8 * ((k * 7) % n) / (n - 1.0);
            double y = -0.6 + 1.8 * ((k * 3) % n) / (n - 1.0);
            double z = planar ? 0 : -0.5 * Math.exp(-(x * x + (y - 0.4) * (y - 0.4)) / 0.08) + 0.35 * x * x + 0.1 * y * y;
            FacePoint p = project(x, y, z);
            xyz[idx * 3] = (float) p.x();
            xyz[idx * 3 + 1] = (float) p.y();
            xyz[idx * 3 + 2] = (float) z;
        }
        return FaceMesh.of(xyz);
    }
}
