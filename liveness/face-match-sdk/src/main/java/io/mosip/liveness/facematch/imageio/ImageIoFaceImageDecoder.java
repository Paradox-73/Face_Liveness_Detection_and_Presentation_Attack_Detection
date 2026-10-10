/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch.imageio;

import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.facematch.FaceImageDecoder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;

/**
 * Desktop {@link FaceImageDecoder} on {@code javax.imageio}: JPEG and PNG with the JDK readers, JPEG 2000 with any
 * ImageIO JPEG 2000 reader on the class path. The desktop Registration Client ships
 * {@code com.github.jai-imageio:jai-imageio-jpeg2000:1.3.0}, which registers one; this module does not bundle it.
 *
 * <p>Images are read through an in-memory stream, so ImageIO never writes a cache file with biometric data to
 * the temporary directory. The result is {@link PixelFormat#BGR888}. Thread-safe.
 */
public final class ImageIoFaceImageDecoder implements FaceImageDecoder {

    /** Largest accepted width or height: a small file whose header claims 30000 x 30000 would need gigabytes. */
    static final int MAX_SIDE = 4096;

    private static final byte[] JP2_SIGNATURE = {0, 0, 0, 0x0c, 'j', 'P', ' ', ' '};
    private static final byte[] J2K_CODESTREAM = {(byte) 0xff, 0x4f, (byte) 0xff, 0x51};

    /** Creates the decoder; the SDK instantiates it by name. */
    public ImageIoFaceImageDecoder() {
        // Stateless.
    }

    @Override
    public ImageBuffer decode(byte[] encoded) throws ImageDecodeException {
        Objects.requireNonNull(encoded, "encoded");
        BufferedImage image;
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new ImageDecodeException(isJpeg2000(encoded)
                        ? "no JPEG 2000 ImageIO reader on the class path (add jai-imageio-jpeg2000)"
                        : "unsupported face image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w > MAX_SIDE || h > MAX_SIDE) {
                    throw new ImageDecodeException("face image of " + w + " x " + h + " pixels is larger than "
                            + MAX_SIDE + " per side");
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new ImageDecodeException("the face image cannot be decoded", e);
        }
        return toBgr(image);
    }

    /**
     * Tells whether data is JPEG 2000 (JP2 file or raw codestream).
     *
     * @param data image bytes
     * @return {@code true} for JPEG 2000
     */
    static boolean isJpeg2000(byte[] data) {
        return startsWith(data, JP2_SIGNATURE) || startsWith(data, J2K_CODESTREAM);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static ImageBuffer toBgr(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage bgr = image;
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR || !(image.getRaster().getDataBuffer() instanceof DataBufferByte)
                || ((DataBufferByte) image.getRaster().getDataBuffer()).getData().length != w * h * 3) {
            bgr = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = bgr.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        return ImageBuffer.ofBytes(w, h, PixelFormat.BGR888, pixels);
    }

    @Override
    public String toString() {
        return "ImageIoFaceImageDecoder";
    }
}
