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
