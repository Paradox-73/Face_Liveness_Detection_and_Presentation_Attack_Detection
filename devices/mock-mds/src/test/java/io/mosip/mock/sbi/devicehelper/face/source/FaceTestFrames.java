/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.source;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Builds changing test frames from the stock stream image, so tests need no
 * binary fixtures.
 */
public final class FaceTestFrames {
	/** The stock face stream image, relative to the module folder. */
	public static final Path STOCK_IMAGE = Path.of("Biometric Devices", "Face", "Stream Image", "0.jpeg");

	private FaceTestFrames() {
	}

	/**
	 * Writes {@code count} JPEG frames named frame_0000.jpg ... into a folder.
	 * Each frame is the stock image with a marker bar at a different position.
	 *
	 * @param folder target folder, created if missing
	 * @param count  number of frames
	 * @return the written files in order
	 * @throws IOException if writing fails
	 */
	public static List<Path> writeSequence(Path folder, int count) throws IOException {
		Files.createDirectories(folder);
		BufferedImage base = ImageIO.read(STOCK_IMAGE.toFile());
		List<Path> files = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Path file = folder.resolve(String.format("frame_%04d.jpg", i));
			Files.write(file, toJpeg(frame(base, i, count)));
			files.add(file);
		}
		return files;
	}

	/**
	 * Draws frame {@code index}: the base image plus a moving bar and a border.
	 *
	 * @param base  base image
	 * @param index frame index
	 * @param count total frames
	 * @return the frame
	 */
	public static BufferedImage frame(BufferedImage base, int index, int count) {
		BufferedImage image = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.drawImage(base, 0, 0, null);
		int x = (int) ((long) (base.getWidth() - 40) * index / Math.max(1, count - 1));
		g.setColor(new Color(20 + (index * 37) % 200, 60, 200));
		g.fillRect(x, 10, 40, 30);
		g.setStroke(new BasicStroke(4));
		g.setColor(Color.YELLOW);
		g.drawRect(2 + index % 5, 2 + index % 5, base.getWidth() - 12, base.getHeight() - 12);
		g.dispose();
		return image;
	}

	/**
	 * Encodes an image as JPEG.
	 *
	 * @param image the image
	 * @return JPEG bytes
	 * @throws IOException if encoding fails
	 */
	public static byte[] toJpeg(BufferedImage image) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "jpg", out))
			throw new IOException("no JPEG writer");
		return out.toByteArray();
	}

	/**
	 * Decodes image bytes.
	 *
	 * @param bytes encoded image
	 * @return the image, or null if the bytes are not a decodable image
	 */
	public static BufferedImage decode(byte[] bytes) {
		try {
			return ImageIO.read(new ByteArrayInputStream(bytes));
		} catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Mean absolute difference per colour channel between two images of equal
	 * size, in grey levels (0..255).
	 *
	 * @param a first image
	 * @param b second image
	 * @return the mean absolute difference
	 */
	public static double meanAbsDiff(BufferedImage a, BufferedImage b) {
		if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
			throw new IllegalArgumentException("sizes differ");
		long sum = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				int p = a.getRGB(x, y);
				int q = b.getRGB(x, y);
				for (int shift = 0; shift <= 16; shift += 8)
					sum += Math.abs(((p >> shift) & 0xFF) - ((q >> shift) & 0xFF));
			}
		}
		return sum / (3.0 * a.getWidth() * a.getHeight());
	}
}
