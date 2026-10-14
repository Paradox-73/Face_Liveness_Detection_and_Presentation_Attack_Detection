/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mosip.mock.sbi.devicehelper.face.fault.FaceFaultInjector;
import io.mosip.mock.sbi.devicehelper.face.source.FaceFrame;
import io.mosip.mock.sbi.devicehelper.face.source.FaceFrameSource;
import io.mosip.mock.sbi.devicehelper.face.source.FaceFrameSources;
import io.mosip.mock.sbi.devicehelper.face.source.FaceSourceSettings;
import io.mosip.mock.sbi.devicehelper.face.source.FaceSourceType;

/**
 * Connects the mock face device to its current {@link FaceFrameSource}.
 *
 * <p>
 * A session runs from {@link #open()} (STREAM start or a capture without a
 * stream) to {@link #close()} (device de-initialised). The feed keeps the last
 * intact frame written to STREAM so RCAPTURE can sign exactly what the client
 * analysed, paces the stream at the source frame rate (or the SLOW_FPS fault
 * rate), and lets the admin API switch sources during a stream.
 * </p>
 */
public class FaceFrameFeed {
	private static final Logger LOGGER = LoggerFactory.getLogger(FaceFrameFeed.class);

	private final Path workingDirectory;
	private final Path defaultImage;
	private final FaceFaultInjector faults;
	private final AtomicLong framesServed = new AtomicLong();
	private final AtomicLong capturesTaken = new AtomicLong();

	private FaceSourceSettings settings;
	private FaceFrameSource source;
	private boolean open;
	/** SEQUENCE sources replaced during the open session, by folder, for {@code resume}. */
	private final Map<String, FaceFrameSource> parked = new HashMap<>();
	private volatile FaceFrame currentFrame;
	private volatile FaceFrame lastServedFrame;
	private volatile FaceFrame lastCapturedFrame;
	private volatile String lastError;
	private long nextDueNanos;

	/**
	 * Creates the feed.
	 *
	 * @param initial          start-up source settings
	 * @param workingDirectory folder that relative paths are resolved against
	 * @param defaultImage     JPEG served by STATIC when no path is set
	 * @param faults           the device's fault injector
	 */
	public FaceFrameFeed(FaceSourceSettings initial, Path workingDirectory, Path defaultImage,
			FaceFaultInjector faults) {
		this.settings = initial.copy();
		this.workingDirectory = workingDirectory;
		this.defaultImage = defaultImage;
		this.faults = faults;
	}

	/**
	 * Selects a new source. The new source is opened once to check it works (for
	 * a webcam this waits for its first frame). During a session the new source
	 * replaces the old one at once; otherwise it is closed again and reopened
	 * when the next session starts. On failure the old source stays.
	 *
	 * @param newSettings the new source settings
	 * @throws IOException              if the new source cannot produce frames
	 * @throws IllegalArgumentException if the settings are incomplete
	 */
	public void setSource(FaceSourceSettings newSettings) throws IOException {
		setSource(newSettings, false);
	}

	/**
	 * Selects a new source, as {@link #setSource(FaceSourceSettings)}. With
	 * {@code resume}, a SEQUENCE folder that was replaced earlier in the same
	 * session continues at the frame where it stopped, the way a recorded person
	 * goes on after answering a prompt; the scenario runner's playlist does the
	 * same, so replayed packs do not repeat frames the liveness loop check has
	 * already seen.
	 *
	 * @param newSettings the new source settings
	 * @param resume      continue a SEQUENCE folder replaced earlier in this
	 *                    session
	 * @throws IOException              if the new source cannot produce frames
	 * @throws IllegalArgumentException if the settings are incomplete
	 */
	public void setSource(FaceSourceSettings newSettings, boolean resume) throws IOException {
		FaceFrameSource candidate = null;
		FaceFrameSource stale = null;
		synchronized (this) {
			FaceFrameSource earlier = open ? parked.remove(parkKey(newSettings)) : null;
			if (resume)
				candidate = earlier;
			else
				stale = earlier;
		}
		if (stale != null)
			stale.close();
		if (candidate == null) {
			candidate = FaceFrameSources.create(newSettings, workingDirectory, defaultImage);
			candidate.open();
		}
		FaceFrameSource old;
		FaceSourceSettings oldSettings;
		boolean park;
		synchronized (this) {
			old = source;
			oldSettings = settings;
			settings = newSettings.copy();
			source = candidate;
			if (!open)
				candidate.close();
			lastError = null;
			park = open && old != null && old != candidate && parkKey(oldSettings) != null;
			if (park) {
				FaceFrameSource replaced = parked.put(parkKey(oldSettings), old);
				if (replaced != null && replaced != old)
					replaced.close();
			}
		}
		if (old != null && old != candidate && !park)
			old.close();
		LOGGER.info("Face frame source set :: {}", newSettings.describe());
	}

	/** A parked source resumes only for the same folder, frame rate and ending. */
	private static String parkKey(FaceSourceSettings s) {
		return s.getType() == FaceSourceType.SEQUENCE ? s.getPath() + "|" + s.getFps() + "|" + s.isPingpong() : null;
	}

	/**
	 * Starts a session: opens the source and resets per-stream counters.
	 *
	 * @return false if the camera is unavailable (UNAVAILABLE fault or open
	 *         failure); the reason is kept for the admin state
	 */
	public synchronized boolean open() {
		if (faults.isSourceUnavailable()) {
			lastError = "camera unavailable (UNAVAILABLE fault)";
			return false;
		}
		try {
			if (source == null)
				source = FaceFrameSources.create(settings, workingDirectory, defaultImage);
			if (!open) {
				source.open();
				open = true;
			}
		} catch (IOException | RuntimeException ex) {
			lastError = ex.getMessage();
			LOGGER.error("Face frame source could not be opened :: {}", ex.getMessage());
			return false;
		}
		lastServedFrame = null;
		currentFrame = null;
		nextDueNanos = 0;
		faults.startStream();
		return true;
	}

	/** Ends the session and releases the source (files, ffmpeg process, camera). */
	public synchronized void close() {
		if (source != null && open)
			source.close();
		parked.values().forEach(FaceFrameSource::close);
		parked.clear();
		open = false;
		currentFrame = null;
	}

	/**
	 * Returns the next source frame for STREAM, or null when the source has
	 * ended, failed or no session is open. If the source is replaced by
	 * {@link #setSource(FaceSourceSettings)} while this call waits, the frame is
	 * read from the new source instead, so the stream continues.
	 *
	 * @return the frame or null
	 */
	public FaceFrame nextFrame() {
		for (int attempt = 0; attempt < 3; attempt++) {
			FaceFrameSource active;
			synchronized (this) {
				if (!open)
					return null;
				active = source;
			}
			try {
				FaceFrame frame = active.next();
				if (frame != null || !isReplaced(active)) {
					currentFrame = frame;
					return frame;
				}
			} catch (IOException ex) {
				if (!isReplaced(active)) {
					lastError = ex.getMessage();
					LOGGER.error("Face frame source failed :: {}", ex.getMessage());
					return null;
				}
			}
		}
		return null;
	}

	private synchronized boolean isReplaced(FaceFrameSource active) {
		return source != active;
	}

	/**
	 * Returns a frame for a capture made without a running stream: opens a
	 * session if needed and reads one frame.
	 *
	 * @return the frame, or null if the source gives none
	 */
	public FaceFrame grabFrame() {
		synchronized (this) {
			if (!open && !open())
				return null;
		}
		return nextFrame();
	}

	/**
	 * Records a frame as written to STREAM.
	 *
	 * @param frame      the frame written
	 * @param validImage true when the bytes were an intact JPEG; only those are
	 *                   used for capture
	 */
	public void markServed(FaceFrame frame, boolean validImage) {
		framesServed.incrementAndGet();
		if (validImage)
			lastServedFrame = frame;
	}

	/**
	 * Records a frame as used for an RCAPTURE record.
	 *
	 * @param frame the frame
	 */
	public void markCaptured(FaceFrame frame) {
		capturesTaken.incrementAndGet();
		lastCapturedFrame = frame;
	}

	/**
	 * Returns how long to wait before the next STREAM frame, keeping a steady
	 * schedule at the effective frame rate plus any fault delay.
	 *
	 * @return milliseconds to sleep
	 */
	public synchronized long nextPauseMillis() {
		double sourceFps = source != null ? source.getFps() : settings.getFps();
		double fps = faults.effectiveFps(sourceFps);
		long interval = (long) (1_000_000_000L / fps);
		long now = System.nanoTime();
		if (nextDueNanos == 0 || now - nextDueNanos > interval)
			nextDueNanos = now + interval;
		else
			nextDueNanos += interval;
		return Math.max(0L, (nextDueNanos - now) / 1_000_000L) + faults.extraDelayMillis();
	}

	/**
	 * Describes the feed for the admin state response. Contains no image data;
	 * frames are identified by SHA-256 digest.
	 *
	 * @return a map of names to values
	 */
	public synchronized Map<String, Object> describe() {
		Map<String, Object> map = new LinkedHashMap<>(source != null ? source.describe() : settings.describe());
		map.put("sessionOpen", open);
		map.put("framesServed", framesServed.get());
		map.put("capturesTaken", capturesTaken.get());
		putFrame(map, "lastServedFrame", lastServedFrame);
		putFrame(map, "lastCapturedFrame", lastCapturedFrame);
		if (lastError != null)
			map.put("lastError", lastError);
		return map;
	}

	private static void putFrame(Map<String, Object> map, String key, FaceFrame frame) {
		if (frame == null)
			return;
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("sequence", frame.getSequence());
		info.put("timestamp", frame.getTimestampMillis());
		info.put("bytes", frame.getJpeg().length);
		info.put("sha256", frame.sha256());
		map.put(key, info);
	}

	/** @return the frame most recently read from the source, or null */
	public FaceFrame getCurrentFrame() {
		return currentFrame;
	}

	/** @return the last intact frame written to STREAM in this session, or null */
	public FaceFrame getLastServedFrame() {
		return lastServedFrame;
	}

	/** @return the frame used by the last capture, or null */
	public FaceFrame getLastCapturedFrame() {
		return lastCapturedFrame;
	}

	/** @return a copy of the current source settings */
	public synchronized FaceSourceSettings getSettings() {
		return settings.copy();
	}

	/** @return true while a session is open */
	public synchronized boolean isOpen() {
		return open;
	}

	/** @return the last source error message, or null */
	public String getLastError() {
		return lastError;
	}
}
