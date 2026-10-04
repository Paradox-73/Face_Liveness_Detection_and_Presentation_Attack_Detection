/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.image;

/**
 * Order of the three colour bytes of a pixel in an {@link Rgb8Image} or of the three planes of a model tensor.
 */
public enum ChannelOrder {

    /** Red, green, blue (MediaPipe Face Mesh and SFace inputs). */
    RGB(0, 2),

    /** Blue, green, red (OpenCV order; YuNet and MiniFASNet inputs). */
    BGR(2, 0);

    private final int redIndex;
    private final int blueIndex;

    ChannelOrder(int redIndex, int blueIndex) {
        this.redIndex = redIndex;
        this.blueIndex = blueIndex;
    }

    /**
     * Returns the position of red within a pixel.
     *
     * @return 0 or 2
     */
    public int redIndex() {
        return redIndex;
    }

    /**
     * Returns the position of blue within a pixel.
     *
     * @return 0 or 2
     */
    public int blueIndex() {
        return blueIndex;
    }
}
