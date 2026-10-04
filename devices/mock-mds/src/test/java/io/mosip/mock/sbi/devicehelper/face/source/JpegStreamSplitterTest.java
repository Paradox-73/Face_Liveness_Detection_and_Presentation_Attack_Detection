/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class JpegStreamSplitterTest {

	@Test
	void splitsBackToBackJpegsAndSkipsGarbage() throws IOException {
		byte[] first = Files.readAllBytes(FaceTestFrames.STOCK_IMAGE);
		byte[] second = FaceTestFrames.toJpeg(
				FaceTestFrames.frame(FaceTestFrames.decode(first), 3, 10));
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		stream.write("noise before".getBytes(StandardCharsets.US_ASCII));
		stream.write(first);
		stream.write(new byte[] { 0x00, (byte) 0xFF, 0x12 });
		stream.write(second);

		JpegStreamSplitter splitter = new JpegStreamSplitter(1 << 24);
		ByteArrayInputStream in = new ByteArrayInputStream(stream.toByteArray());
		assertArrayEquals(first, splitter.readNext(in));
		assertArrayEquals(second, splitter.readNext(in));
		assertNull(splitter.readNext(in));
	}

	@Test
	void endMarkerInsideMetadataSegmentDoesNotCutTheImage() throws IOException {
		byte[] jpeg = Files.readAllBytes(FaceTestFrames.STOCK_IMAGE);
		// APP1 segment whose payload contains FF D9, like an embedded thumbnail
		byte[] app1 = { (byte) 0xFF, (byte) 0xE1, 0x00, 0x08, 'x', (byte) 0xFF, (byte) 0xD9, 'y', 'z', 'w' };
		byte[] withApp = new byte[jpeg.length + app1.length];
		withApp[0] = (byte) 0xFF;
		withApp[1] = (byte) 0xD8;
		System.arraycopy(app1, 0, withApp, 2, app1.length);
		System.arraycopy(jpeg, 2, withApp, 2 + app1.length, jpeg.length - 2);

		byte[] result = new JpegStreamSplitter(1 << 24).readNext(new ByteArrayInputStream(withApp));
		assertArrayEquals(withApp, result);
	}

	@Test
	void imageCutOffByEndOfStreamIsDropped() throws IOException {
		byte[] jpeg = Files.readAllBytes(FaceTestFrames.STOCK_IMAGE);
		byte[] cut = Arrays.copyOf(jpeg, jpeg.length / 2);
		assertNull(new JpegStreamSplitter(1 << 24).readNext(new ByteArrayInputStream(cut)));
	}

	@Test
	void oversizedImageIsSkippedAndNextImageReturned() throws IOException {
		byte[] jpeg = Files.readAllBytes(FaceTestFrames.STOCK_IMAGE);
		byte[] small = FaceTestFrames.toJpeg(new java.awt.image.BufferedImage(8, 8,
				java.awt.image.BufferedImage.TYPE_INT_RGB));
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		stream.write(jpeg);
		stream.write(small);
		JpegStreamSplitter splitter = new JpegStreamSplitter(small.length + 16);
		assertArrayEquals(small, splitter.readNext(new ByteArrayInputStream(stream.toByteArray())));
	}
}
