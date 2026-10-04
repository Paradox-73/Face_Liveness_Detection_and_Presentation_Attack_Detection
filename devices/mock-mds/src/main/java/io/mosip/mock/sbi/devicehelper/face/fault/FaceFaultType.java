/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.mock.sbi.devicehelper.face.fault;

import java.util.Locale;

/**
 * Faults the mock face device can inject, set through {@code POST /admin/fault}.
 */
public enum FaceFaultType {
	/** No fault. */
	NONE,
	/** Close the STREAM connection cleanly (TCP FIN). Alias {@code FIN}. */
	DISCONNECT,
	/** Reset the STREAM connection (TCP RST). Alias {@code RST}. */
	RESET,
	/** Keep the connection open but send nothing. */
	STALL,
	/** Repeat one frame. */
	FREEZE,
	/** Skip a share of frames ({@code dropRatio}). Alias {@code DROP}. */
	DROP_FRAMES,
	/** Send JPEGs with damaged entropy-coded data. Alias {@code CORRUPT}. */
	CORRUPT_JPEG,
	/** Send JPEGs cut short (no end marker), with a matching Content-Length. */
	TRUNCATED_JPEG,
	/** Send parts whose bytes are not a JPEG at all. */
	NON_JPEG,
	/** Send a Content-Length that does not match the part. */
	BAD_CONTENT_LENGTH,
	/** Stream at a lower frame rate ({@code fps}). Aliases {@code SLOW}, {@code FPS=n}. */
	SLOW_FPS,
	/** Report the device Busy (same as {@code /admin/status Busy}). */
	BUSY,
	/** Report the device Not Ready (same as {@code /admin/status Not Ready}). */
	NOT_READY,
	/** Camera cannot be opened: STREAM fails with error 608 and capture times out. */
	UNAVAILABLE;

	/**
	 * Parses a fault name, ignoring case and treating '-' and ' ' as '_'.
	 * Aliases: FIN, CLOSE, RST, DROP, CORRUPT, GARBLED, TRUNCATE, SLOW, FPS (and
	 * {@code FPS=n}), CAMERA_UNAVAILABLE, CLEAR.
	 *
	 * @param value the name
	 * @return the fault type
	 * @throws IllegalArgumentException if the name is unknown
	 */
	public static FaceFaultType parse(String value) {
		if (value == null || value.isBlank())
			throw new IllegalArgumentException("fault is empty");
		String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		if (name.startsWith("FPS="))
			return SLOW_FPS;
		switch (name) {
		case "FIN":
		case "CLOSE":
			return DISCONNECT;
		case "RST":
			return RESET;
		case "DROP":
			return DROP_FRAMES;
		case "CORRUPT":
		case "GARBLED":
			return CORRUPT_JPEG;
		case "TRUNCATE":
		case "TRUNCATED":
			return TRUNCATED_JPEG;
		case "SLOW":
		case "FPS":
			return SLOW_FPS;
		case "CAMERA_UNAVAILABLE":
			return UNAVAILABLE;
		case "CLEAR":
			return NONE;
		default:
			return FaceFaultType.valueOf(name);
		}
	}

	/**
	 * Tells whether the fault changes the device status rather than the stream.
	 *
	 * @return true for BUSY and NOT_READY
	 */
	public boolean isStatusFault() {
		return this == BUSY || this == NOT_READY;
	}
}
