/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.mdm.dto;

import lombok.Data;

/**
 * Body of {@code POST /admin/fault}: sets or clears the mock face device's
 * fault (see LIVENESS-EXTENSIONS.md for each fault and parameter).
 */
@Data
public class FaceFaultRequest {
	/** Device type: Face or Biometric Device; optional. */
	private String type;

	/** Fault name, for example NONE, DISCONNECT, FREEZE, DROP_FRAMES, SLOW_FPS. */
	private String fault;

	/** Frames of each stream that pass unaltered before the fault starts. */
	private Integer afterFrames;

	/** Frames the fault lasts once started; 0 means until cleared. */
	private Integer frames;

	/** DROP_FRAMES: share of frames skipped, in (0, 1]. */
	private Double dropRatio;

	/** SLOW_FPS: frame rate, in (0, 60]. */
	private Double fps;

	/** Extra delay before every frame, in milliseconds; works with any fault. */
	private Long delayMs;

	/** STALL: length in milliseconds; 0 means until cleared. */
	private Long durationMs;

	/** BAD_CONTENT_LENGTH: bytes added to the true length (may be negative). */
	private Integer lengthDelta;
}
