/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.gemalto.jp2.JP2Decoder;

import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;

/**
 * {@link ImageDecoder} of the Android client.
 *
 * <p>Raw CameraX frames ({@link PixelFormat#RGBA8888}) and encoded frames (JPEG and PNG through
 * {@link BitmapFactory}, JPEG 2000 through the JP2ForAndroid decoder ARC already ships) become an upright,
 * non-mirrored {@link PixelFormat#RGB888} image, using the frame's rotation and mirror flag (see
 * {@link PixelOrientation}). Decoded bitmaps are recycled at once; no image is written to storage.
 *
 * <p>Thread-safe.
 */
public final class AndroidImageDecoder implements ImageDecoder {

    private static final byte[] JP2_SIGNATURE = {0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A,
            (byte) 0x87, 0x0A};

    @Override
    public ImageBuffer decode(FaceFrame frame) throws ImageDecodeException {
        if (frame == null) {
            throw new ImageDecodeException("no frame");
        }
        PixelFormat format = frame.format();
        try {
            if (format.isEncoded()) {
                return fromBitmap(decodeBitmap(frame.data()), frame.rotationDegrees(), frame.isMirrored());
            }
            if (format == PixelFormat.NV21 || format.isIntPacked()) {
                throw new ImageDecodeException("unsupported frame format " + format);
            }
            return PixelOrientation.toUprightRgb(frame.data(), format, frame.width(), frame.height(),
                    frame.rotationDegrees(), frame.isMirrored());
        } catch (IllegalArgumentException e) {
            throw new ImageDecodeException("frame does not match its size or format: " + e.getMessage(), e);
        }
    }

    @Override
    public ImageBuffer decode(byte[] encoded) throws ImageDecodeException {
        return fromBitmap(decodeBitmap(encoded), 0, false);
    }

    /**
     * Decodes JPEG, PNG or JPEG 2000 bytes to a bitmap.
     *
     * @param encoded file bytes
     * @return the bitmap; the caller recycles it
     * @throws ImageDecodeException if the bytes are empty, corrupt or in another format
     */
    public static Bitmap decodeBitmap(byte[] encoded) throws ImageDecodeException {
        if (encoded == null || encoded.length == 0) {
            throw new ImageDecodeException("no image data");
        }
        Bitmap bitmap;
        try {
            bitmap = isJpeg2000(encoded) ? new JP2Decoder(encoded).decode()
                    : BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
        } catch (RuntimeException | LinkageError e) {
            throw new ImageDecodeException("image could not be decoded: " + e.getClass().getSimpleName(), e);
        }
        if (bitmap == null) {
            throw new ImageDecodeException("unsupported or unrecognised image format");
        }
        return bitmap;
    }

    /**
     * Tells whether bytes start with the JPEG 2000 file signature box or a JPEG 2000 codestream marker.
     *
     * @param data image bytes
     * @return {@code true} for JP2 files and raw J2K codestreams
     */
    public static boolean isJpeg2000(byte[] data) {
        if (data == null) {
            return false;
        }
        if (data.length >= JP2_SIGNATURE.length) {
            boolean jp2 = true;
            for (int i = 0; i < JP2_SIGNATURE.length && jp2; i++) {
                jp2 = data[i] == JP2_SIGNATURE[i];
            }
            if (jp2) {
                return true;
            }
        }
        return data.length >= 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0x4F
                && (data[2] & 0xFF) == 0xFF && (data[3] & 0xFF) == 0x51;
    }

    /**
     * Converts an upright RGB888 image to a bitmap (for JPEG 2000 encoding and for the replay preview).
     *
     * @param rgb the image
     * @return a new ARGB_8888 bitmap
     */
    public static Bitmap toBitmap(ImageBuffer rgb) {
        return Bitmap.createBitmap(PixelOrientation.toArgb(rgb), rgb.width(), rgb.height(), Bitmap.Config.ARGB_8888);
    }

    private static ImageBuffer fromBitmap(Bitmap bitmap, int rotation, boolean mirrored) {
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            return PixelOrientation.toUprightRgb(pixels, w, h, rotation, mirrored);
        } finally {
            bitmap.recycle();
        }
    }
}
