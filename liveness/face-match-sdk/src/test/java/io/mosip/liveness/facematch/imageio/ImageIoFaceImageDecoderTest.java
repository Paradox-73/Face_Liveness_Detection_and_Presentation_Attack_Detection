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
import io.mosip.liveness.facematch.SdkTestData;
import io.mosip.liveness.facematch.iso.FaceIsoRecord;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Desktop decoding of the images inside face records: JPEG 2000 (jai-imageio), JPEG and PNG. */
class ImageIoFaceImageDecoderTest {

    private final ImageIoFaceImageDecoder decoder = new ImageIoFaceImageDecoder();

    @Test
    void decodesTheJpeg2000InsideTheMockMdsRecords() throws Exception {
        FaceIsoRecord r = FaceIsoRecord.parse(SdkTestData.registrationFaceIso());
        assertTrue(ImageIoFaceImageDecoder.isJpeg2000(r.image()));
        ImageBuffer img = decoder.decode(r.image());
        assertEquals(r.width(), img.width());
        assertEquals(r.height(), img.height());
        assertEquals(PixelFormat.BGR888, img.format());
        assertEquals(img.width() * img.height() * 3, img.bytes().length);
    }

    @Test
    void decodesJpegAndPng() throws Exception {
        ImageBuffer jpeg = decoder.decode(SdkTestData.streamFrameJpeg());
        assertEquals(490, jpeg.width());
        assertEquals(360, jpeg.height());
        assertFalse(ImageIoFaceImageDecoder.isJpeg2000(SdkTestData.streamFrameJpeg()));

        BufferedImage grey = new BufferedImage(8, 4, BufferedImage.TYPE_BYTE_GRAY);
        grey.getRaster().setSample(1, 1, 0, 200);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(grey, "png", out);
        ImageBuffer png = decoder.decode(out.toByteArray());
        assertEquals(PixelFormat.BGR888, png.format());
        int i = (1 * 8 + 1) * 3;
        assertEquals(200, png.bytes()[i] & 0xff);
        assertEquals(200, png.bytes()[i + 2] & 0xff);
    }

    /** A tiny PNG whose header claims 30000 x 30000 pixels is refused before any pixel memory is allocated. */
    @Test
    void oversizedImageHeaderIsRefused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR), "png", out);
        byte[] png = out.toByteArray();
        // IHDR data starts at byte 16 (8 signature + 4 length + 4 type): width, height, then the CRC at 29.
        ByteBuffer.wrap(png, 16, 8).putInt(30_000).putInt(30_000);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png, 29, 4).putInt((int) crc.getValue());
        ImageDecodeException e = assertThrows(ImageDecodeException.class, () -> decoder.decode(png));
        assertTrue(e.getMessage().contains("30000 x 30000"), e.getMessage());
    }

    @Test
    void unreadableDataFailsWithADecodeException() {
        ImageDecodeException e = assertThrows(ImageDecodeException.class,
                () -> decoder.decode(new byte[] {1, 2, 3, 4}));
        assertEquals("unsupported face image format", e.getMessage());
        assertThrows(ImageDecodeException.class, () -> decoder.decode(new byte[] {(byte) 0xff, (byte) 0xd8,
            (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F'}));
        assertTrue(ImageIoFaceImageDecoder.isJpeg2000(new byte[] {(byte) 0xff, 0x4f, (byte) 0xff, 0x51, 0}));
        assertFalse(ImageIoFaceImageDecoder.isJpeg2000(new byte[] {0}));
    }
}
