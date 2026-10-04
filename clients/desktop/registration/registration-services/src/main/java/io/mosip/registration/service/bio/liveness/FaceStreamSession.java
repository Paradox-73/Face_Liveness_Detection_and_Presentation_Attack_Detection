/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.device.StreamStats;
import io.mosip.liveness.core.stream.MjpegFrameReader;
import io.mosip.registration.config.AppConfig;

/**
 * A running SBI face stream: the core {@link MjpegFrameReader} on its own {@code sbi-stream-reader} thread.
 *
 * <p>It replaces the parse loop of the client's {@code Streamer} for liveness: frames are validated JPEG parts with
 * monotonic timestamps; an end of stream, a read that exceeds the socket timeout (2 s), a stalled or disconnected
 * device, or too many invalid parts end the stream with exactly one terminal callback instead of spinning. After
 * a disconnect or stall the device is asked for a new stream up to {@link StreamRequest#reconnectAttempts()}
 * times; the sink sees one continuous stream with increasing sequence numbers.
 *
 * <p>Sink callbacks run on the reader thread. {@link #close()} is safe from any thread: it drops the connection
 * and the reader ends with {@link EndReason#CLOSED}.
 */
public final class FaceStreamSession implements FaceFrameSource {

	private static final Logger LOGGER = AppConfig.getLogger(FaceStreamSession.class);

	/** Opens a new device stream, for reconnects. */
	@FunctionalInterface
	public interface Connector {

		/**
		 * Opens the stream.
		 *
		 * @return the MJPEG response body
		 * @throws DeviceException if the device refuses or cannot be reached
		 */
		InputStream open() throws DeviceException;
	}

	private final Connector connector;
	private final StreamRequest request;
	private final Clock clock;
	private final String sourceId;
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicLong deliveredBefore = new AtomicLong();
	private volatile InputStream initial;
	private volatile MjpegFrameReader reader;
	private volatile Thread thread;

	/**
	 * Creates a session over an opened stream.
	 *
	 * @param initial   the first opened stream
	 * @param connector opens a new stream for a reconnect
	 * @param request   timeouts and reconnect attempts
	 * @param clock     clock for frame timestamps and deadlines
	 * @param sourceId  device serial number, recorded in each frame
	 */
	public FaceStreamSession(InputStream initial, Connector connector, StreamRequest request, Clock clock,
			String sourceId) {
		this.initial = Objects.requireNonNull(initial, "initial");
		this.connector = Objects.requireNonNull(connector, "connector");
		this.request = Objects.requireNonNull(request, "request");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
	}

	@Override
	public void start(FrameSink sink) {
		Objects.requireNonNull(sink, "sink");
		if (closed.get() || !started.compareAndSet(false, true)) {
			throw new IllegalStateException("stream already started or closed");
		}
		Thread t = new Thread(() -> readLoop(sink), "sbi-stream-reader");
		t.setDaemon(true);
		thread = t;
		t.start();
	}

	@Override
	public StreamStats stats() {
		MjpegFrameReader r = reader;
		if (r == null) {
			return StreamStats.EMPTY;
		}
		StreamStats s = r.stats();
		return new StreamStats(s.framesDelivered() + deliveredBefore.get(), s.framesDropped(), s.invalidParts(), s.fps(),
				s.jitterMs());
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		MjpegFrameReader r = reader;
		if (r != null) {
			r.stop();
		}
		InputStream in = initial;
		if (!started.get() && in != null) {
			closeQuietly(in);
		}
	}

	/**
	 * Waits for the reader thread to end (tests).
	 *
	 * @param millis longest wait
	 * @throws InterruptedException if interrupted while waiting
	 */
	void join(long millis) throws InterruptedException {
		Thread t = thread;
		if (t != null) {
			t.join(millis);
		}
	}

	private void readLoop(FrameSink sink) {
		Relay relay = new Relay(sink);
		InputStream in = initial;
		initial = null;
		int reconnects = 0;
		long deadline = clock.monotonicNanos() + request.streamTimeoutMs() * 1_000_000L;
		while (true) {
			long remainingMs = Math.max(1L, (deadline - clock.monotonicNanos()) / 1_000_000L);
			MjpegFrameReader r = MjpegFrameReader.builder(in, relay).clock(clock).sourceId(sourceId)
					.firstFrameTimeoutMs(request.firstFrameTimeoutMs()).streamTimeoutMs(remainingMs).build();
			reader = r;
			if (closed.get()) {
				r.stop();
			}
			relay.reset();
			r.run();
			if (closed.get() || !relay.shouldReconnect() || reconnects >= request.reconnectAttempts()) {
				relay.forwardTerminal(closed.get());
				return;
			}
			// Counted here, not after each reader, so stats() never adds the current reader twice.
			deliveredBefore.addAndGet(r.stats().framesDelivered());
			reconnects++;
			LOGGER.info("Face stream {} ended with {}; reconnecting ({}/{})", sourceId, relay.errorCode(), reconnects,
					request.reconnectAttempts());
			try {
				in = connector.open();
			} catch (DeviceException e) {
				sink.onError(e.getError());
				return;
			}
			if (closed.get()) {
				closeQuietly(in);
				sink.onEnd(EndReason.CLOSED);
				return;
			}
		}
	}

	private static void closeQuietly(InputStream in) {
		try {
			in.close();
		} catch (IOException | RuntimeException e) {
			// The stream is abandoned either way.
		}
	}

	/**
	 * Passes frames on with sequence numbers that keep increasing across reconnects, and holds back the terminal
	 * call of each reader until the session decides whether to reconnect.
	 */
	private static final class Relay implements FrameSink {

		private final FrameSink target;
		private long nextSequence;
		private DeviceError error;
		private EndReason end;

		Relay(FrameSink target) {
			this.target = target;
		}

		void reset() {
			error = null;
			end = null;
		}

		@Override
		public void onFrame(FaceFrame frame) {
			FaceFrame f = frame.sequence() == nextSequence ? frame : renumber(frame, nextSequence);
			nextSequence++;
			target.onFrame(f);
		}

		@Override
		public void onError(DeviceError e) {
			this.error = e;
		}

		@Override
		public void onEnd(EndReason reason) {
			this.end = reason;
		}

		boolean shouldReconnect() {
			if (error == null) {
				return false;
			}
			DeviceErrorCode code = error.code();
			return code == DeviceErrorCode.DISCONNECTED || code == DeviceErrorCode.STALLED;
		}

		DeviceErrorCode errorCode() {
			return error == null ? null : error.code();
		}

		void forwardTerminal(boolean closedByConsumer) {
			if (closedByConsumer) {
				target.onEnd(EndReason.CLOSED);
			} else if (error != null) {
				target.onError(error);
			} else {
				target.onEnd(end == null ? EndReason.END_OF_STREAM : end);
			}
		}

		private static FaceFrame renumber(FaceFrame f, long sequence) {
			return FaceFrame.builder().data(f.data()).format(f.format()).size(f.width(), f.height())
					.rotationDegrees(f.rotationDegrees()).mirrored(f.isMirrored()).monotonicNanos(f.monotonicNanos())
					.sequence(sequence).sourceId(f.sourceId()).build();
		}
	}
}
