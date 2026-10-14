/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.source;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Settings that select and configure a {@link FaceFrameSource}. Filled from
 * {@code application.properties} at start-up and from {@code POST /admin/source}
 * at run time.
 */
public class FaceSourceSettings {
	/** Property: source type, STATIC, SEQUENCE or FFMPEG. */
	public static final String KEY_TYPE = "mosip.mock.sbi.face.source.type";
	/** Property: frames per second. */
	public static final String KEY_FPS = "mosip.mock.sbi.face.source.fps";
	/** Property: JPEG file (STATIC) or folder of JPEG files (SEQUENCE). */
	public static final String KEY_PATH = "mosip.mock.sbi.face.source.path";
	/** Property: restart SEQUENCE or FFMPEG file input at the end. */
	public static final String KEY_LOOP = "mosip.mock.sbi.face.source.loop";
	/** Property: webcam device name for FFMPEG. */
	public static final String KEY_CAMERA = "mosip.mock.sbi.face.source.camera";
	/** Property: video file for FFMPEG. */
	public static final String KEY_FILE = "mosip.mock.sbi.face.source.file";
	/** Property: ffmpeg input format for the webcam (dshow, v4l2, avfoundation). */
	public static final String KEY_INPUT_FORMAT = "mosip.mock.sbi.face.source.input.format";
	/** Property: output frame width for FFMPEG; height keeps the aspect ratio. */
	public static final String KEY_WIDTH = "mosip.mock.sbi.face.source.width";
	/** Property: ffmpeg executable. */
	public static final String KEY_FFMPEG_PATH = "mosip.mock.sbi.face.ffmpeg.path";
	/** Property: allow webcam names that look like virtual cameras. */
	public static final String KEY_ALLOW_VIRTUAL = "mosip.mock.sbi.face.ffmpeg.allow.virtual.camera";
	/** Property: raw ffmpeg input arguments, separated by whitespace. */
	public static final String KEY_INPUT_ARGS = "mosip.mock.sbi.face.ffmpeg.input.args";

	/** Default frame rate: enough for blink detection (10 fps) with margin. */
	public static final double DEFAULT_FPS = 15.0;
	/** Default FFMPEG output width. */
	public static final int DEFAULT_WIDTH = 640;
	/** Highest accepted frame rate. */
	public static final double MAX_FPS = 60.0;

	private FaceSourceType type = FaceSourceType.STATIC;
	private String path;
	private double fps = DEFAULT_FPS;
	private boolean loop = true;
	private boolean pingpong;
	private String camera;
	private String file;
	private String inputFormat;
	private int width = DEFAULT_WIDTH;
	private String ffmpegPath = "ffmpeg";
	private boolean allowVirtualCamera;
	private List<String> inputArgs = new ArrayList<>();

	/**
	 * Reads settings from properties. Missing or empty keys keep their defaults.
	 *
	 * @param properties function that returns a property value or null
	 * @return the settings
	 */
	public static FaceSourceSettings fromProperties(UnaryOperator<String> properties) {
		FaceSourceSettings settings = new FaceSourceSettings();
		String value = trimmed(properties.apply(KEY_TYPE));
		if (value != null)
			settings.setType(FaceSourceType.parse(value));
		value = trimmed(properties.apply(KEY_FPS));
		if (value != null)
			settings.setFps(Double.parseDouble(value));
		settings.setPath(trimmed(properties.apply(KEY_PATH)));
		value = trimmed(properties.apply(KEY_LOOP));
		if (value != null)
			settings.setLoop(Boolean.parseBoolean(value));
		settings.setCamera(trimmed(properties.apply(KEY_CAMERA)));
		settings.setFile(trimmed(properties.apply(KEY_FILE)));
		settings.setInputFormat(trimmed(properties.apply(KEY_INPUT_FORMAT)));
		value = trimmed(properties.apply(KEY_WIDTH));
		if (value != null)
			settings.setWidth(Integer.parseInt(value));
		value = trimmed(properties.apply(KEY_FFMPEG_PATH));
		if (value != null)
			settings.setFfmpegPath(value);
		value = trimmed(properties.apply(KEY_ALLOW_VIRTUAL));
		if (value != null)
			settings.setAllowVirtualCamera(Boolean.parseBoolean(value));
		value = trimmed(properties.apply(KEY_INPUT_ARGS));
		if (value != null)
			settings.setInputArgs(new ArrayList<>(Arrays.asList(value.split("\\s+"))));
		return settings;
	}

	/**
	 * Checks a frame rate.
	 *
	 * @param fps frames per second
	 * @return the same value
	 * @throws IllegalArgumentException if not in (0, {@value #MAX_FPS}]
	 */
	public static double checkFps(double fps) {
		if (!(fps > 0.0) || fps > MAX_FPS)
			throw new IllegalArgumentException("fps must be greater than 0 and at most " + MAX_FPS);
		return fps;
	}

	/**
	 * Returns a copy of these settings.
	 *
	 * @return the copy
	 */
	public FaceSourceSettings copy() {
		FaceSourceSettings copy = new FaceSourceSettings();
		copy.type = type;
		copy.path = path;
		copy.fps = fps;
		copy.loop = loop;
		copy.pingpong = pingpong;
		copy.camera = camera;
		copy.file = file;
		copy.inputFormat = inputFormat;
		copy.width = width;
		copy.ffmpegPath = ffmpegPath;
		copy.allowVirtualCamera = allowVirtualCamera;
		copy.inputArgs = new ArrayList<>(inputArgs);
		return copy;
	}

	/**
	 * Describes the settings for the admin state response.
	 *
	 * @return a map of setting names to values
	 */
	public Map<String, Object> describe() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("source", type.name());
		map.put("fps", fps);
		putIfSet(map, "path", path);
		putIfSet(map, "camera", camera);
		putIfSet(map, "file", file);
		putIfSet(map, "inputFormat", inputFormat);
		if (type != FaceSourceType.STATIC)
			map.put("loop", loop);
		if (type == FaceSourceType.SEQUENCE)
			map.put("pingpong", pingpong);
		if (type == FaceSourceType.FFMPEG)
			map.put("width", width);
		return map;
	}

	private static void putIfSet(Map<String, Object> map, String key, String value) {
		if (value != null)
			map.put(key, value);
	}

	private static String trimmed(String value) {
		if (value == null)
			return null;
		String trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\""))
			trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** @return the source type */
	public FaceSourceType getType() {
		return type;
	}

	/** @param type the source type */
	public void setType(FaceSourceType type) {
		this.type = type;
	}

	/** @return JPEG file (STATIC) or folder (SEQUENCE); null for the default */
	public String getPath() {
		return path;
	}

	/** @param path JPEG file (STATIC) or folder (SEQUENCE) */
	public void setPath(String path) {
		this.path = path;
	}

	/** @return frames per second */
	public double getFps() {
		return fps;
	}

	/** @param fps frames per second, in (0, 60] */
	public void setFps(double fps) {
		this.fps = checkFps(fps);
	}

	/** @return true when SEQUENCE or FFMPEG file input restarts at the end */
	public boolean isLoop() {
		return loop;
	}

	/** @param loop restart at the end */
	public void setLoop(boolean loop) {
		this.loop = loop;
	}

	/** @return true when SEQUENCE plays backwards at the end, then forwards again */
	public boolean isPingpong() {
		return pingpong;
	}

	/** @param pingpong play SEQUENCE backwards at the end, then forwards again */
	public void setPingpong(boolean pingpong) {
		this.pingpong = pingpong;
	}

	/** @return webcam device name for FFMPEG */
	public String getCamera() {
		return camera;
	}

	/** @param camera webcam device name for FFMPEG */
	public void setCamera(String camera) {
		this.camera = camera;
	}

	/** @return video file for FFMPEG */
	public String getFile() {
		return file;
	}

	/** @param file video file for FFMPEG */
	public void setFile(String file) {
		this.file = file;
	}

	/** @return ffmpeg webcam input format, or null for the platform default */
	public String getInputFormat() {
		return inputFormat;
	}

	/** @param inputFormat ffmpeg webcam input format */
	public void setInputFormat(String inputFormat) {
		this.inputFormat = inputFormat;
	}

	/** @return FFMPEG output width in pixels */
	public int getWidth() {
		return width;
	}

	/** @param width FFMPEG output width in pixels, 16 to 4096 */
	public void setWidth(int width) {
		if (width < 16 || width > 4096)
			throw new IllegalArgumentException("width must be between 16 and 4096");
		this.width = width;
	}

	/** @return ffmpeg executable */
	public String getFfmpegPath() {
		return ffmpegPath;
	}

	/** @param ffmpegPath ffmpeg executable */
	public void setFfmpegPath(String ffmpegPath) {
		this.ffmpegPath = ffmpegPath;
	}

	/** @return true when webcam names that look like virtual cameras are allowed */
	public boolean isAllowVirtualCamera() {
		return allowVirtualCamera;
	}

	/** @param allowVirtualCamera allow virtual camera names */
	public void setAllowVirtualCamera(boolean allowVirtualCamera) {
		this.allowVirtualCamera = allowVirtualCamera;
	}

	/** @return raw ffmpeg input arguments; when set they replace camera and file */
	public List<String> getInputArgs() {
		return inputArgs;
	}

	/** @param inputArgs raw ffmpeg input arguments */
	public void setInputArgs(List<String> inputArgs) {
		this.inputArgs = inputArgs == null ? new ArrayList<>() : new ArrayList<>(inputArgs);
	}
}
