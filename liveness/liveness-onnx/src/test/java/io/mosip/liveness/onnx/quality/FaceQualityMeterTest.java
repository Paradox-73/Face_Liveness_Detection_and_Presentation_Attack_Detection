/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.quality;

import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceQuality;
import io.mosip.liveness.onnx.image.ChannelOrder;
import io.mosip.liveness.onnx.image.ImageOps;
import io.mosip.liveness.onnx.image.Rgb8Image;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceQualityMeterTest {

    private static Rgb8Image flat(int value) {
        byte[] d = new byte[200 * 200 * 3];
        Arrays.fill(d, (byte) value);
        return Rgb8Image.wrap(200, 200, d, ChannelOrder.RGB);
    }

    private static Rgb8Image stripes(int period) {
        byte[] d = new byte[200 * 200 * 3];
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                byte v = (byte) (((x / period) + (y / period)) % 2 == 0 ? 30 : 220);
                int i = (y * 200 + x) * 3;
                d[i] = v;
                d[i + 1] = v;
                d[i + 2] = v;
            }
        }
        return Rgb8Image.wrap(200, 200, d, ChannelOrder.RGB);
    }

    @Test
    void flatFaceHasNoSharpnessAndItsGreyLevelAsBrightness() {
        FaceQuality q = FaceQualityMeter.measure(flat(90), FaceBox.of(20, 30, 100, 120, 0.9), 55);
        assertEquals(0, q.sharpness(), 1e-6);
        assertEquals(90, q.brightness(), 0.01);
        assertEquals(55, q.interEyeDistancePx(), 1e-12);
    }

    @Test
    void blurLowersSharpness() {
        FaceBox box = FaceBox.of(0, 0, 200, 200, 1);
        Rgb8Image sharp = stripes(4);
        Rgb8Image blurred = ImageOps.resize(ImageOps.resize(sharp, 40, 40), 200, 200);
        double s = FaceQualityMeter.measure(sharp, box, 60).sharpness();
        double b = FaceQualityMeter.measure(blurred, box, 60).sharpness();
        assertTrue(s > 10 * b, "sharp " + s + " vs blurred " + b);
    }

    @Test
    void boxOutsideTheImageIsClamped() {
        FaceQuality q = FaceQualityMeter.measure(flat(200), FaceBox.of(-50, 150, 120, 120, 0.9), 40);
        assertEquals(200, q.brightness(), 0.01);
        FaceQuality far = FaceQualityMeter.measure(flat(10), FaceBox.of(500, 500, 20, 20, 0.9), -1);
        assertEquals(10, far.brightness(), 0.01);
        assertEquals(0, far.interEyeDistancePx(), 1e-12);
    }

    @Test
    void laplacianOfALinearRampIsZero() {
        float[] ramp = new float[10 * 10];
        for (int i = 0; i < ramp.length; i++) {
            ramp[i] = (i % 10) * 3f + (i / 10) * 2f;
        }
        assertEquals(0, FaceQualityMeter.laplacianVariance(ramp, 10, 10), 1e-9);
        assertEquals(0, FaceQualityMeter.laplacianVariance(new float[4], 2, 2), 1e-12);
    }
}
