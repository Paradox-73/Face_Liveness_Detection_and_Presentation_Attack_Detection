/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.mosip.mock.sbi.devicehelper.face.fault.FaceFaultInjector;
import io.mosip.mock.sbi.devicehelper.face.source.FaceSourceSettings;
import io.mosip.mock.sbi.devicehelper.face.source.FaceSourceType;
import io.mosip.mock.sbi.devicehelper.face.source.FaceTestFrames;

/** T-MOCK: a SEQUENCE switched back to with resume continues where it stopped. */
class FaceFrameFeedTest {

	@TempDir
	Path tempDir;

	private static FaceSourceSettings sequence(Path folder) {
		FaceSourceSettings s = new FaceSourceSettings();
		s.setType(FaceSourceType.SEQUENCE);
		s.setPath(folder.toString());
		s.setFps(15);
		return s;
	}

	private static void assertFrame(Path expected, FaceFrameFeed feed) throws IOException {
		assertArrayEquals(Files.readAllBytes(expected), feed.nextFrame().getJpeg());
	}

	@Test
	void resumeContinuesAReplacedSequenceAndPlainSwitchRestartsIt() throws IOException {
		List<Path> main = FaceTestFrames.writeSequence(tempDir.resolve("main"), 6);
		List<Path> action = FaceTestFrames.writeSequence(tempDir.resolve("action"), 2);
		FaceFrameFeed feed = new FaceFrameFeed(sequence(tempDir.resolve("main")), tempDir, null,
				new FaceFaultInjector());
		assertTrue(feed.open());
		assertFrame(main.get(0), feed);
		assertFrame(main.get(1), feed);

		feed.setSource(sequence(tempDir.resolve("action")), true);
		assertFrame(action.get(0), feed);
		feed.setSource(sequence(tempDir.resolve("main")), true);
		assertFrame(main.get(2), feed);

		feed.setSource(sequence(tempDir.resolve("action")), false);
		feed.setSource(sequence(tempDir.resolve("main")), false);
		assertFrame(main.get(0), feed);

		feed.setSource(sequence(tempDir.resolve("action")), true);
		FaceSourceSettings faster = sequence(tempDir.resolve("main"));
		faster.setFps(30);
		feed.setSource(faster, true);
		assertFrame(main.get(0), feed);

		feed.close();
		assertTrue(feed.open());
		feed.setSource(sequence(tempDir.resolve("action")), true);
		assertFrame(action.get(0), feed);
		feed.close();
	}
}
