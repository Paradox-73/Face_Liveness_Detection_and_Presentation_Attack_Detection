/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationHelper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;

/**
 * Tests {@link FaceStreamSession} against an HTTP MJPEG stream served by {@link MockWebServer}, opened the way the
 * SBI providers open it ({@link MosipDeviceSpecificationHelper#openStream}): frames, end of stream, stall with the
 * 2 s socket timeout, disconnect with reconnect, invalid parts and close from another thread.
 */
public class FaceStreamSessionTest {

	private static final String BOUNDARY = "--BoundaryString";

	private MockWebServer server;
	private final MosipDeviceSpecificationHelper helper = new MosipDeviceSpecificationHelper();
	private final Sink sink = new Sink();
	private FaceStreamSession session;

	@Before
	public void start() throws IOException {
		server = new MockWebServer();
		server.start();
	}

	@After
	public void stop() throws IOException {
		if (session != null) {
			session.close();
		}
		server.shutdown();
	}

	private static byte[] mjpeg(int frames, boolean validJpeg) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < frames; i++) {
			byte[] body = validJpeg ? LivenessFakes.jpeg(64, 48, i) : "not a jpeg".getBytes(StandardCharsets.US_ASCII);
			String head = BOUNDARY + "\r\nContent-Type:image/jpeg\r\nContent-Length:" + body.length + "\r\n\r\n";
			out.write(head.getBytes(StandardCharsets.US_ASCII), 0, head.length());
			out.write(body, 0, body.length);
			out.write('\r');
			out.write('\n');
		}
		return out.toByteArray();
	}

	private static MockResponse stream(byte[] body) {
		return new MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=BoundaryString")
				.setBody(new Buffer().write(body));
	}

	private InputStream open(int socketTimeoutMs) throws DeviceException {
		try {
			return helper.openStream(server.url("/stream").toString(), "STREAM", "{}", socketTimeoutMs);
		} catch (IOException e) {
			throw new DeviceException(DeviceErrorCode.STREAM_OPEN_FAILED, "open failed", e);
		}
	}

	private FaceStreamSession session(int reconnects, int socketTimeoutMs) throws DeviceException {
		StreamRequest request = StreamRequest.builder().streamTimeoutMs(20_000).firstFrameTimeoutMs(3_000)
				.socketTimeoutMs(socketTimeoutMs).reconnectAttempts(reconnects).build();
		session = new FaceStreamSession(open(socketTimeoutMs), () -> open(socketTimeoutMs), request, Clock.system(),
				LivenessFakes.SERIAL);
		return session;
	}

	@Test
	public void framesArriveInOrderThenOneEndOfStream() throws Exception {
		server.enqueue(stream(mjpeg(5, true)));
		session(0, 2_000).start(sink);
		assertTrue(sink.awaitTerminal());
		assertEquals(5, sink.frames.size());
		for (int i = 0; i < 5; i++) {
			assertEquals(i, sink.frames.get(i).sequence());
			assertEquals(LivenessFakes.SERIAL, sink.frames.get(i).sourceId());
		}
		assertEquals(EndReason.END_OF_STREAM, sink.end);
		assertEquals(1, sink.terminalCalls.get());
		assertEquals(5, session.stats().framesDelivered());
	}

	@Test
	public void stalledDeviceEndsWithStalledAfterSocketTimeout() throws Exception {
		byte[] body = mjpeg(2, true);
		server.enqueue(stream(body).throttleBody(body.length / 2, 2, TimeUnit.SECONDS));
		long start = System.nanoTime();
		session(0, 500).start(sink);
		assertTrue(sink.awaitTerminal());
		long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		assertEquals(DeviceErrorCode.STALLED, sink.error.code());
		assertTrue("ended by the read timeout, not the device: " + ms, ms < 1_900);
		assertEquals(1, sink.terminalCalls.get());
	}

	@Test
	public void disconnectIsFollowedByOneReconnectWithContinuousSequence() throws Exception {
		byte[] first = mjpeg(3, true);
		server.enqueue(stream(first).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
		server.enqueue(stream(mjpeg(4, true)));
		session(1, 2_000).start(sink);
		assertTrue(sink.awaitTerminal());
		assertEquals(2, server.getRequestCount());
		assertEquals("STREAM", server.takeRequest().getMethod());
		for (int i = 0; i < sink.frames.size(); i++) {
			assertEquals(i, sink.frames.get(i).sequence());
		}
		assertEquals(1, sink.terminalCalls.get());
	}

	@Test
	public void invalidPartsAreSkipped() throws Exception {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(mjpeg(2, false));
		body.write(mjpeg(3, true));
		server.enqueue(stream(body.toByteArray()));
		session(0, 2_000).start(sink);
		assertTrue(sink.awaitTerminal());
		assertEquals(3, sink.frames.size());
		assertEquals(2, session.stats().invalidParts());
	}

	@Test
	public void closeFromAnotherThreadEndsWithClosed() throws Exception {
		byte[] body = mjpeg(200, true);
		server.enqueue(stream(body).throttleBody(4_096, 50, TimeUnit.MILLISECONDS));
		session(1, 2_000).start(sink);
		assertTrue(sink.firstFrame.await(5, TimeUnit.SECONDS));
		session.close();
		assertTrue(sink.awaitTerminal());
		assertEquals(EndReason.CLOSED, sink.end);
		assertNull(sink.error);
		assertEquals("no reconnect after close", 1, server.getRequestCount());
	}

	@Test(expected = DeviceException.class)
	public void refusedStreamFailsToOpen() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(503));
		open(2_000);
	}

	@Test
	public void jsonErrorAnswerIsARefusedStreamNotADisconnect() throws Exception {
		server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
				.setBody("{\"errorCode\":\"608\",\"errorInfo\":\"Device not ready\"}"));
		try {
			helper.openStream(server.url("/stream").toString(), "STREAM", "{}", 2_000);
			fail("a JSON error answer must not be read as a frame stream");
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("608"));
		}
	}

	/** Records frames and the single terminal call. */
	private static final class Sink implements FrameSink {
		final List<FaceFrame> frames = new CopyOnWriteArrayList<>();
		final AtomicInteger terminalCalls = new AtomicInteger();
		final CountDownLatch terminal = new CountDownLatch(1);
		final CountDownLatch firstFrame = new CountDownLatch(1);
		volatile EndReason end;
		volatile DeviceError error;

		@Override
		public void onFrame(FaceFrame frame) {
			frames.add(frame);
			firstFrame.countDown();
		}

		@Override
		public void onError(DeviceError e) {
			error = e;
			terminalCalls.incrementAndGet();
			terminal.countDown();
		}

		@Override
		public void onEnd(EndReason reason) {
			end = reason;
			terminalCalls.incrementAndGet();
			terminal.countDown();
		}

		boolean awaitTerminal() throws InterruptedException {
			boolean done = terminal.await(10, TimeUnit.SECONDS);
			Thread.sleep(100);
			return done;
		}
	}
}
