/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.mesh;

/**
 * Reference 3-D face used to estimate head pose: a subset of the MediaPipe canonical face model
 * ({@code mediapipe/modules/face_geometry/data/canonical_face_model.obj}, Apache-2.0, google-ai-edge/mediapipe
 * {@code master@2863977e}).
 *
 * <p>Model axes: x towards the subject's own left (the right of a non-mirrored image), y up, z towards the
 * camera; units are centimetres-like. The subset holds points on rigid parts of the face (nose bridge,
 * forehead, eye corners, cheeks, temples) and leaves out the lips, eyelids, brows and jaw, which move with
 * expressions.
 */
final class CanonicalFace {

    /** Face Mesh indices of the rigid subset. */
    static final int[] RIGID_INDICES = {
        1, 4, 5, 6, 9, 10, 151, 168, 195, 197, 33, 133, 263, 362, 234,
        454, 127, 356, 93, 323, 98, 327, 64, 294, 116, 345, 162, 389, 21, 251
    };

    /** x, y, z of each point of {@link #RIGID_INDICES}, from the canonical model. */
    static final double[] RIGID_XYZ = {
        0.000000, -1.126865, 7.475604, // 1 nose tip
        0.000000, -0.463170, 7.586580, // 4
        0.000000, 0.365669, 7.242870, // 5
        0.000000, 2.473255, 5.788627, // 6
        0.000000, 4.885979, 5.385258, // 9
        0.000000, 8.261778, 4.481535, // 10 forehead
        0.000000, 6.545390, 5.027311, // 151
        0.000000, 3.271027, 5.236015, // 168
        0.000000, 1.059413, 6.774605, // 195
        0.000000, 1.728369, 6.316750, // 197
        -4.445859, 2.663991, 3.173422, // 33 right eye, outer corner
        -1.856432, 2.585245, 3.757904, // 133 right eye, inner corner
        4.445859, 2.663991, 3.173422, // 263 left eye, outer corner
        1.856432, 2.585245, 3.757904, // 362 left eye, inner corner
        -7.664182, 0.673132, -2.435867, // 234
        7.664182, 0.673132, -2.435867, // 454
        -7.743095, 2.364999, -2.005167, // 127
        7.743095, 2.364999, -2.005167, // 356
        -7.542244, -1.049282, -2.431321, // 93
        7.542244, -1.049282, -2.431321, // 323
        -1.405627, -1.714196, 5.241087, // 98
        1.405627, -1.714196, 5.241087, // 327
        -1.592294, -1.257709, 5.456949, // 64
        1.592294, -1.257709, 5.456949, // 294
        -6.465170, 0.937119, 1.689873, // 116
        6.465170, 0.937119, 1.689873, // 345
        -7.555811, 4.106811, -0.991917, // 162
        7.555811, 4.106811, -0.991917, // 389
        -7.087960, 5.434801, 0.099620, // 21
        7.087960, 5.434801, 0.099620, // 251
    };

    /**
     * The five detector landmarks on the canonical model, in {@link io.mosip.liveness.core.vision.Landmark5}
     * order: right eye centre (mean of points 33 and 133), left eye centre (263, 362), nose tip (1), right mouth
     * corner (61), left mouth corner (291).
     */
    static final double[] LANDMARK5_XYZ = {
        -3.151146, 2.624618, 3.465663,
        3.151146, 2.624618, 3.465663,
        0.000000, -1.126865, 7.475604,
        -2.456206, -4.342621, 4.283884,
        2.456206, -4.342621, 4.283884,
    };

    private CanonicalFace() {
    }
}
