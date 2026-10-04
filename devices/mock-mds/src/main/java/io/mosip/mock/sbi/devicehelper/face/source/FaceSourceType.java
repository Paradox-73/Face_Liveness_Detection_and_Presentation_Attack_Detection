/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.source;

import java.util.Locale;

/**
 * The kinds of frame source the mock face device can stream from.
 */
public enum FaceSourceType {
	/** One JPEG file served as every frame (stock MockMDS behaviour). */
	STATIC,
	/** A folder of JPEG files played in name order. */
	SEQUENCE,
	/** Frames read from an ffmpeg process (webcam or video file). */
	FFMPEG;

	/**
	 * Parses a type name, ignoring case. {@code IMAGE_SEQUENCE}, {@code WEBCAM}
	 * and {@code VIDEO} are accepted as aliases.
	 *
	 * @param value the name
	 * @return the type
	 * @throws IllegalArgumentException if the name is unknown
	 */
	public static FaceSourceType parse(String value) {
		if (value == null || value.isBlank())
			throw new IllegalArgumentException("source type is empty");
		String name = value.trim().toUpperCase(Locale.ROOT);
		switch (name) {
		case "IMAGE_SEQUENCE":
		case "FOLDER":
			return SEQUENCE;
		case "WEBCAM":
		case "CAMERA":
		case "VIDEO":
			return FFMPEG;
		default:
			return FaceSourceType.valueOf(name);
		}
	}
}
