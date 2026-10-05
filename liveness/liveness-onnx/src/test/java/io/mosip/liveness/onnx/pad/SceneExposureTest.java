/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.pad;

import io.mosip.liveness.onnx.image.ChannelOrder;
import io.mosip.liveness.onnx.image.Rgb8Image;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exposure gain for the PAD crop of a dark frame. */
class SceneExposureTest {

    @Test
    void normallyLitFramesAreLeftAlone() {
        assertEquals(1.0, SceneExposure.gain(SceneExposure.DARK_SCENE_MEAN), 0);
        assertEquals(1.0, SceneExposure.gain(150), 0);
        assertEquals(1.0, SceneExposure.gain(Double.NaN), 0);
    }

    @Test
    void darkFramesAreBrightenedTowardsTheTarget() {
        assertEquals(SceneExposure.TARGET_MEAN / 80, SceneExposure.gain(80), 1e-12);
        assertEquals(SceneExposure.MAX_GAIN, SceneExposure.gain(20), 0);
        assertEquals(SceneExposure.MAX_GAIN, SceneExposure.gain(0), 0);
    }

    @Test
    void meanGreyUsesTheChannelOrder() {
        byte[] d = new byte[16 * 16 * 3];
        for (int i = 0; i < d.length; i += 3) {
            d[i] = (byte) 200;
        }
        // 200 in the red byte: grey 77 * 200 / 256; read as blue: 29 * 200 / 256.
        assertEquals(77 * 200 / 256.0, SceneExposure.meanGrey(Rgb8Image.wrap(16, 16, d, ChannelOrder.RGB)), 1e-9);
        assertEquals(29 * 200 / 256.0, SceneExposure.meanGrey(Rgb8Image.wrap(16, 16, d, ChannelOrder.BGR)), 1e-9);
    }

    @Test
    void uniformFrameGivesItsGreyLevel() {
        byte[] d = new byte[40 * 30 * 3];
        Arrays.fill(d, (byte) 64);
        Rgb8Image img = Rgb8Image.wrap(40, 30, d, ChannelOrder.BGR);
        assertEquals(64.0, SceneExposure.meanGrey(img), 1e-9);
        assertEquals(SceneExposure.TARGET_MEAN / 64.0, SceneExposure.gain(img), 1e-9);
    }
}
