/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Makes a "low-confidence" copy of a bona fide frame pack for the desktop demo: every frame gets Gaussian sensor
 * noise, as a cheap camera shows in poor light. The person and the movements are unchanged, so blink and smile
 * are still measurable, but the passive PAD score of pack s3 drops from about 100 to 35-90 per frame (the session
 * score was 74 in the desktop demo): below the resident threshold (80) and above the reject threshold (20), so the
 * engine asks for challenges.
 * <p>
 * Usage: {@code java NoisyPack.java <source pack folder> <target pack folder> [sigma]} (default sigma 8 grey
 * levels). Each frame uses its own random seed (its index), so no two frames are identical and the stream does not
 * look frozen. The frames stay in the local run folder like the source packs; they are never committed.
 */
public final class NoisyPack {

	private static final double DEFAULT_SIGMA = 8.0;

	private NoisyPack() {
	}

	/**
	 * Writes the noisy copy.
	 *
	 * @param args source folder, target folder, optional sigma
	 * @throws IOException if a frame cannot be read or written
	 */
	public static void main(String[] args) throws IOException {
		File source = new File(args[0]);
		File target = new File(args[1]);
		double sigma = args.length > 2 ? Double.parseDouble(args[2]) : DEFAULT_SIGMA;
		File[] frames = source.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg"));
		if (frames == null || frames.length == 0) {
			throw new IOException("no JPEG frames in " + source);
		}
		Arrays.sort(frames);
		if (!target.isDirectory() && !target.mkdirs()) {
			throw new IOException("cannot create " + target);
		}
		for (int i = 0; i < frames.length; i++) {
			BufferedImage image = ImageIO.read(frames[i]);
			ImageIO.write(addNoise(image, sigma, new Random(i)), "jpg", new File(target, frames[i].getName()));
		}
		System.out.println(frames.length + " frames written to " + target);
	}

	private static BufferedImage addNoise(BufferedImage src, double sigma, Random random) {
		int w = src.getWidth();
		int h = src.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = src.getRGB(x, y);
				int n = (int) Math.round(random.nextGaussian() * sigma);
				out.setRGB(x, y, clamp(((p >> 16) & 0xff) + n) << 16 | clamp(((p >> 8) & 0xff) + n) << 8
						| clamp((p & 0xff) + n));
			}
		}
		return out;
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}
}
