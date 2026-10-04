/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.source;

import java.nio.file.Path;

/**
 * Creates {@link FaceFrameSource} objects from {@link FaceSourceSettings}.
 */
public final class FaceFrameSources {
	/** Longest wait for ffmpeg's first frame; webcams can take a few seconds. */
	public static final long FFMPEG_OPEN_TIMEOUT_MILLIS = 10_000L;
	/** Longest wait for a new ffmpeg frame before the last one is repeated. */
	public static final long FFMPEG_FRAME_TIMEOUT_MILLIS = 2_000L;

	private FaceFrameSources() {
		throw new IllegalStateException("FaceFrameSources class");
	}

	/**
	 * Creates an unopened source.
	 *
	 * @param settings         the settings
	 * @param workingDirectory folder that relative paths are resolved against
	 * @param defaultImage     JPEG served by STATIC when the settings name no
	 *                         path (the stock stream image)
	 * @return the source; call {@link FaceFrameSource#open()} before use
	 * @throws IllegalArgumentException if required settings are missing
	 */
	public static FaceFrameSource create(FaceSourceSettings settings, Path workingDirectory, Path defaultImage) {
		switch (settings.getType()) {
		case SEQUENCE:
			if (settings.getPath() == null)
				throw new IllegalArgumentException("SEQUENCE source needs a folder path");
			return new ImageSequenceSource(resolve(workingDirectory, settings.getPath()), settings.getFps(),
					settings.isLoop());
		case FFMPEG:
			FaceSourceSettings ffmpeg = settings.copy();
			if (ffmpeg.getFile() != null)
				ffmpeg.setFile(resolve(workingDirectory, ffmpeg.getFile()).toString());
			return new FfmpegPipeSource(ffmpeg, FFMPEG_OPEN_TIMEOUT_MILLIS, FFMPEG_FRAME_TIMEOUT_MILLIS);
		case STATIC:
		default:
			Path image = settings.getPath() == null ? defaultImage : resolve(workingDirectory, settings.getPath());
			return new StaticImageSource(image, settings.getFps());
		}
	}

	/**
	 * Resolves a path against the working directory unless it is absolute.
	 *
	 * @param workingDirectory base folder
	 * @param path             absolute or relative path
	 * @return the normalised absolute path
	 */
	public static Path resolve(Path workingDirectory, String path) {
		Path given = Path.of(path);
		return (given.isAbsolute() ? given : workingDirectory.resolve(given)).toAbsolutePath().normalize();
	}
}
