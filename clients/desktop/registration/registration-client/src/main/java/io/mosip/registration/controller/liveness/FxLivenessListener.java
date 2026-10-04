/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.service.bio.liveness.LivenessOutcome;
import io.mosip.registration.service.bio.liveness.LivenessRunListener;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * Moves liveness callbacks from the liveness threads to the JavaFX application thread.
 *
 * <p>UI states, capture ready and session ends are forwarded in order. Preview frames are coalesced: the reader
 * thread decodes a frame only when the previous one has been drawn, so a slow screen never queues frames and never
 * delays frame analysis. Callbacks never block the calling liveness thread.
 */
final class FxLivenessListener implements LivenessRunListener {

	/** What the screen side receives, always on the UI thread. */
	interface Target {

		/**
		 * Draws a state.
		 *
		 * @param state the state
		 */
		void uiState(LivenessUiState state);

		/**
		 * Shows a preview frame.
		 *
		 * @param image the decoded frame
		 */
		void previewFrame(Image image);

		/**
		 * Starts the signed capture.
		 *
		 * @param context workflow, subject and pass token
		 */
		void captureReady(LivenessContext context);

		/**
		 * Handles the end of a session.
		 *
		 * @param outcome the outcome
		 */
		void sessionEnded(LivenessOutcome outcome);
	}

	private final Target target;
	private final Executor uiThread;
	private final AtomicBoolean framePending = new AtomicBoolean();
	private final AtomicReference<Image> latestFrame = new AtomicReference<>();
	private volatile boolean detached;

	/**
	 * Creates a bridge to the JavaFX application thread.
	 *
	 * @param target receiver on the UI thread
	 */
	FxLivenessListener(Target target) {
		this(target, Platform::runLater);
	}

	/**
	 * Creates a bridge with an explicit UI executor (tests).
	 *
	 * @param target   receiver
	 * @param uiThread executor that runs work on the UI thread
	 */
	FxLivenessListener(Target target, Executor uiThread) {
		this.target = Objects.requireNonNull(target, "target");
		this.uiThread = Objects.requireNonNull(uiThread, "uiThread");
	}

	/** Stops forwarding; later callbacks are dropped (the dialog closed). */
	void detach() {
		detached = true;
	}

	@Override
	public void onUiState(LivenessUiState state) {
		post(t -> t.uiState(state));
	}

	@Override
	public void onPreviewFrame(FaceFrame frame) {
		if (detached || !isEncoded(frame) || !framePending.compareAndSet(false, true)) {
			return;
		}
		Image image = new Image(new ByteArrayInputStream(frame.data()));
		if (image.isError()) {
			framePending.set(false);
			return;
		}
		latestFrame.set(image);
		uiThread.execute(() -> {
			framePending.set(false);
			Image next = latestFrame.getAndSet(null);
			if (!detached && next != null) {
				target.previewFrame(next);
			}
		});
	}

	@Override
	public void onCaptureReady(LivenessContext context) {
		post(t -> t.captureReady(context));
	}

	@Override
	public void onSessionEnded(LivenessOutcome outcome) {
		post(t -> t.sessionEnded(outcome));
	}

	private void post(Consumer<Target> call) {
		if (detached) {
			return;
		}
		uiThread.execute(() -> {
			if (!detached) {
				call.accept(target);
			}
		});
	}

	private static boolean isEncoded(FaceFrame frame) {
		return frame != null && frame.data() != null
				&& (frame.format() == PixelFormat.JPEG || frame.format() == PixelFormat.PNG);
	}
}
