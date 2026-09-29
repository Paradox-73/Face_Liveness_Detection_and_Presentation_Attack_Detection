package io.mosip.registration.livenessmanager.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;

import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AndroidImageDecoderTest {

    private final AndroidImageDecoder decoder = new AndroidImageDecoder();

    @Test
    public void rgbaCameraFrameIsRotatedUpright() throws Exception {
        // 4x2 RGBA frame from a sensor mounted at 90 degrees: the upright image is 2x4.
        byte[] rgba = new byte[4 * 2 * 4];
        rgba[0] = (byte) 200; // top-left pixel red
        FaceFrame frame = FaceFrame.builder().data(rgba).format(PixelFormat.RGBA8888).size(4, 2).rotationDegrees(90)
                .sequence(1).sourceId("test").build();
        ImageBuffer image = decoder.decode(frame);
        assertEquals(PixelFormat.RGB888, image.format());
        assertEquals(2, image.width());
        assertEquals(4, image.height());
        // After a clockwise turn the source's top-left pixel is the top-right pixel.
        assertEquals((byte) 200, image.bytes()[3]);
    }

    @Test
    public void jpegAndPngFramesAreDecoded() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF2040C0);
        for (Bitmap.CompressFormat f : new Bitmap.CompressFormat[] {Bitmap.CompressFormat.JPEG,
                Bitmap.CompressFormat.PNG}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(f, 95, out));
            ImageBuffer image = decoder.decode(out.toByteArray());
            assertEquals(64, image.width());
            assertEquals(48, image.height());
            int blue = image.bytes()[2] & 0xFF;
            assertTrue("blue channel kept for " + f + ": " + blue, blue > 150);
        }
    }

    @Test
    public void mirroredEncodedFrameIsFlipped() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888);
        bitmap.setPixel(0, 0, 0xFFFF0000);
        bitmap.setPixel(1, 0, 0xFF0000FF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        FaceFrame frame = FaceFrame.builder().data(out.toByteArray()).format(PixelFormat.PNG).mirrored(true)
                .sequence(1).sourceId("test").build();
        ImageBuffer image = decoder.decode(frame);
        assertEquals((byte) 0xFF, image.bytes()[2]);  // left pixel is now the blue one
        assertEquals((byte) 0xFF, image.bytes()[3]);  // right pixel is now the red one
    }

    @Test
    public void recognisesJpeg2000Signatures() {
        assertTrue(AndroidImageDecoder.isJpeg2000(new byte[] {0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D,
                0x0A, (byte) 0x87, 0x0A, 0x00}));
        assertTrue(AndroidImageDecoder.isJpeg2000(new byte[] {(byte) 0xFF, 0x4F, (byte) 0xFF, 0x51}));
        assertFalse(AndroidImageDecoder.isJpeg2000(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        assertFalse(AndroidImageDecoder.isJpeg2000(null));
    }

    @Test
    public void corruptDataFailsWithDecodeException() {
        try {
            decoder.decode(new byte[] {1, 2, 3, 4, 5});
            fail("expected ImageDecodeException");
        } catch (ImageDecodeException expected) {
            // fails closed
        }
        try {
            decoder.decode(new byte[0]);
            fail("expected ImageDecodeException");
        } catch (ImageDecodeException expected) {
            // fails closed
        }
    }
}
