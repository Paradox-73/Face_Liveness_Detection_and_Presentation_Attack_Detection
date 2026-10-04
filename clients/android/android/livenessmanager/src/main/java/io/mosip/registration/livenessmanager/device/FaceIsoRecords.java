/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import android.graphics.Bitmap;

import com.gemalto.jp2.JP2Encoder;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Date;

import io.mosip.biometrics.util.face.FaceBDIR;
import io.mosip.biometrics.util.face.FaceEncoder;
import io.mosip.biometrics.util.face.FaceQualityBlock;
import io.mosip.liveness.core.device.DevicePurpose;

/**
 * Writes and reads the ISO/IEC 19794-5:2011 face record that SBI devices return and ARC stores in the packet.
 *
 * <p>The image inside is JPEG 2000 (JP2 file format), encoded with the JP2ForAndroid (OpenJPEG) library that ARC
 * already ships, at about 2 bits per pixel like MOSIP's mock device. The record is written with MOSIP
 * biometrics-util ({@code FaceEncoder.convertFaceImageToISO19794_5_2011}) with the same header values as its
 * {@code convertFaceImageToISO} (which cannot run on Android because it reads the image with
 * {@code java.awt}); only the image size is passed explicitly.
 */
public final class FaceIsoRecords {

    /** Compression ratio of the JPEG 2000 image: 24 bits per pixel / 12 = 2 bits per pixel. */
    static final float JP2_COMPRESSION_RATIO = 12f;

    private static final long FORMAT_IDENTIFIER = 0x46414300L;  // "FAC\0"
    private static final long VERSION_NUMBER = 0x30333000L;     // "030\0"
    private static final int IMAGE_DATA_TYPE_JPEG2000 = 1;
    private static final int PURPOSE_AUTH = 1;
    private static final int PURPOSE_REGISTRATION = 2;

    private FaceIsoRecords() {
    }

    /**
     * Encodes a bitmap as JPEG 2000 (JP2 file format).
     *
     * @param bitmap upright face image
     * @return the JP2 bytes
     * @throws IOException if the encoder fails
     */
    public static byte[] encodeJp2(Bitmap bitmap) throws IOException {
        byte[] jp2 = new JP2Encoder(bitmap).setOutputFormat(JP2Encoder.FORMAT_JP2)
                .setCompressionRatio(JP2_COMPRESSION_RATIO).encode();
        if (jp2 == null || jp2.length == 0) {
            throw new IOException("JPEG 2000 encoder returned no data");
        }
        return jp2;
    }

    /**
     * Wraps a JPEG 2000 image in an ISO/IEC 19794-5:2011 face record.
     *
     * @param jp2        JPEG 2000 image
     * @param width      image width in pixels
     * @param height     image height in pixels
     * @param purpose    registration or authentication capture
     * @param quality    quality score 0-100 for the quality block
     * @param capturedAt capture time
     * @return the record
     * @throws IOException if the record cannot be written
     */
    public static byte[] toIsoRecord(byte[] jp2, int width, int height, DevicePurpose purpose, int quality,
                                     Date capturedAt) throws IOException {
        int purposeCode = purpose == DevicePurpose.AUTH ? PURPOSE_AUTH : PURPOSE_REGISTRATION;
        FaceQualityBlock[] qualityBlocks = {new FaceQualityBlock(Math.max(0, Math.min(100, quality)), 1, 1)};
        // Argument order and values as in biometrics-util 1.3.0 FaceEncoder.convertFaceImageToISO.
        return FaceEncoder.convertFaceImageToISO19794_5_2011(FORMAT_IDENTIFIER, VERSION_NUMBER, 0, 0, capturedAt,
                1, 0, 255, 0, 0, 0, 0, 0, new int[] {0, 0, 0}, new int[] {0, 0, 0}, 1, 5, 0, 0, qualityBlocks,
                jp2, width, height, purposeCode, 0, 0, 0, IMAGE_DATA_TYPE_JPEG2000, null, null);
    }

    /**
     * Reads the image out of an ISO/IEC 19794-5 face record.
     *
     * @param isoRecord the record
     * @return the image bytes (JPEG 2000 for SBI devices)
     * @throws IOException if the record is malformed
     */
    public static byte[] imageOf(byte[] isoRecord) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(isoRecord))) {
            byte[] image = new FaceBDIR(in).getImage();
            if (image == null || image.length == 0) {
                throw new IOException("face record holds no image");
            }
            return image;
        } catch (RuntimeException e) {
            throw new IOException("face record is malformed", e);
        }
    }
}
