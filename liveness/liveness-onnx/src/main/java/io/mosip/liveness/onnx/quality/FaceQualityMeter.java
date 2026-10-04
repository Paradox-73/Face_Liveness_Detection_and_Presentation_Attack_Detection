/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.quality;

import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceQuality;
import io.mosip.liveness.onnx.image.ImageOps;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.image.TensorFormat;

/**
 * Measures brightness and sharpness of a face.
 *
 * <p>The face box, clamped to the image, is resized to {@value #CROP_SIZE}x{@value #CROP_SIZE} grey pixels
 * (ITU-R BT.601 luma). Brightness is the mean grey level. Sharpness is the variance of the 4-neighbour
 * Laplacian over the crop interior. The fixed crop size makes sharpness comparable between near and far faces;
 * it still depends on the camera and compression, so thresholds are calibrated per device class.
 */
public final class FaceQualityMeter {

    /** Side of the grey crop in pixels. */
    public static final int CROP_SIZE = 128;

    private static final float WR = 0.299f;
    private static final float WG = 0.587f;
    private static final float WB = 0.114f;

    private FaceQualityMeter() {
    }

    /**
     * Measures one face.
     *
     * @param image              the image
     * @param face               the face box
     * @param interEyeDistancePx inter-eye distance measured by the caller, in pixels
     * @return the quality measures
     */
    public static FaceQuality measure(Rgb8Image image, FaceBox face, double interEyeDistancePx) {
        int left = clamp((int) Math.floor(face.x()), image.width() - 1);
        int top = clamp((int) Math.floor(face.y()), image.height() - 1);
        int right = clamp((int) Math.ceil(face.x() + face.width()) - 1, image.width() - 1);
        int bottom = clamp((int) Math.ceil(face.y() + face.height()) - 1, image.height() - 1);
        float[] rgb = new float[3 * CROP_SIZE * CROP_SIZE];
        ImageOps.resizeRegionToTensor(image, left, top, Math.max(left, right), Math.max(top, bottom), CROP_SIZE,
                CROP_SIZE, TensorFormat.RGB_RAW, rgb, CROP_SIZE, CROP_SIZE);
        int plane = CROP_SIZE * CROP_SIZE;
        float[] grey = new float[plane];
        double sum = 0;
        for (int i = 0; i < plane; i++) {
            grey[i] = WR * rgb[i] + WG * rgb[plane + i] + WB * rgb[2 * plane + i];
            sum += grey[i];
        }
        double brightness = Math.max(0, Math.min(255, sum / plane));
        return FaceQuality.of(Math.max(0, interEyeDistancePx), laplacianVariance(grey, CROP_SIZE, CROP_SIZE),
                brightness);
    }

    /**
     * Variance of the 4-neighbour Laplacian ({@code 4p - up - down - left - right}) over the interior pixels.
     *
     * @param grey   grey levels, row-major
     * @param width  image width; at least 3
     * @param height image height; at least 3
     * @return the variance, 0 or more
     */
    static double laplacianVariance(float[] grey, int width, int height) {
        double sum = 0;
        double sumSq = 0;
        int n = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                double lap = 4 * grey[i] - grey[i - 1] - grey[i + 1] - grey[i - width] - grey[i + width];
                sum += lap;
                sumSq += lap * lap;
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }
        double mean = sum / n;
        return Math.max(0, sumSq / n - mean * mean);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }
}
