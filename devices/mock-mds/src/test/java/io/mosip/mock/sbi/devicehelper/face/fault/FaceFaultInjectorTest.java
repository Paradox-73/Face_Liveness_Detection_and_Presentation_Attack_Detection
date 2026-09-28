package io.mosip.mock.sbi.devicehelper.face.fault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.mosip.mock.sbi.devicehelper.face.fault.FrameAction.Kind;
import io.mosip.mock.sbi.devicehelper.face.source.FaceFrame;
import io.mosip.mock.sbi.devicehelper.face.source.FaceTestFrames;

/** T-MOCK: each fault mode changes the stream as documented. */
class FaceFaultInjectorTest {

	private final AtomicLong clock = new AtomicLong(1_000L);
	private final FaceFaultInjector injector = new FaceFaultInjector(clock::get);
	private final List<FaceFrame> frames = new ArrayList<>();

	@BeforeEach
	void buildFrames() throws IOException {
		BufferedImage base = FaceTestFrames.decode(Files.readAllBytes(FaceTestFrames.STOCK_IMAGE));
		for (int i = 0; i < 12; i++)
			frames.add(new FaceFrame(FaceTestFrames.toJpeg(FaceTestFrames.frame(base, i, 12)), i, i, "TEST"));
		injector.startStream();
	}

	@Test
	void noFaultSendsEveryFrameUnchanged() {
		for (FaceFrame frame : frames) {
			FrameAction action = injector.decide(frame);
			assertEquals(Kind.SEND, action.getKind());
			assertSame(frame, action.getFrame());
			assertTrue(action.isValidImage());
		}
	}

	@Test
	void freezeRepeatsOneFrameAfterTheGivenCount() {
		injector.set(FaceFaultSettings.builder(FaceFaultType.FREEZE).afterFrames(3).build());
		for (int i = 0; i < 3; i++)
			assertSame(frames.get(i), injector.decide(frames.get(i)).getFrame());
		for (int i = 3; i < 10; i++) {
			FrameAction action = injector.decide(frames.get(i));
			assertSame(frames.get(3), action.getFrame(), "frame " + i + " must repeat frame 3");
			assertTrue(action.isValidImage());
		}
	}

	@Test
	void freezeLastsOnlyTheGivenNumberOfFrames() {
		injector.set(FaceFaultSettings.builder(FaceFaultType.FREEZE).afterFrames(1).frames(3).build());
		assertSame(frames.get(0), injector.decide(frames.get(0)).getFrame());
		for (int i = 1; i <= 3; i++)
			assertSame(frames.get(1), injector.decide(frames.get(i)).getFrame());
		assertSame(frames.get(4), injector.decide(frames.get(4)).getFrame());
	}

	@Test
	void dropSkipsTheConfiguredShareOfFrames() {
		injector.set(FaceFaultSettings.builder(FaceFaultType.DROP_FRAMES).dropRatio(0.5).build());
		int skipped = 0;
		for (FaceFrame frame : frames) {
			if (injector.decide(frame).getKind() == Kind.SKIP)
				skipped++;
		}
		assertEquals(6, skipped);
		injector.set(FaceFaultSettings.builder(FaceFaultType.DROP_FRAMES).dropRatio(1.0).build());
		assertEquals(Kind.SKIP, injector.decide(frames.get(0)).getKind());
	}

	@Test
	void corruptionFaultsSendBytesThatAreNotTheIntactFrame() {
		injector.set(FaceFaultSettings.builder(FaceFaultType.TRUNCATED_JPEG).build());
		FrameAction truncated = injector.decide(frames.get(0));
		assertFalse(truncated.isValidImage());
		assertTrue(truncated.getFrame().getJpeg().length < frames.get(0).getJpeg().length);
		byte[] cut = truncated.getFrame().getJpeg();
		assertFalse((cut[cut.length - 2] & 0xFF) == 0xFF && (cut[cut.length - 1] & 0xFF) == 0xD9, "no end marker");

		injector.set(FaceFaultSettings.builder(FaceFaultType.NON_JPEG).build());
		FrameAction nonJpeg = injector.decide(frames.get(1));
		assertNull(FaceTestFrames.decode(nonJpeg.getFrame().getJpeg()));
		for (byte b : nonJpeg.getFrame().getJpeg())
			assertNotEquals((byte) 0xFF, b);

		injector.set(FaceFaultSettings.builder(FaceFaultType.CORRUPT_JPEG).build());
		byte[] original = frames.get(2).getJpeg();
		FrameAction garbled = injector.decide(frames.get(2));
		byte[] damaged = garbled.getFrame().getJpeg();
		assertFalse(garbled.isValidImage());
		assertEquals(original.length, damaged.length);
		assertNotEquals(frames.get(2).sha256(), garbled.getFrame().sha256());
		assertArrayEquals(java.util.Arrays.copyOf(original, 200), java.util.Arrays.copyOf(damaged, 200),
				"headers stay intact");
		BufferedImage decoded = FaceTestFrames.decode(damaged);
		if (decoded != null)
			assertTrue(FaceTestFrames.meanAbsDiff(decoded, FaceTestFrames.decode(original)) > 5.0,
					"damaged frame must look different");
	}

	@Test
	void badContentLengthKeepsBytesAndChangesDeclaredLength() {
		injector.set(FaceFaultSettings.builder(FaceFaultType.BAD_CONTENT_LENGTH).lengthDelta(-100).build());
		FrameAction action = injector.decide(frames.get(0));
		assertEquals(Kind.SEND_BAD_LENGTH, action.getKind());
		assertEquals(-100, action.getLengthDelta());
		assertSame(frames.get(0), action.getFrame());
	}

	@Test
	void disconnectAndResetStartAfterTheGivenFrameCount() {
		injector.set(FaceFaultSettings.builder(FaceFaultType.DISCONNECT).afterFrames(5).build());
		for (int i = 0; i < 5; i++)
			assertEquals(Kind.SEND, injector.decide(frames.get(i)).getKind());
		assertEquals(Kind.DISCONNECT, injector.decide(frames.get(5)).getKind());

		injector.set(FaceFaultSettings.builder(FaceFaultType.parse("RST")).build());
		injector.startStream();
		assertEquals(Kind.RESET, injector.decide(frames.get(0)).getKind());
	}

	@Test
	void stallSkipsFramesForItsDuration() {
		injector.set(FaceFaultSettings.builder(FaceFaultType.STALL).afterFrames(1).durationMillis(2000).build());
		assertEquals(Kind.SEND, injector.decide(frames.get(0)).getKind());
		assertEquals(Kind.SKIP, injector.decide(frames.get(1)).getKind());
		clock.addAndGet(1500);
		assertEquals(Kind.SKIP, injector.decide(frames.get(2)).getKind());
		clock.addAndGet(600);
		assertEquals(Kind.SEND, injector.decide(frames.get(3)).getKind());
		assertEquals(Kind.SEND, injector.decide(frames.get(4)).getKind(), "stall does not restart");
	}

	@Test
	void slowFpsAndDelayChangePacing() {
		assertEquals(15.0, injector.effectiveFps(15.0));
		FaceFaultType type = FaceFaultType.parse("FPS=2");
		injector.set(FaceFaultSettings.builder(type).fps(2).afterFrames(2).delayMillis(40).build());
		assertEquals(15.0, injector.effectiveFps(15.0), "not yet active");
		injector.decide(frames.get(0));
		injector.decide(frames.get(1));
		assertEquals(2.0, injector.effectiveFps(15.0));
		assertEquals(40L, injector.extraDelayMillis());
	}

	@Test
	void unavailableAndStatusFaultsAreReported() {
		injector.set(FaceFaultSettings.builder(FaceFaultType.parse("camera-unavailable")).build());
		assertTrue(injector.isSourceUnavailable());
		assertTrue(FaceFaultType.parse("busy").isStatusFault());
		assertTrue(FaceFaultType.NOT_READY.isStatusFault());
		injector.set(null);
		assertFalse(injector.isSourceUnavailable());
		assertEquals("NONE", injector.describe().get("fault"));
	}

	@Test
	void aliasesAndValidation() {
		assertEquals(FaceFaultType.DISCONNECT, FaceFaultType.parse("fin"));
		assertEquals(FaceFaultType.DROP_FRAMES, FaceFaultType.parse("drop"));
		assertEquals(FaceFaultType.CORRUPT_JPEG, FaceFaultType.parse("corrupt"));
		assertEquals(FaceFaultType.SLOW_FPS, FaceFaultType.parse("slow"));
		assertEquals(FaceFaultType.NONE, FaceFaultType.parse("clear"));
		assertThrows(IllegalArgumentException.class, () -> FaceFaultType.parse("explode"));
		assertThrows(IllegalArgumentException.class, () -> FaceFaultType.parse(" "));
		FaceFaultSettings.Builder builder = FaceFaultSettings.builder(FaceFaultType.DROP_FRAMES);
		assertThrows(IllegalArgumentException.class, () -> builder.dropRatio(0));
		assertThrows(IllegalArgumentException.class, () -> builder.fps(0));
		assertThrows(IllegalArgumentException.class, () -> builder.delayMillis(-1));
		assertThrows(IllegalArgumentException.class, () -> builder.afterFrames(-1));
		assertThrows(IllegalArgumentException.class, () -> builder.lengthDelta(0));
		assertNotNull(builder.build().describe().get("dropRatio"));
	}
}
