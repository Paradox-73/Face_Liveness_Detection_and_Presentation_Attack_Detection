/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.fault;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.function.LongSupplier;

import io.mosip.mock.sbi.devicehelper.face.source.FaceFrame;

/**
 * Holds the active face fault and decides, frame by frame, what the STREAM
 * writer sends. Thread-safe: the admin API changes the fault while a stream
 * thread calls {@link #decide(FaceFrame)}.
 *
 * <p>
 * Frames are counted per stream ({@link #startStream()}). A fault starts once
 * {@code afterFrames} frames of the stream have passed unaltered and lasts
 * {@code frames} frames (0 = until cleared); STALL may instead last
 * {@code durationMs}. {@code delayMs} applies with every fault type.
 * </p>
 */
public class FaceFaultInjector {
	private static final byte[] NON_JPEG_BYTES = buildNonJpegBytes();

	private final LongSupplier clock;
	private volatile FaceFaultSettings settings = FaceFaultSettings.NONE;
	private long framesInStream;
	private long framesInWindow;
	private boolean windowClosed;
	private double dropAccumulator;
	private FaceFrame frozen;
	private long stallStartedMillis = -1;

	/** Creates an injector that uses the system clock. */
	public FaceFaultInjector() {
		this(System::currentTimeMillis);
	}

	/**
	 * Creates an injector with a given clock, for tests.
	 *
	 * @param clock returns the current time in milliseconds
	 */
	public FaceFaultInjector(LongSupplier clock) {
		this.clock = clock;
	}

	/**
	 * Replaces the active fault. The new fault's frame window starts from the
	 * current position in the stream.
	 *
	 * @param newSettings the fault; null clears it
	 */
	public synchronized void set(FaceFaultSettings newSettings) {
		this.settings = newSettings == null ? FaceFaultSettings.NONE : newSettings;
		resetWindow();
	}

	/**
	 * Returns the active fault.
	 *
	 * @return the settings, never null
	 */
	public FaceFaultSettings get() {
		return settings;
	}

	/** Resets per-stream counters; called when a STREAM request starts. */
	public synchronized void startStream() {
		framesInStream = 0;
		resetWindow();
	}

	private void resetWindow() {
		framesInWindow = 0;
		windowClosed = false;
		dropAccumulator = 0.0;
		frozen = null;
		stallStartedMillis = -1;
	}

	/**
	 * Decides what to send for the next frame produced by the source.
	 *
	 * @param frame the source frame
	 * @return the action for the STREAM writer
	 */
	public synchronized FrameAction decide(FaceFrame frame) {
		FaceFaultSettings current = settings;
		long index = framesInStream++;
		if (!isActive(current, index))
			return FrameAction.send(frame, true);
		framesInWindow++;
		switch (current.getType()) {
		case DISCONNECT:
			return FrameAction.disconnect();
		case RESET:
			return FrameAction.reset();
		case STALL:
			return stall(current, frame);
		case FREEZE:
			if (frozen == null)
				frozen = frame;
			return FrameAction.send(frozen, true);
		case DROP_FRAMES:
			return drop(current, frame);
		case CORRUPT_JPEG:
			return FrameAction.send(frame.withJpeg(garble(frame.getJpeg())), false);
		case TRUNCATED_JPEG:
			return FrameAction.send(frame.withJpeg(truncate(frame.getJpeg())), false);
		case NON_JPEG:
			return FrameAction.send(frame.withJpeg(NON_JPEG_BYTES.clone()), false);
		case BAD_CONTENT_LENGTH:
			return FrameAction.sendBadLength(frame, current.getLengthDelta());
		default:
			return FrameAction.send(frame, true);
		}
	}

	private boolean isActive(FaceFaultSettings current, long index) {
		if (current.getType() == FaceFaultType.NONE || windowClosed || index < current.getAfterFrames())
			return false;
		if (current.getFrames() > 0 && framesInWindow >= current.getFrames()) {
			windowClosed = true;
			return false;
		}
		return true;
	}

	private FrameAction stall(FaceFaultSettings current, FaceFrame frame) {
		long now = clock.getAsLong();
		if (stallStartedMillis < 0)
			stallStartedMillis = now;
		if (current.getDurationMillis() > 0 && now - stallStartedMillis >= current.getDurationMillis()) {
			windowClosed = true;
			return FrameAction.send(frame, true);
		}
		return FrameAction.skip();
	}

	private FrameAction drop(FaceFaultSettings current, FaceFrame frame) {
		dropAccumulator += current.getDropRatio();
		if (dropAccumulator >= 1.0 - 1e-9) {
			dropAccumulator -= 1.0;
			return FrameAction.skip();
		}
		return FrameAction.send(frame, true);
	}

	/**
	 * Returns the frame rate to pace the next frame at.
	 *
	 * @param sourceFps the source's own frame rate
	 * @return the SLOW_FPS rate while that fault is active, else sourceFps
	 */
	public synchronized double effectiveFps(double sourceFps) {
		FaceFaultSettings current = settings;
		if (current.getType() == FaceFaultType.SLOW_FPS && !windowClosed
				&& framesInStream >= current.getAfterFrames()
				&& (current.getFrames() == 0 || framesInWindow < current.getFrames()))
			return current.getFps();
		return sourceFps;
	}

	/**
	 * Returns the extra delay to add before every frame.
	 *
	 * @return milliseconds, 0 when none
	 */
	public long extraDelayMillis() {
		return settings.getDelayMillis();
	}

	/**
	 * Tells whether the camera should be reported as unavailable.
	 *
	 * @return true while the UNAVAILABLE fault is set
	 */
	public boolean isSourceUnavailable() {
		return settings.getType() == FaceFaultType.UNAVAILABLE;
	}

	/**
	 * Describes the fault state for the admin state response.
	 *
	 * @return the settings plus the frame count of the current stream
	 */
	public synchronized Map<String, Object> describe() {
		Map<String, Object> map = settings.describe();
		map.put("framesInStream", framesInStream);
		return map;
	}

	/**
	 * Damages the entropy-coded data of a JPEG while keeping its markers, so a
	 * decoder shows garbage or reports an error. Deterministic.
	 *
	 * @param jpeg the intact JPEG
	 * @return a damaged copy
	 */
	public static byte[] garble(byte[] jpeg) {
		byte[] out = jpeg.clone();
		int start = scanDataStart(out);
		for (int i = start + (out.length - start) / 8; i < out.length - 2; i += 3) {
			int value = out[i] & 0xFF;
			int changed = value ^ 0x55;
			boolean afterMarkerPrefix = (out[i - 1] & 0xFF) == 0xFF;
			if (value != 0xFF && changed != 0xFF && !afterMarkerPrefix)
				out[i] = (byte) changed;
		}
		return out;
	}

	/**
	 * Cuts a JPEG to 60 % of its length, so the end marker is missing.
	 *
	 * @param jpeg the intact JPEG
	 * @return the shortened copy
	 */
	public static byte[] truncate(byte[] jpeg) {
		return Arrays.copyOf(jpeg, Math.max(2, jpeg.length * 6 / 10));
	}

	private static int scanDataStart(byte[] jpeg) {
		for (int i = 2; i + 3 < jpeg.length; i++) {
			if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xDA) {
				int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
				return Math.min(jpeg.length - 1, i + 2 + length);
			}
		}
		return Math.max(1, jpeg.length / 4);
	}

	private static byte[] buildNonJpegBytes() {
		StringBuilder text = new StringBuilder();
		while (text.length() < 2048)
			text.append("This multipart part is not a JPEG image. ");
		return text.toString().getBytes(StandardCharsets.US_ASCII);
	}
}
