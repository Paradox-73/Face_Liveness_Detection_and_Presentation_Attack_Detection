/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.pad;

import io.mosip.liveness.onnx.image.ChannelOrder;
import io.mosip.liveness.onnx.image.Rgb8Image;

/**
 * Exposure correction for the passive PAD models in a dark room.
 *
 * <p>MiniFASNet was trained on normally exposed faces and calls a dark, noisy face an attack: on the synthetic
 * dim packs ({@code s3-dim-*}: the S3 recordings at 45% brightness with extra noise) its live probability drops from
 * 99 to about 2. When the whole frame is dark (mean grey level below {@link #DARK_SCENE_MEAN}), the PAD crop is
 * brightened by {@code TARGET_MEAN / mean}, at most {@link #MAX_GAIN}, as a camera with a longer exposure would
 * record it. The live probability of the dim packs then comes back to about 50.
 *
 * <p>The trigger is the brightness of the whole frame, not of the face: a darker skin tone in a normally lit room
 * (pack {@code s1-*}: face grey level 66, frame 105) is left untouched, because brightening that face lowers its
 * live probability (48 to 6 at the same gain). Brightening gives an attack nothing it would not get in a normally
 * lit room: the models see the same picture as with good light, and the quality gate still refuses faces darker
 * than {@code quality.min_brightness}.
 */
public final class SceneExposure {

    /** Frames whose mean grey level is below this are corrected. */
    public static final double DARK_SCENE_MEAN = 90;
    /** Mean grey level the correction aims for. */
    public static final double TARGET_MEAN = 160;
    /** Largest gain applied. */
    public static final double MAX_GAIN = 2.5;

    private static final int STEP = 4;

    private SceneExposure() {
    }

    /**
     * Returns the mean grey level of the frame, sampled on every fourth pixel of every fourth row.
     *
     * @param image the frame
     * @return mean grey level, 0-255
     */
    public static double meanGrey(Rgb8Image image) {
        byte[] d = image.data();
        ChannelOrder order = image.order();
        int ri = order.redIndex();
        int bi = order.blueIndex();
        long sum = 0;
        long n = 0;
        for (int y = 0; y < image.height(); y += STEP) {
            int row = y * image.width() * 3;
            for (int x = 0; x < image.width(); x += STEP) {
                int i = row + x * 3;
                sum += 77 * (d[i + ri] & 0xFF) + 150 * (d[i + 1] & 0xFF) + 29 * (d[i + bi] & 0xFF);
                n++;
            }
        }
        return n == 0 ? 0 : sum / 256.0 / n;
    }

    /**
     * Returns the exposure gain for a frame with this mean grey level.
     *
     * @param meanGrey mean grey level of the frame
     * @return 1 for a normally lit frame; up to {@link #MAX_GAIN} for a dark one
     */
    public static double gain(double meanGrey) {
        if (!(meanGrey < DARK_SCENE_MEAN)) {
            return 1.0;
        }
        if (meanGrey <= 0) {
            return MAX_GAIN;
        }
        return Math.max(1.0, Math.min(MAX_GAIN, TARGET_MEAN / meanGrey));
    }

    /**
     * Returns the exposure gain for a frame.
     *
     * @param image the frame
     * @return 1 for a normally lit frame; up to {@link #MAX_GAIN} for a dark one
     */
    public static double gain(Rgb8Image image) {
        return gain(meanGrey(image));
    }
}
