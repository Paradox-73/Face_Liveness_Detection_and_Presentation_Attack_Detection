/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.image;

import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;

/**
 * Turns raw camera or bitmap pixels into the upright, non-mirrored RGB888 image the engine analyses, in one
 * pass: rotation clockwise by the frame's rotation, then an optional left-right flip, then channel order.
 *
 * <p>Why the flip matters: head pose, gaze and "turn left / turn right" are measured relative to the subject.
 * The engine assumes an image as a camera facing the person records it (the subject's right eye on the left of
 * the image). CameraX analysis frames from the front camera are already like that; only a frame marked
 * {@code mirrored} (a selfie-style picture) is flipped back.
 *
 * <p>Pure Java, no Android classes, so it is tested on the JVM.
 */
public final class PixelOrientation {

    private PixelOrientation() {
    }

    /**
     * Converts packed byte pixels to an upright RGB888 image.
     *
     * @param src             pixels, tightly packed rows
     * @param format          {@link PixelFormat#RGBA8888}, {@link PixelFormat#RGB888}, {@link PixelFormat#BGR888}
     *                        or {@link PixelFormat#GRAY8}
     * @param width           source width
     * @param height          source height
     * @param rotationDegrees clockwise rotation that makes the image upright: 0, 90, 180 or 270
     * @param mirrored        whether the source is mirrored left-right
     * @return a new RGB888 image
     * @throws IllegalArgumentException if the format, rotation or array length is not supported
     */
    public static ImageBuffer toUprightRgb(byte[] src, PixelFormat format, int width, int height,
                                           int rotationDegrees, boolean mirrored) {
        int channels = channels(format);
        if (src.length < format.requiredLength(width, height)) {
            throw new IllegalArgumentException("pixel array too short for " + width + "x" + height + " " + format);
        }
        int[] order = channelOrder(format);
        Geometry g = new Geometry(width, height, rotationDegrees, mirrored);
        byte[] dst = new byte[g.outWidth * g.outHeight * 3];
        int d = 0;
        for (int oy = 0; oy < g.outHeight; oy++) {
            for (int ox = 0; ox < g.outWidth; ox++) {
                int s = g.sourceIndex(ox, oy) * channels;
                dst[d++] = src[s + order[0]];
                dst[d++] = src[s + order[1]];
                dst[d++] = src[s + order[2]];
            }
        }
        return ImageBuffer.ofBytes(g.outWidth, g.outHeight, PixelFormat.RGB888, dst);
    }

    /**
     * Converts {@code 0xAARRGGBB} pixels (the layout of {@code Bitmap.getPixels}) to an upright RGB888 image.
     *
     * @param src             pixels, row by row
     * @param width           source width
     * @param height          source height
     * @param rotationDegrees clockwise rotation that makes the image upright
     * @param mirrored        whether the source is mirrored left-right
     * @return a new RGB888 image
     */
    public static ImageBuffer toUprightRgb(int[] src, int width, int height, int rotationDegrees, boolean mirrored) {
        if (src.length < width * height) {
            throw new IllegalArgumentException("pixel array too short for " + width + "x" + height);
        }
        Geometry g = new Geometry(width, height, rotationDegrees, mirrored);
        byte[] dst = new byte[g.outWidth * g.outHeight * 3];
        int d = 0;
        for (int oy = 0; oy < g.outHeight; oy++) {
            for (int ox = 0; ox < g.outWidth; ox++) {
                int p = src[g.sourceIndex(ox, oy)];
                dst[d++] = (byte) (p >> 16);
                dst[d++] = (byte) (p >> 8);
                dst[d++] = (byte) p;
            }
        }
        return ImageBuffer.ofBytes(g.outWidth, g.outHeight, PixelFormat.RGB888, dst);
    }

    /**
     * Converts an RGB888 image to {@code 0xFFRRGGBB} pixels for {@code Bitmap.createBitmap}.
     *
     * @param rgb RGB888 image
     * @return one int per pixel
     */
    public static int[] toArgb(ImageBuffer rgb) {
        if (rgb.format() != PixelFormat.RGB888) {
            throw new IllegalArgumentException("expected RGB888, got " + rgb.format());
        }
        byte[] b = rgb.bytes();
        int[] out = new int[rgb.width() * rgb.height()];
        for (int i = 0, s = 0; i < out.length; i++, s += 3) {
            out[i] = 0xFF000000 | (b[s] & 0xFF) << 16 | (b[s + 1] & 0xFF) << 8 | (b[s + 2] & 0xFF);
        }
        return out;
    }

    private static int channels(PixelFormat format) {
        switch (format) {
            case RGBA8888:
                return 4;
            case RGB888:
            case BGR888:
                return 3;
            case GRAY8:
                return 1;
            default:
                throw new IllegalArgumentException("unsupported raw format " + format);
        }
    }

    private static int[] channelOrder(PixelFormat format) {
        switch (format) {
            case BGR888:
                return new int[] {2, 1, 0};
            case GRAY8:
                return new int[] {0, 0, 0};
            default:
                return new int[] {0, 1, 2};
        }
    }

    /** Maps an output pixel back to its source pixel. */
    private static final class Geometry {

        final int width;
        final int height;
        final int rotation;
        final boolean mirrored;
        final int outWidth;
        final int outHeight;

        Geometry(int width, int height, int rotationDegrees, boolean mirrored) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be positive");
            }
            int r = ((rotationDegrees % 360) + 360) % 360;
            if (r % 90 != 0) {
                throw new IllegalArgumentException("rotation must be a multiple of 90: " + rotationDegrees);
            }
            this.width = width;
            this.height = height;
            this.rotation = r;
            this.mirrored = mirrored;
            boolean swap = r == 90 || r == 270;
            this.outWidth = swap ? height : width;
            this.outHeight = swap ? width : height;
        }

        int sourceIndex(int ox, int oy) {
            int x = mirrored ? outWidth - 1 - ox : ox;
            int sx;
            int sy;
            switch (rotation) {
                case 90:
                    sx = oy;
                    sy = height - 1 - x;
                    break;
                case 180:
                    sx = width - 1 - x;
                    sy = height - 1 - oy;
                    break;
                case 270:
                    sx = width - 1 - oy;
                    sy = x;
                    break;
                default:
                    sx = x;
                    sy = oy;
                    break;
            }
            return sy * width + sx;
        }
    }
}
