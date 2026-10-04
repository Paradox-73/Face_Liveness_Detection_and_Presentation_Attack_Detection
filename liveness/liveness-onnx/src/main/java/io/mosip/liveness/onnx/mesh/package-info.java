/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
/**
 * Dense landmarks and the measures derived from them:
 * {@link io.mosip.liveness.onnx.mesh.FaceMeshLandmarker} (MediaPipe Face Mesh V2, 478 points),
 * {@link io.mosip.liveness.onnx.mesh.FaceMeasures} (eye aspect ratio, mouth width ratio, gaze, inter-eye
 * distance) and {@link io.mosip.liveness.onnx.mesh.HeadPoseEstimator} (yaw, pitch, roll by fitting a reference
 * 3-D face). The Face Mesh model card excludes life-critical decisions, so these measures drive challenge
 * detection only, never the PAD decision.
 */
package io.mosip.liveness.onnx.mesh;
