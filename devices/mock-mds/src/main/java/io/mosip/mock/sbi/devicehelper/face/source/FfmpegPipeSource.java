/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.source;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads frames from an ffmpeg child process: a webcam (dshow, v4l2 or
 * avfoundation) or a video file.
 *
 * <p>
 * ffmpeg is started with {@code -f image2pipe -vcodec mjpeg pipe:1}; its
 * standard output is split into JPEG images by {@link JpegStreamSplitter} on a
 * reader thread that keeps only the newest image, so a slow consumer never
 * builds a backlog. {@link #next()} returns the newest image not yet returned,
 * waiting up to {@code frameTimeoutMillis}; if none arrives but ffmpeg is still
 * running it repeats the last image, and once ffmpeg has exited it returns
 * {@code null}. ffmpeg is not bundled (it is GPL); the executable comes from
 * {@link FaceSourceSettings#getFfmpegPath()}.
 * </p>
 */
public class FfmpegPipeSource implements FaceFrameSource {
	/** Source type name. */
	public static final String NAME = "FFMPEG";

	private static final Logger LOGGER = LoggerFactory.getLogger(FfmpegPipeSource.class);
	private static final String[] VIRTUAL_CAMERA_MARKERS = { "obs", "manycam", "xsplit", "virtual" };
	private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
	private static final int STDERR_LINES_KEPT = 20;

	private final FaceSourceSettings settings;
	private final long openTimeoutMillis;
	private final long frameTimeoutMillis;
	private final Object lock = new Object();
	private final Deque<String> stderrTail = new ArrayDeque<>();

	private Process process;
	private Thread readerThread;
	private Thread stderrThread;
	private byte[] latest;
	private long latestNumber;
	private long lastReturnedNumber;
	private long sequence;
	private boolean ended;
	private boolean closed;

	/**
	 * Creates the source.
	 *
	 * @param settings           FFMPEG settings: camera, file or raw input
	 *                           arguments, fps, width, ffmpeg path
	 * @param openTimeoutMillis  longest wait for the first frame in
	 *                           {@link #open()}
	 * @param frameTimeoutMillis longest wait for a new frame in {@link #next()}
	 */
	public FfmpegPipeSource(FaceSourceSettings settings, long openTimeoutMillis, long frameTimeoutMillis) {
		this.settings = settings.copy();
		FaceSourceSettings.checkFps(settings.getFps());
		this.openTimeoutMillis = openTimeoutMillis;
		this.frameTimeoutMillis = frameTimeoutMillis;
	}

	/**
	 * Builds the ffmpeg command line for the settings.
	 *
	 * @param settings the settings
	 * @param osName   value of the {@code os.name} system property, used to pick
	 *                 the default webcam input format
	 * @return the command and its arguments
	 * @throws IllegalArgumentException if the settings name no input, or the
	 *                                  camera name is refused
	 */
	public static List<String> buildCommand(FaceSourceSettings settings, String osName) {
		List<String> cmd = new ArrayList<>(List.of(settings.getFfmpegPath(), "-hide_banner", "-loglevel", "error",
				"-nostdin"));
		if (!settings.getInputArgs().isEmpty()) {
			cmd.addAll(settings.getInputArgs());
		} else if (settings.getCamera() != null) {
			checkCameraName(settings.getCamera(), settings.isAllowVirtualCamera());
			cmd.addAll(cameraInput(settings, osName));
		} else if (settings.getFile() != null) {
			cmd.add("-re");
			if (settings.isLoop())
				cmd.addAll(List.of("-stream_loop", "-1"));
			cmd.addAll(List.of("-i", settings.getFile()));
		} else {
			throw new IllegalArgumentException("FFMPEG source needs a camera, a file or input arguments");
		}
		String filter = "fps=" + BigDecimal.valueOf(settings.getFps()).stripTrailingZeros().toPlainString()
				+ ",scale=" + settings.getWidth() + ":-2";
		cmd.addAll(List.of("-an", "-vf", filter, "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "4", "pipe:1"));
		return cmd;
	}

	/**
	 * Returns the default webcam input format for an operating system.
	 *
	 * @param osName value of the {@code os.name} system property
	 * @return {@code dshow}, {@code avfoundation} or {@code v4l2}
	 */
	public static String defaultInputFormat(String osName) {
		String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		if (os.contains("win"))
			return "dshow";
		if (os.contains("mac"))
			return "avfoundation";
		return "v4l2";
	}

	private static List<String> cameraInput(FaceSourceSettings settings, String osName) {
		String format = settings.getInputFormat() == null ? defaultInputFormat(osName)
				: settings.getInputFormat().toLowerCase(Locale.ROOT);
		switch (format) {
		case "dshow":
			return List.of("-f", "dshow", "-rtbufsize", "64M", "-i", "video=" + settings.getCamera());
		case "v4l2":
			return List.of("-f", "v4l2", "-i", settings.getCamera());
		case "avfoundation":
			return List.of("-f", "avfoundation", "-framerate", "30", "-i", settings.getCamera());
		default:
			throw new IllegalArgumentException("inputFormat must be dshow, v4l2 or avfoundation");
		}
	}

	/**
	 * Refuses camera names that could break the command line, and names that
	 * look like virtual cameras (OBS, ManyCam, XSplit, "Virtual") unless allowed.
	 * A virtual camera lets any video be injected as if it were the sensor.
	 *
	 * @param camera       the device name
	 * @param allowVirtual true to accept virtual camera names
	 * @throws IllegalArgumentException if the name is refused
	 */
	public static void checkCameraName(String camera, boolean allowVirtual) {
		if (camera.isBlank() || camera.length() > 200 || camera.chars().anyMatch(c -> c == '"' || c < 0x20))
			throw new IllegalArgumentException("camera name is empty, too long or has quotes or control characters");
		String lower = camera.toLowerCase(Locale.ROOT);
		for (String marker : VIRTUAL_CAMERA_MARKERS) {
			if (!allowVirtual && lower.contains(marker))
				throw new IllegalArgumentException("camera '" + camera
						+ "' looks like a virtual camera; set mosip.mock.sbi.face.ffmpeg.allow.virtual.camera=true to use it");
		}
	}

	/** {@inheritDoc} */
	@Override
	public void open() throws IOException {
		List<String> cmd = buildCommand(settings, System.getProperty("os.name"));
		LOGGER.info("Starting ffmpeg frame source :: {}", cmd);
		synchronized (lock) {
			closed = false;
			ended = false;
			latest = null;
			latestNumber = 0;
			lastReturnedNumber = 0;
			sequence = 0;
			stderrTail.clear();
		}
		Process started = new ProcessBuilder(cmd).start();
		started.getOutputStream().close();
		synchronized (lock) {
			process = started;
		}
		readerThread = startDaemon("ffmpeg-frames", () -> readFrames(started.getInputStream()));
		stderrThread = startDaemon("ffmpeg-stderr", () -> readErrors(started.getErrorStream()));
		if (!awaitFirstFrame()) {
			String detail = getStderrTail();
			close();
			throw new IOException("ffmpeg produced no frame within " + openTimeoutMillis + " ms: " + detail);
		}
	}

	private boolean awaitFirstFrame() throws IOException {
		long deadline = System.currentTimeMillis() + openTimeoutMillis;
		synchronized (lock) {
			while (latest == null && !ended && !closed) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0)
					break;
				waitOnLock(remaining);
			}
			return latest != null;
		}
	}

	/** {@inheritDoc} */
	@Override
	public FaceFrame next() throws IOException {
		long deadline = System.currentTimeMillis() + frameTimeoutMillis;
		synchronized (lock) {
			while (!closed && !ended && latestNumber <= lastReturnedNumber) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0)
					break;
				waitOnLock(remaining);
			}
			if (closed || latest == null || (ended && latestNumber <= lastReturnedNumber))
				return null;
			lastReturnedNumber = latestNumber;
			return new FaceFrame(latest, sequence++, System.currentTimeMillis(), NAME);
		}
	}

	private void waitOnLock(long millis) throws IOException {
		try {
			lock.wait(millis);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while waiting for an ffmpeg frame", ex);
		}
	}

	private void readFrames(InputStream stdout) {
		JpegStreamSplitter splitter = new JpegStreamSplitter(MAX_FRAME_BYTES);
		try (InputStream in = new BufferedInputStream(stdout, 256 * 1024)) {
			byte[] image;
			while ((image = splitter.readNext(in)) != null) {
				synchronized (lock) {
					latest = image;
					latestNumber++;
					lock.notifyAll();
				}
			}
		} catch (IOException ex) {
			LOGGER.debug("ffmpeg frame reader stopped :: {}", ex.getMessage());
		} finally {
			synchronized (lock) {
				ended = true;
				lock.notifyAll();
			}
		}
	}

	private void readErrors(InputStream stderr) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				synchronized (stderrTail) {
					if (stderrTail.size() == STDERR_LINES_KEPT)
						stderrTail.removeFirst();
					stderrTail.addLast(line);
				}
			}
		} catch (IOException ex) {
			LOGGER.debug("ffmpeg stderr reader stopped :: {}", ex.getMessage());
		}
	}

	/**
	 * Returns the last lines ffmpeg wrote to its standard error.
	 *
	 * @return the lines joined with " | ", or an empty string
	 */
	public String getStderrTail() {
		synchronized (stderrTail) {
			return String.join(" | ", stderrTail);
		}
	}

	/**
	 * Tells whether the ffmpeg process is running.
	 *
	 * @return true while ffmpeg runs
	 */
	public boolean isRunning() {
		synchronized (lock) {
			return process != null && process.isAlive();
		}
	}

	/** {@inheritDoc} */
	@Override
	public double getFps() {
		return settings.getFps();
	}

	/** {@inheritDoc} */
	@Override
	public String getName() {
		return NAME;
	}

	/** {@inheritDoc} */
	@Override
	public Map<String, Object> describe() {
		Map<String, Object> map = new LinkedHashMap<>(settings.describe());
		map.put("running", isRunning());
		return map;
	}

	/** {@inheritDoc} */
	@Override
	public void close() {
		Process toStop;
		synchronized (lock) {
			closed = true;
			lock.notifyAll();
			toStop = process;
			process = null;
		}
		if (toStop != null) {
			toStop.destroy();
			try {
				if (!toStop.waitFor(2, TimeUnit.SECONDS))
					toStop.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		joinQuietly(readerThread);
		joinQuietly(stderrThread);
	}

	private static Thread startDaemon(String name, Runnable task) {
		Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	private static void joinQuietly(Thread thread) {
		if (thread == null || thread == Thread.currentThread())
			return;
		try {
			thread.join(2000);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
