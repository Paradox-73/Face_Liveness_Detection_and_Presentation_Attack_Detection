/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.image;

/**
 * Layout of image data in a {@link io.mosip.liveness.core.device.FaceFrame} or an {@link ImageBuffer}.
 *
 * <p>Encoded formats ({@link #isEncoded()}) are compressed files and may appear only in frames; a platform
 * {@link io.mosip.liveness.core.api.ImageDecoder} turns them into an {@link ImageBuffer}. Raw formats are
 * tightly packed, top row first, with no padding at the end of a row: a producer whose rows are padded (for
 * example a camera plane with a row stride larger than the width) must repack the rows first.
 */
public enum PixelFormat {

    /** JPEG file bytes (what SBI MJPEG streams deliver). */
    JPEG(true, false, 0),

    /** PNG file bytes. */
    PNG(true, false, 0),

    /** JPEG 2000 codestream or file bytes (the image inside an ISO/IEC 19794-5 face record). */
    JPEG2000(true, false, 0),

    /** 3 bytes per pixel in the order red, green, blue. */
    RGB888(false, false, 3),

    /** 3 bytes per pixel in the order blue, green, red (OpenCV order; the order ONNX PAD models expect). */
    BGR888(false, false, 3),

    /** 4 bytes per pixel in the order red, green, blue, alpha (Android CameraX {@code RGBA_8888} output). */
    RGBA8888(false, false, 4),

    /** 1 byte per pixel, grey level 0-255. */
    GRAY8(false, false, 1),

    /**
     * YUV 4:2:0 in NV21 layout: the full-resolution Y plane, then interleaved V and U samples at half
     * resolution in both directions. Width and height must be even.
     */
    NV21(false, false, 0),

    /**
     * One {@code int} per pixel packed as {@code 0xAARRGGBB}, the layout of {@code BufferedImage.TYPE_INT_ARGB}
     * and {@code Bitmap.getPixels}. Valid only in an int-backed {@link ImageBuffer}.
     */
    ARGB8888_INT(false, true, 1);

    private final boolean encoded;
    private final boolean intPacked;
    private final int elementsPerPixel;

    PixelFormat(boolean encoded, boolean intPacked, int elementsPerPixel) {
        this.encoded = encoded;
        this.intPacked = intPacked;
        this.elementsPerPixel = elementsPerPixel;
    }

    /**
     * Tells whether data in this format is a compressed file that must be decoded before analysis.
     *
     * @return {@code true} for {@link #JPEG}, {@link #PNG} and {@link #JPEG2000}
     */
    public boolean isEncoded() {
        return encoded;
    }

    /**
     * Tells whether pixels of this format are stored in an {@code int[]} rather than a {@code byte[]}.
     *
     * @return {@code true} only for {@link #ARGB8888_INT}
     */
    public boolean isIntPacked() {
        return intPacked;
    }

    /**
     * Returns the number of array elements (bytes, or ints for {@link #ARGB8888_INT}) that an image of the
     * given size occupies in this raw format.
     *
     * @param width  image width in pixels; positive
     * @param height image height in pixels; positive
     * @return the exact array length required
     * @throws IllegalArgumentException if the size is not positive, if the format is encoded, or if NV21 is
     *                                  given an odd width or height
     * @throws ArithmeticException      if the length does not fit in an {@code int}
     */
    public int requiredLength(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        if (encoded) {
            throw new IllegalArgumentException("encoded format has no fixed length: " + name());
        }
        int pixels = Math.multiplyExact(width, height);
        if (this == NV21) {
            if (width % 2 != 0 || height % 2 != 0) {
                throw new IllegalArgumentException("NV21 needs an even width and height");
            }
            return Math.addExact(pixels, pixels / 2);
        }
        return Math.multiplyExact(pixels, elementsPerPixel);
    }
}
