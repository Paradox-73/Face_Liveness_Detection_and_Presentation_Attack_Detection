/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T-MOCK: frame sources produce the expected, changing frames. */
class FaceFrameSourcesTest {

	@TempDir
	Path tempDir;

	@Test
	void sequenceSourcePlaysFramesInNameOrderAndTheyChange() throws IOException {
		List<Path> files = FaceTestFrames.writeSequence(tempDir.resolve("seq"), 6);
		ImageSequenceSource source = new ImageSequenceSource(tempDir.resolve("seq"), 10, false);
		source.open();
		assertEquals(6, source.getFrameCount());
		Set<String> digests = new HashSet<>();
		for (int i = 0; i < files.size(); i++) {
			FaceFrame frame = source.next();
			assertNotNull(frame);
			assertEquals(i, frame.getSequence());
			assertArrayEquals(Files.readAllBytes(files.get(i)), frame.getJpeg());
			assertNotNull(FaceTestFrames.decode(frame.getJpeg()));
			digests.add(frame.sha256());
		}
		assertEquals(6, digests.size(), "every frame must differ");
		assertNull(source.next(), "without loop the source ends after the last frame");
		source.close();
	}

	@Test
	void sequenceSourceLoopsAndRestartsOnOpen() throws IOException {
		List<Path> files = FaceTestFrames.writeSequence(tempDir.resolve("loop"), 3);
		ImageSequenceSource source = new ImageSequenceSource(tempDir.resolve("loop"), 10, true);
		source.open();
		for (int i = 0; i < 7; i++)
			assertArrayEquals(Files.readAllBytes(files.get(i % 3)), source.next().getJpeg());
		source.close();
		source.open();
		assertArrayEquals(Files.readAllBytes(files.get(0)), source.next().getJpeg());
		source.close();
	}

	@Test
	void sequenceSourcePingpongPlaysBackwardsThenForwards() throws IOException {
		List<Path> files = FaceTestFrames.writeSequence(tempDir.resolve("pingpong"), 3);
		ImageSequenceSource source = new ImageSequenceSource(tempDir.resolve("pingpong"), 10, false, true);
		source.open();
		int[] order = { 0, 1, 2, 1, 0, 1, 2, 1 };
		for (int i : order)
			assertArrayEquals(Files.readAllBytes(files.get(i)), source.next().getJpeg());
		source.close();
	}

	@Test
	void sequenceSourceIgnoresNonJpegFilesAndRejectsEmptyFolder() throws IOException {
		Path folder = Files.createDirectories(tempDir.resolve("mixed"));
		Files.writeString(folder.resolve("notes.txt"), "not a frame");
		ImageSequenceSource empty = new ImageSequenceSource(folder, 10, true);
		assertThrows(IOException.class, empty::open);
		assertThrows(IOException.class, () -> new ImageSequenceSource(tempDir.resolve("missing"), 10, true).open());
	}

	@Test
	void staticSourceServesTheSameFileEveryFrame() throws IOException {
		StaticImageSource source = new StaticImageSource(FaceTestFrames.STOCK_IMAGE, 15);
		source.open();
		FaceFrame first = source.next();
		FaceFrame second = source.next();
		assertEquals(first.sha256(), second.sha256());
		assertArrayEquals(Files.readAllBytes(FaceTestFrames.STOCK_IMAGE), first.getJpeg());
		assertEquals(15.0, source.getFps());
		assertThrows(IOException.class, () -> new StaticImageSource(tempDir.resolve("none.jpg"), 15).open());
	}

	@Test
	void staticSourcePicksUpAReplacedFile() throws IOException {
		Path file = tempDir.resolve("live.jpg");
		Files.copy(FaceTestFrames.STOCK_IMAGE, file);
		StaticImageSource source = new StaticImageSource(file, 15);
		source.open();
		String before = source.next().sha256();
		Files.write(file, FaceTestFrames.toJpeg(
				FaceTestFrames.frame(FaceTestFrames.decode(Files.readAllBytes(file)), 1, 2)));
		assertNotEquals(before, source.next().sha256());
	}

	@Test
	void factoryCreatesEachTypeAndResolvesRelativePaths() throws IOException {
		FaceTestFrames.writeSequence(tempDir.resolve("rel"), 2);
		FaceSourceSettings settings = new FaceSourceSettings();
		settings.setType(FaceSourceType.SEQUENCE);
		settings.setPath("rel");
		FaceFrameSource sequence = FaceFrameSources.create(settings, tempDir, FaceTestFrames.STOCK_IMAGE);
		assertInstanceOf(ImageSequenceSource.class, sequence);
		sequence.open();
		assertNotNull(sequence.next());
		sequence.close();

		settings.setType(FaceSourceType.STATIC);
		settings.setPath(null);
		FaceFrameSource stat = FaceFrameSources.create(settings, tempDir, FaceTestFrames.STOCK_IMAGE);
		assertEquals(FaceTestFrames.STOCK_IMAGE.toString(), stat.describe().get("path"));

		settings.setType(FaceSourceType.FFMPEG);
		settings.setCamera("Integrated Camera");
		assertInstanceOf(FfmpegPipeSource.class, FaceFrameSources.create(settings, tempDir, FaceTestFrames.STOCK_IMAGE));

		settings.setType(FaceSourceType.SEQUENCE);
		settings.setPath(null);
		assertThrows(IllegalArgumentException.class,
				() -> FaceFrameSources.create(settings, tempDir, FaceTestFrames.STOCK_IMAGE));
	}

	@Test
	void settingsReadFromPropertiesAndValidate() {
		Map<String, String> props = Map.of(FaceSourceSettings.KEY_TYPE, "sequence", FaceSourceSettings.KEY_FPS, "12.5",
				FaceSourceSettings.KEY_PATH, "\"frames/live\"", FaceSourceSettings.KEY_LOOP, "false",
				FaceSourceSettings.KEY_INPUT_ARGS, "-f lavfi -i testsrc");
		FaceSourceSettings settings = FaceSourceSettings.fromProperties(props::get);
		assertEquals(FaceSourceType.SEQUENCE, settings.getType());
		assertEquals(12.5, settings.getFps());
		assertEquals("frames/live", settings.getPath());
		assertEquals(List.of("-f", "lavfi", "-i", "testsrc"), settings.getInputArgs());
		assertThrows(IllegalArgumentException.class, () -> settings.setFps(0));
		assertThrows(IllegalArgumentException.class, () -> settings.setFps(61));
		assertThrows(IllegalArgumentException.class, () -> FaceSourceType.parse("tape"));
		assertEquals(FaceSourceType.FFMPEG, FaceSourceType.parse("webcam"));
	}
}
