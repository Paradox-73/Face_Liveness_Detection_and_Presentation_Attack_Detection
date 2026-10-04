/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch.iso;

import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The first 2-D face image of an ISO/IEC 19794-5 face record, as MOSIP devices sign it in the {@code bioValue} of an
 * RCAPTURE response and as the Registration Clients store it in the BIR {@code BDB}.
 *
 * <p>Both editions are read: 19794-5:2011 (version {@code "030"}, what MockMDS and MOSIP's biometrics-util write)
 * and 19794-5:2005 (version {@code "010"}). Only the fields needed to find the image are interpreted: the image
 * data type, width, height, the first quality score and the image bytes. The image itself (JPEG, JPEG 2000 or PNG)
 * is decoded by the platform, not here, so this class runs unchanged on the desktop and on Android.
 *
 * <p>Every length is checked against the data, so a truncated or inconsistent record fails with
 * {@link FaceIsoFormatException} instead of reading outside the array.
 */
public final class FaceIsoRecord {

    /** Record edition. */
    public enum Edition {
        /** ISO/IEC 19794-5:2005, version {@code "010"}. */
        ISO_19794_5_2005,
        /** ISO/IEC 19794-5:2011, version {@code "030"}. */
        ISO_19794_5_2011
    }

    private static final byte[] FORMAT_ID = {'F', 'A', 'C', 0};
    private static final int FIRST_3D_IMAGE_TYPE = 0x80;
    private static final int QUALITY_BLOCK_BYTES = 5;
    private static final int LANDMARK_BYTES = 8;

    private final Edition edition;
    private final int faceImageType;
    private final int imageDataType;
    private final int width;
    private final int height;
    private final int quality;
    private final byte[] image;

    private FaceIsoRecord(Edition edition, int faceImageType, int imageDataType, int width, int height, int quality,
                          byte[] image) {
        this.edition = edition;
        this.faceImageType = faceImageType;
        this.imageDataType = imageDataType;
        this.width = width;
        this.height = height;
        this.quality = quality;
        this.image = image;
    }

    /**
     * Tells whether data starts like an ISO/IEC 19794-5 face record ({@code "FAC\0"}).
     *
     * @param data bytes to test; may be {@code null}
     * @return {@code true} if the format identifier matches
     */
    public static boolean isFaceRecord(byte[] data) {
        if (data == null || data.length < FORMAT_ID.length) {
            return false;
        }
        for (int i = 0; i < FORMAT_ID.length; i++) {
            if (data[i] != FORMAT_ID[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the first 2-D face image of a record.
     *
     * @param data the record; not modified
     * @return the image and its header fields
     * @throws FaceIsoFormatException if the data is not a face record, is truncated, uses an unknown version or
     *                                holds only 3-D images
     */
    public static FaceIsoRecord parse(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (!isFaceRecord(data)) {
            throw new FaceIsoFormatException("not an ISO/IEC 19794-5 face record");
        }
        ByteBuffer in = ByteBuffer.wrap(data);
        try {
            seek(in, FORMAT_ID.length);
            byte[] version = new byte[4];
            in.get(version);
            String v = new String(version, 0, 3, StandardCharsets.US_ASCII);
            long recordLength = u32(in);
            if (recordLength > data.length || recordLength < in.position()) {
                throw new FaceIsoFormatException("record length " + recordLength + " does not fit " + data.length
                        + " bytes");
            }
            if ("030".equals(v) && version[3] == 0) {
                return parse2011(in, recordLength);
            }
            if ("010".equals(v) && version[3] == 0) {
                return parse2005(in, recordLength);
            }
            throw new FaceIsoFormatException("unsupported face record version " + v);
        } catch (FaceIsoFormatException e) {
            throw e;
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            // ByteBuffer reports a read or position past the end this way.
            throw new FaceIsoFormatException("truncated face record", e);
        }
    }

    private static FaceIsoRecord parse2011(ByteBuffer in, long recordLength) {
        int representations = u16(in);
        in.get();          // certification flag
        u16(in);           // temporal semantics
        for (int r = 0; r < representations; r++) {
            int start = in.position();
            long length = u32(in);
            if (length < 4 || start + length > recordLength) {
                throw new FaceIsoFormatException("representation length " + length + " does not fit the record");
            }
            FaceIsoRecord record = representation2011(in, start + (int) length);
            if (record != null) {
                return record;
            }
            seek(in, start + (int) length);
        }
        throw new FaceIsoFormatException("the record has no 2-D face image");
    }

    private static FaceIsoRecord representation2011(ByteBuffer in, int end) {
        skip(in, 9 + 1 + 2 + 2);  // capture date and time, device technology, vendor and type
        int qualityBlocks = u8(in);
        int quality = -1;
        for (int q = 0; q < qualityBlocks; q++) {
            int score = u8(in);
            quality = q == 0 ? score : quality;
            skip(in, QUALITY_BLOCK_BYTES - 1);
        }
        int landmarks = u16(in);
        skip(in, 1 + 1 + 1 + 1 + 3 + 2 + 6);  // gender, eyes, hair, height, property mask, expression, pose
        skip(in, landmarks * LANDMARK_BYTES);
        int faceImageType = u8(in);
        int imageDataType = u8(in);
        int width = u16(in);
        int height = u16(in);
        skip(in, 1 + 2 + 1 + 1);  // spatial sampling rate, post-acquisition processing, cross reference, colour space
        if (faceImageType >= FIRST_3D_IMAGE_TYPE) {
            return null;
        }
        long imageLength = u32(in);
        if (imageLength <= 0 || in.position() + imageLength > end) {
            throw new FaceIsoFormatException("image length " + imageLength + " does not fit the representation");
        }
        return new FaceIsoRecord(Edition.ISO_19794_5_2011, faceImageType, imageDataType, width, height, quality,
                bytes(in, (int) imageLength));
    }

    private static FaceIsoRecord parse2005(ByteBuffer in, long recordLength) {
        int images = u16(in);
        if (images < 1) {
            throw new FaceIsoFormatException("the record has no face image");
        }
        int start = in.position();
        long blockLength = u32(in);
        if (start + blockLength > recordLength) {
            throw new FaceIsoFormatException("facial record length " + blockLength + " does not fit the record");
        }
        int featurePoints = u16(in);
        skip(in, 1 + 1 + 1 + 3 + 2 + 3 + 3);  // gender, eyes, hair, feature mask, expression, pose, uncertainty
        skip(in, featurePoints * LANDMARK_BYTES);
        int faceImageType = u8(in);
        int imageDataType = u8(in);
        int width = u16(in);
        int height = u16(in);
        skip(in, 1 + 1 + 2);  // colour space, source type, device type
        int quality = u16(in);
        long imageLength = start + blockLength - in.position();
        if (imageLength <= 0) {
            throw new FaceIsoFormatException("the facial record holds no image data");
        }
        return new FaceIsoRecord(Edition.ISO_19794_5_2005, faceImageType, imageDataType, width, height, quality,
                bytes(in, (int) imageLength));
    }

    private static int u8(ByteBuffer in) {
        return in.get() & 0xff;
    }

    private static int u16(ByteBuffer in) {
        return in.getShort() & 0xffff;
    }

    private static long u32(ByteBuffer in) {
        return in.getInt() & 0xffffffffL;
    }

    private static void skip(ByteBuffer in, int n) {
        seek(in, in.position() + n);
    }

    private static void seek(ByteBuffer in, int position) {
        // Through Buffer: the covariant ByteBuffer.position(int) of Java 9+ is missing on Android API 28.
        ((Buffer) in).position(position);
    }

    private static byte[] bytes(ByteBuffer in, int n) {
        byte[] out = new byte[n];
        in.get(out);
        return out;
    }

    /**
     * Returns the record edition.
     *
     * @return 2005 or 2011
     */
    public Edition edition() {
        return edition;
    }

    /**
     * Returns the face image type code (0 basic, 1 full frontal, 2 token frontal).
     *
     * @return the code from the record
     */
    public int faceImageType() {
        return faceImageType;
    }

    /**
     * Returns the image data type code: 0 JPEG, 1 JPEG 2000 (lossy in the 2011 edition), 2 JPEG 2000 lossless and
     * 3 PNG (2011 edition). Decoders identify the format from the image bytes, so this is informational.
     *
     * @return the code from the record
     */
    public int imageDataType() {
        return imageDataType;
    }

    /**
     * Returns the image width declared in the record.
     *
     * @return pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the image height declared in the record.
     *
     * @return pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns the first quality score of the record.
     *
     * @return 0-100 (2011), the raw 16-bit value (2005), or -1 if the record has none
     */
    public int quality() {
        return quality;
    }

    /**
     * Returns the encoded image (JPEG, JPEG 2000 or PNG). Biometric data: never log it.
     *
     * @return a copy of the image bytes
     */
    public byte[] image() {
        return image.clone();
    }

    /**
     * Returns the size of the encoded image.
     *
     * @return bytes
     */
    public int imageLength() {
        return image.length;
    }

    @Override
    public String toString() {
        return "FaceIsoRecord[" + edition + ", type=" + faceImageType + ", dataType=" + imageDataType + ", " + width
                + "x" + height + ", quality=" + quality + ", imageBytes=" + image.length + "]";
    }
}
