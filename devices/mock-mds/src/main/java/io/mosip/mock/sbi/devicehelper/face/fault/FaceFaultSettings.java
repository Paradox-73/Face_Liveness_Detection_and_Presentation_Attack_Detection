/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.fault;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One fault configuration: the fault type plus when and how strongly it
 * applies. Immutable; build with {@link #builder(FaceFaultType)}.
 */
public final class FaceFaultSettings {
	/** No fault and no extra delay. */
	public static final FaceFaultSettings NONE = builder(FaceFaultType.NONE).build();

	private static final long MAX_DELAY_MILLIS = 60_000L;
	private static final int MAX_LENGTH_DELTA = 1 << 20;

	private final FaceFaultType type;
	private final int afterFrames;
	private final int frames;
	private final double dropRatio;
	private final double fps;
	private final long delayMillis;
	private final long durationMillis;
	private final int lengthDelta;

	private FaceFaultSettings(Builder builder) {
		this.type = builder.type;
		this.afterFrames = builder.afterFrames;
		this.frames = builder.frames;
		this.dropRatio = builder.dropRatio;
		this.fps = builder.fps;
		this.delayMillis = builder.delayMillis;
		this.durationMillis = builder.durationMillis;
		this.lengthDelta = builder.lengthDelta;
	}

	/**
	 * Starts a builder with default parameters: afterFrames 0, frames 0 (until
	 * cleared), dropRatio 0.5, fps 2, delayMs 0, durationMs 0 (until cleared),
	 * lengthDelta 512.
	 *
	 * @param type the fault type
	 * @return the builder
	 */
	public static Builder builder(FaceFaultType type) {
		return new Builder(type);
	}

	/** @return the fault type */
	public FaceFaultType getType() {
		return type;
	}

	/** @return frames of the current stream that pass unaltered before the fault starts */
	public int getAfterFrames() {
		return afterFrames;
	}

	/** @return frames the fault lasts once started; 0 means until cleared */
	public int getFrames() {
		return frames;
	}

	/** @return share of frames skipped by DROP_FRAMES, in (0, 1] */
	public double getDropRatio() {
		return dropRatio;
	}

	/** @return frame rate used by SLOW_FPS */
	public double getFps() {
		return fps;
	}

	/** @return extra delay before every frame in milliseconds, with any fault type */
	public long getDelayMillis() {
		return delayMillis;
	}

	/** @return STALL length in milliseconds; 0 means until cleared or stream timeout */
	public long getDurationMillis() {
		return durationMillis;
	}

	/** @return bytes added to the true length by BAD_CONTENT_LENGTH (may be negative) */
	public int getLengthDelta() {
		return lengthDelta;
	}

	/**
	 * Describes the settings for the admin state response.
	 *
	 * @return a map of parameter names to values
	 */
	public Map<String, Object> describe() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("fault", type.name());
		map.put("afterFrames", afterFrames);
		map.put("frames", frames);
		map.put("delayMs", delayMillis);
		if (type == FaceFaultType.DROP_FRAMES)
			map.put("dropRatio", dropRatio);
		if (type == FaceFaultType.SLOW_FPS)
			map.put("fps", fps);
		if (type == FaceFaultType.STALL)
			map.put("durationMs", durationMillis);
		if (type == FaceFaultType.BAD_CONTENT_LENGTH)
			map.put("lengthDelta", lengthDelta);
		return map;
	}

	/** Builder for {@link FaceFaultSettings}; setters validate their argument. */
	public static final class Builder {
		private final FaceFaultType type;
		private int afterFrames;
		private int frames;
		private double dropRatio = 0.5;
		private double fps = 2.0;
		private long delayMillis;
		private long durationMillis;
		private int lengthDelta = 512;

		private Builder(FaceFaultType type) {
			if (type == null)
				throw new IllegalArgumentException("fault type is null");
			this.type = type;
		}

		/**
		 * @param value frames that pass unaltered first, at least 0
		 * @return this builder
		 */
		public Builder afterFrames(int value) {
			this.afterFrames = requireRange(value, 0, Integer.MAX_VALUE, "afterFrames");
			return this;
		}

		/**
		 * @param value frames the fault lasts, 0 for until cleared
		 * @return this builder
		 */
		public Builder frames(int value) {
			this.frames = requireRange(value, 0, Integer.MAX_VALUE, "frames");
			return this;
		}

		/**
		 * @param value share of frames to skip, in (0, 1]
		 * @return this builder
		 */
		public Builder dropRatio(double value) {
			if (!(value > 0.0) || value > 1.0)
				throw new IllegalArgumentException("dropRatio must be greater than 0 and at most 1");
			this.dropRatio = value;
			return this;
		}

		/**
		 * @param value SLOW_FPS frame rate, in (0, 60]
		 * @return this builder
		 */
		public Builder fps(double value) {
			if (!(value > 0.0) || value > 60.0)
				throw new IllegalArgumentException("fps must be greater than 0 and at most 60");
			this.fps = value;
			return this;
		}

		/**
		 * @param value extra delay before every frame, 0 to 60000 ms
		 * @return this builder
		 */
		public Builder delayMillis(long value) {
			if (value < 0 || value > MAX_DELAY_MILLIS)
				throw new IllegalArgumentException("delayMs must be between 0 and " + MAX_DELAY_MILLIS);
			this.delayMillis = value;
			return this;
		}

		/**
		 * @param value STALL length in ms, 0 for until cleared
		 * @return this builder
		 */
		public Builder durationMillis(long value) {
			if (value < 0)
				throw new IllegalArgumentException("durationMs must be at least 0");
			this.durationMillis = value;
			return this;
		}

		/**
		 * @param value bytes added to the true Content-Length, non-zero
		 * @return this builder
		 */
		public Builder lengthDelta(int value) {
			if (value == 0 || Math.abs(value) > MAX_LENGTH_DELTA)
				throw new IllegalArgumentException("lengthDelta must be non-zero and at most " + MAX_LENGTH_DELTA);
			this.lengthDelta = value;
			return this;
		}

		/** @return the settings */
		public FaceFaultSettings build() {
			return new FaceFaultSettings(this);
		}

		private static int requireRange(int value, int min, int max, String name) {
			if (value < min || value > max)
				throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
			return value;
		}
	}
}
