/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Decodes JPEG and PNG frames with {@link ImageIO} into BGR888 buffers, the same decoder family the desktop client
 * uses, so the runner measures the desktop decode cost.
 *
 * <p>Only encoded frames are supported: frame packs are folders of JPEG files. Frames are assumed upright and not
 * mirrored, as recorded.
 */
final class ImageIoDecoder implements ImageDecoder {

    static {
        // No disk cache: decoding stays in memory and leaves no temporary files.
        ImageIO.setUseCache(false);
    }

    @Override
    public ImageBuffer decode(FaceFrame frame) throws ImageDecodeException {
        if (!frame.format().isEncoded()) {
            throw new ImageDecodeException("only encoded frames are supported, got " + frame.format());
        }
        return decode(frame.data());
    }

    @Override
    public ImageBuffer decode(byte[] encoded) throws ImageDecodeException {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(encoded));
        } catch (IOException | RuntimeException e) {
            throw new ImageDecodeException("image could not be decoded: " + e.getClass().getSimpleName());
        }
        if (image == null) {
            throw new ImageDecodeException("unsupported or corrupt image");
        }
        return toBgr(image);
    }

    /**
     * Converts a decoded image to a tightly packed BGR888 buffer.
     *
     * @param image any decoded image
     * @return the buffer; its pixel array is not shared with {@code image} unless the image was already BGR
     */
    static ImageBuffer toBgr(BufferedImage image) {
        BufferedImage bgr = image;
        if (image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            bgr = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = bgr.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        return ImageBuffer.ofBytes(bgr.getWidth(), bgr.getHeight(), PixelFormat.BGR888, pixels);
    }
}
