/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.SampleModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.springframework.stereotype.Component;

import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;

/**
 * {@link ImageDecoder} of the desktop client, based on {@code javax.imageio}.
 *
 * <p>Encoded frames (JPEG, PNG and, through the JPEG 2000 plug-in the client already ships, JPEG 2000) are decoded
 * to {@link PixelFormat#BGR888}, which the ONNX adapters wrap without a copy. Raw frames are passed through. The
 * result is rotated by {@link FaceFrame#rotationDegrees()} so it is upright.
 *
 * <p>Images are read through a {@link MemoryCacheImageInputStream}: ImageIO's default file cache would otherwise
 * write face images to the temporary folder. Thread-safe.
 */
@Component
public class DesktopImageDecoder implements ImageDecoder {

	private static final int[] BGR_OFFSETS = { 2, 1, 0 };

	@Override
	public ImageBuffer decode(FaceFrame frame) throws ImageDecodeException {
		if (frame == null) {
			throw new ImageDecodeException("no frame");
		}
		ImageBuffer upright = decodeFrame(frame);
		return rotate(upright, frame.rotationDegrees());
	}

	@Override
	public ImageBuffer decode(byte[] encoded) throws ImageDecodeException {
		if (encoded == null || encoded.length == 0) {
			throw new ImageDecodeException("no image data");
		}
		return toBgr(read(encoded));
	}

	/**
	 * Converts a decoded image (for example the one inside a signed ISO/IEC 19794-5 record) to BGR888.
	 *
	 * @param image the image
	 * @return the pixel buffer; shares the image's array when it already is 3-byte BGR
	 * @throws ImageDecodeException if the image is {@code null} or empty
	 */
	public ImageBuffer toBgr(BufferedImage image) throws ImageDecodeException {
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
			throw new ImageDecodeException("empty image");
		}
		byte[] direct = directBgrBytes(image);
		if (direct != null) {
			return ImageBuffer.ofBytes(image.getWidth(), image.getHeight(), PixelFormat.BGR888, direct);
		}
		BufferedImage bgr = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = bgr.createGraphics();
		try {
			g.drawImage(image, 0, 0, null);
		} finally {
			g.dispose();
		}
		byte[] data = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
		return ImageBuffer.ofBytes(image.getWidth(), image.getHeight(), PixelFormat.BGR888, data);
	}

	private ImageBuffer decodeFrame(FaceFrame frame) throws ImageDecodeException {
		PixelFormat format = frame.format();
		if (format.isEncoded()) {
			return toBgr(read(frame.data()));
		}
		if (format == PixelFormat.NV21) {
			throw new ImageDecodeException("NV21 frames are not produced by desktop devices");
		}
		try {
			return ImageBuffer.ofBytes(frame.width(), frame.height(), format, frame.data());
		} catch (IllegalArgumentException e) {
			throw new ImageDecodeException("raw frame does not match its size: " + e.getMessage(), e);
		}
	}

	private static BufferedImage read(byte[] encoded) throws ImageDecodeException {
		// ImageIO.read(ImageInputStream) closes the stream itself when it finds a reader; closing it again throws
		// "closed", so the stream is closed here only when no reader was found. It holds only a byte array.
		ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded));
		BufferedImage image;
		try {
			image = ImageIO.read(in);
		} catch (IOException | RuntimeException e) {
			throw new ImageDecodeException("image could not be decoded: " + e.getClass().getSimpleName(), e);
		}
		if (image == null) {
			closeQuietly(in);
			throw new ImageDecodeException("unsupported or unrecognised image format");
		}
		return image;
	}

	private static void closeQuietly(ImageInputStream in) {
		try {
			in.close();
		} catch (IOException e) {
			// In-memory stream; nothing to release.
		}
	}

	/** Returns the image's own pixel array when it is a plain, unshared 3-byte BGR raster. */
	private static byte[] directBgrBytes(BufferedImage image) {
		if (image.getType() != BufferedImage.TYPE_3BYTE_BGR || image.getRaster().getParent() != null
				|| image.getRaster().getMinX() != 0 || image.getRaster().getMinY() != 0) {
			return null;
		}
		SampleModel sm = image.getSampleModel();
		if (!(sm instanceof ComponentSampleModel) || !(image.getRaster().getDataBuffer() instanceof DataBufferByte)) {
			return null;
		}
		ComponentSampleModel csm = (ComponentSampleModel) sm;
		byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
		boolean plain = csm.getPixelStride() == 3 && csm.getScanlineStride() == image.getWidth() * 3
				&& Arrays.equals(csm.getBandOffsets(), BGR_OFFSETS)
				&& data.length == image.getWidth() * image.getHeight() * 3;
		return plain ? data : null;
	}

	private static ImageBuffer rotate(ImageBuffer image, int degrees) throws ImageDecodeException {
		if (degrees == 0) {
			return image;
		}
		int channels = image.format().requiredLength(1, 1);
		if (image.isIntBacked() || image.format().isEncoded() || channels <= 0) {
			throw new ImageDecodeException("cannot rotate " + image.format());
		}
		int w = image.width();
		int h = image.height();
		boolean swap = degrees == 90 || degrees == 270;
		int ow = swap ? h : w;
		int oh = swap ? w : h;
		byte[] src = image.bytes();
		byte[] dst = new byte[src.length];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int nx;
				int ny;
				if (degrees == 90) {
					nx = h - 1 - y;
					ny = x;
				} else if (degrees == 180) {
					nx = w - 1 - x;
					ny = h - 1 - y;
				} else {
					nx = y;
					ny = w - 1 - x;
				}
				System.arraycopy(src, (y * w + x) * channels, dst, (ny * ow + nx) * channels, channels);
			}
		}
		return ImageBuffer.ofBytes(ow, oh, image.format(), dst);
	}
}
