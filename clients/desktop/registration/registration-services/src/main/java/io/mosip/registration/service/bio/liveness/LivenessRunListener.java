/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.registration.mdm.dto.LivenessContext;

/**
 * What a screen receives from a {@link LivenessRun}. The services never touch JavaFX: every callback comes from a
 * liveness thread, and the screen hands UI work to its own thread (on the desktop,
 * {@code Platform.runLater}). Callbacks must return quickly and must not block.
 */
public interface LivenessRunListener {

	/**
	 * Receives the next state to draw: phase, message key and arguments, progress, prompt icon, attempt counter,
	 * retry and override availability. Called on the {@code liveness-worker} thread.
	 *
	 * @param state the state
	 */
	void onUiState(LivenessUiState state);

	/**
	 * Receives a preview frame (JPEG) for the live preview. Called on the {@code sbi-stream-reader} thread for every
	 * frame; keep only the latest and draw it from the UI thread. The data array is a private copy.
	 *
	 * @param frame the frame
	 */
	default void onPreviewFrame(FaceFrame frame) {
	}

	/**
	 * Tells the screen that liveness passed and the signed capture may start now: put the context on the capture
	 * request ({@code MDMRequestDto.setLivenessContext}) and call the existing capture service. For
	 * authentication workflows the stream is already closed and the device has reported Ready. Called on the
	 * {@code liveness-worker} thread.
	 *
	 * @param context workflow, subject and single-use pass token
	 */
	default void onCaptureReady(LivenessContext context) {
	}

	/**
	 * Tells the screen that a session ended: passed (after the capture was bound), failed, cancelled, or could not
	 * start. The outcome says whether a retry is available, whether the attempts are used up and what the
	 * {@code on_max_failure} rule requires. Called on the {@code liveness-worker} thread.
	 *
	 * @param outcome the outcome
	 */
	default void onSessionEnded(LivenessOutcome outcome) {
	}
}
