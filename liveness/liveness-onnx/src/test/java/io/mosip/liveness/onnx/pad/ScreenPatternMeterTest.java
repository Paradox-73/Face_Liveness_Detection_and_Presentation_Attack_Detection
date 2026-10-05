/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.pad;

import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.onnx.image.ChannelOrder;
import io.mosip.liveness.onnx.image.Rgb8Image;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screen-pattern check on generated images: smooth skin with camera noise against the same skin seen through a
 * screen's pixel grid (one column in three brighter per colour, as tools/eval/synth.py draws it).
 */
class ScreenPatternMeterTest {

    private static final int W = 320;
    private static final int H = 240;
    private static final FaceBox FACE = FaceBox.of(100, 60, 120, 140, 0.9);

    @Test
    void fftOfAnImpulseIsFlat() {
        double[] re = new double[8];
        double[] im = new double[8];
        re[0] = 1;
        ScreenPatternMeter.fft(re, im);
        for (int i = 0; i < 8; i++) {
            assertEquals(1.0, re[i], 1e-12);
            assertEquals(0.0, im[i], 1e-12);
        }
    }

    @Test
    void fftFindsTheFrequencyOfACosine() {
        double[] re = new double[64];
        double[] im = new double[64];
        for (int i = 0; i < 64; i++) {
            re[i] = Math.cos(2 * Math.PI * 5 * i / 64.0);
        }
        ScreenPatternMeter.fft(re, im);
        assertEquals(32.0, Math.hypot(re[5], im[5]), 1e-9);
        assertEquals(32.0, Math.hypot(re[59], im[59]), 1e-9);
        assertEquals(0.0, Math.hypot(re[6], im[6]), 1e-9);
    }

    @Test
    void liveSkinWithNoiseScoresLow() {
        double ratio = ScreenPatternMeter.peakRatio(skin(false, 1), FACE);
        assertTrue(ratio < ScreenPatternMeter.LIVE_MAX_RATIO, "ratio " + ratio);
        assertEquals(1.0, ScreenPatternMeter.liveProbability(ratio), 0);
    }

    @Test
    void skinSeenThroughAPixelGridScoresAsAScreen() {
        double ratio = ScreenPatternMeter.peakRatio(skin(true, 1), FACE);
        assertTrue(ratio > ScreenPatternMeter.SCREEN_MIN_RATIO, "ratio " + ratio);
        assertEquals(0.0, ScreenPatternMeter.liveProbability(ratio), 0);
    }

    @Test
    void channelOrderDoesNotMatter() {
        Rgb8Image rgb = skin(true, 2);
        byte[] d = rgb.data().clone();
        for (int i = 0; i < d.length; i += 3) {
            byte t = d[i];
            d[i] = d[i + 2];
            d[i + 2] = t;
        }
        Rgb8Image bgr = Rgb8Image.wrap(W, H, d, ChannelOrder.BGR);
        assertEquals(ScreenPatternMeter.peakRatio(rgb, FACE), ScreenPatternMeter.peakRatio(bgr, FACE), 1e-6);
    }

    @Test
    void smallOrClippedFacesAreNotMeasured() {
        Rgb8Image img = skin(true, 3);
        assertTrue(Double.isNaN(ScreenPatternMeter.peakRatio(img, FaceBox.of(100, 60, 60, 140, 0.9))));
        assertTrue(Double.isNaN(ScreenPatternMeter.peakRatio(img, FaceBox.of(-70, 60, 120, 140, 0.9))));
        assertTrue(Double.isNaN(ScreenPatternMeter.peakRatio(img, FaceBox.of(250, 150, 120, 140, 0.9))));
        assertEquals(1.0, ScreenPatternMeter.liveProbability(Double.NaN), 0);
    }

    @Test
    void liveProbabilityFallsLinearlyBetweenTheTwoRatios() {
        assertEquals(1.0, ScreenPatternMeter.liveProbability(ScreenPatternMeter.LIVE_MAX_RATIO), 0);
        assertEquals(0.5, ScreenPatternMeter.liveProbability(
                (ScreenPatternMeter.LIVE_MAX_RATIO + ScreenPatternMeter.SCREEN_MIN_RATIO) / 2), 1e-12);
        assertEquals(0.0, ScreenPatternMeter.liveProbability(ScreenPatternMeter.SCREEN_MIN_RATIO), 0);
    }

    /** Skin-coloured shading with Gaussian camera noise; with {@code grid}, a 3-column sub-pixel pattern. */
    static Rgb8Image skin(boolean grid, long seed) {
        Random random = new Random(seed);
        byte[] d = new byte[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double shade = 0.75 + 0.25 * Math.sin(x / 37.0) * Math.cos(y / 29.0);
                double[] c = {200 * shade, 150 * shade, 120 * shade};
                for (int k = 0; k < 3; k++) {
                    double v = c[k];
                    if (grid && x % 3 != k) {
                        v *= 0.93;
                    }
                    v += random.nextGaussian() * 2.0;
                    d[(y * W + x) * 3 + k] = (byte) Math.max(0, Math.min(255, Math.round(v)));
                }
            }
        }
        return Rgb8Image.wrap(W, H, d, ChannelOrder.RGB);
    }
}
