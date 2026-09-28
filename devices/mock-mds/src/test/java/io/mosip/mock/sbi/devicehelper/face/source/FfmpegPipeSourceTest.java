package io.mosip.mock.sbi.devicehelper.face.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** T-MOCK: the ffmpeg source builds safe command lines and delivers changing frames. */
class FfmpegPipeSourceTest {

	@Test
	void webcamCommandUsesDshowOnWindows() {
		FaceSourceSettings settings = new FaceSourceSettings();
		settings.setType(FaceSourceType.FFMPEG);
		settings.setCamera("Integrated Camera");
		settings.setFps(10);
		List<String> cmd = FfmpegPipeSource.buildCommand(settings, "Windows 11");
		assertEquals("ffmpeg", cmd.get(0));
		assertTrue(cmd.containsAll(List.of("-f", "dshow", "video=Integrated Camera")));
		assertTrue(cmd.contains("fps=10,scale=640:-2"));
		assertEquals("pipe:1", cmd.get(cmd.size() - 1));
		assertTrue(cmd.containsAll(List.of("image2pipe", "mjpeg")));
		assertTrue(FfmpegPipeSource.buildCommand(settings, "Linux").containsAll(List.of("v4l2")));
		assertTrue(FfmpegPipeSource.buildCommand(settings, "Mac OS X").contains("avfoundation"));
	}

	@Test
	void fileCommandReadsAtNativeRateAndLoops() {
		FaceSourceSettings settings = new FaceSourceSettings();
		settings.setType(FaceSourceType.FFMPEG);
		settings.setFile("replay.mp4");
		settings.setFps(7.5);
		List<String> cmd = FfmpegPipeSource.buildCommand(settings, "Windows 11");
		int input = cmd.indexOf("-i");
		assertTrue(cmd.indexOf("-re") < input && cmd.indexOf("-stream_loop") < input);
		assertEquals("replay.mp4", cmd.get(input + 1));
		assertTrue(cmd.contains("fps=7.5,scale=640:-2"));
		settings.setLoop(false);
		assertFalse(FfmpegPipeSource.buildCommand(settings, "Linux").contains("-stream_loop"));
	}

	@Test
	void virtualAndMalformedCameraNamesAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> FfmpegPipeSource.checkCameraName("OBS Virtual Camera", false));
		assertThrows(IllegalArgumentException.class, () -> FfmpegPipeSource.checkCameraName("ManyCam Video Source", false));
		assertThrows(IllegalArgumentException.class, () -> FfmpegPipeSource.checkCameraName("cam\" -y out.jpg", true));
		FfmpegPipeSource.checkCameraName("OBS Virtual Camera", true);
		FfmpegPipeSource.checkCameraName("Integrated Camera", false);
		FaceSourceSettings settings = new FaceSourceSettings();
		assertThrows(IllegalArgumentException.class, () -> FfmpegPipeSource.buildCommand(settings, "Linux"),
				"no camera, file or input arguments");
	}

	@Test
	void missingExecutableFailsToOpen() {
		FaceSourceSettings settings = new FaceSourceSettings();
		settings.setType(FaceSourceType.FFMPEG);
		settings.setFfmpegPath("no-such-ffmpeg-binary");
		settings.setFile("clip.mp4");
		FfmpegPipeSource source = new FfmpegPipeSource(settings, 2000, 500);
		assertThrows(IOException.class, source::open);
	}

	@Test
	void liveTestPatternDeliversChangingDecodableFrames() throws Exception {
		assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH");
		FaceSourceSettings settings = new FaceSourceSettings();
		settings.setType(FaceSourceType.FFMPEG);
		settings.setFps(15);
		settings.setWidth(320);
		settings.setInputArgs(List.of("-re", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=15"));
		FfmpegPipeSource source = new FfmpegPipeSource(settings, 10_000, 2_000);
		source.open();
		try {
			Set<String> digests = new HashSet<>();
			for (int i = 0; i < 8; i++) {
				FaceFrame frame = source.next();
				assertNotNull(frame);
				BufferedImage image = FaceTestFrames.decode(frame.getJpeg());
				assertNotNull(image);
				assertEquals(320, image.getWidth());
				digests.add(frame.sha256());
			}
			assertTrue(digests.size() >= 6, "test pattern frames must change, got " + digests.size());
			assertTrue(source.isRunning());
		} finally {
			source.close();
		}
		assertFalse(source.isRunning());
		assertNull(source.next());
	}

	@Test
	void finiteInputEndsTheSource() throws Exception {
		assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH");
		FaceSourceSettings settings = new FaceSourceSettings();
		settings.setType(FaceSourceType.FFMPEG);
		settings.setFps(10);
		settings.setWidth(160);
		settings.setInputArgs(List.of("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10:duration=0.5"));
		FfmpegPipeSource source = new FfmpegPipeSource(settings, 10_000, 2_000);
		source.open();
		int frames = 0;
		while (source.next() != null && frames < 50)
			frames++;
		source.close();
		assertTrue(frames >= 1 && frames <= 6, "0.5 s at 10 fps, got " + frames);
	}

	static boolean ffmpegAvailable() {
		try {
			Process process = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (IOException ex) {
			return false;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
