package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.Test;

import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;

/** Tests {@link DesktopImageDecoder}: JPEG and PNG to BGR888, rotation, raw frames and bad input. */
public class DesktopImageDecoderTest {

	private final DesktopImageDecoder decoder = new DesktopImageDecoder();

	@Test
	public void jpegDecodesToBgr() throws Exception {
		ImageBuffer img = decoder.decode(LivenessFakes.jpeg(64, 48, 1));
		assertEquals(64, img.width());
		assertEquals(48, img.height());
		assertEquals(PixelFormat.BGR888, img.format());
	}

	@Test
	public void pngKeepsExactPixels() throws Exception {
		BufferedImage src = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
		src.setRGB(0, 0, 0x102030);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(src, "png", out);
		ImageBuffer img = decoder.decode(out.toByteArray());
		byte[] px = img.bytes();
		assertEquals(0x30, px[0] & 0xff);
		assertEquals(0x20, px[1] & 0xff);
		assertEquals(0x10, px[2] & 0xff);
	}

	@Test
	public void rotatedFrameIsTurnedUpright() throws Exception {
		FaceFrame frame = FaceFrame.builder().data(LivenessFakes.jpeg(64, 48, 2)).format(PixelFormat.JPEG)
				.size(64, 48).rotationDegrees(90).monotonicNanos(1).sequence(0).sourceId("s").build();
		ImageBuffer img = decoder.decode(frame);
		assertEquals(48, img.width());
		assertEquals(64, img.height());
	}

	@Test
	public void rawFramePassesThrough() throws Exception {
		FaceFrame frame = FaceFrame.builder().data(new byte[4 * 2 * 3]).format(PixelFormat.BGR888).size(4, 2)
				.monotonicNanos(1).sequence(0).sourceId("s").build();
		assertEquals(4, decoder.decode(frame).width());
	}

	@Test(expected = ImageDecodeException.class)
	public void garbageIsRejected() throws Exception {
		decoder.decode(new byte[] { 1, 2, 3, 4, 5 });
	}

	@Test(expected = ImageDecodeException.class)
	public void emptyInputIsRejected() throws Exception {
		decoder.decode(new byte[0]);
	}
}
