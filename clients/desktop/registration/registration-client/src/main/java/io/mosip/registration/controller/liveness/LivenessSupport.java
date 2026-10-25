/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

import static io.mosip.registration.constants.RegistrationConstants.APPLICATION_ID;
import static io.mosip.registration.constants.RegistrationConstants.APPLICATION_NAME;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Workflow;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.constants.RegistrationUIConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.context.SessionContext;
import io.mosip.registration.controller.BaseController;
import io.mosip.registration.controller.FXComponents;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.service.bio.liveness.LivenessAccountLock;
import io.mosip.registration.service.bio.liveness.LivenessOrchestrator;
import io.mosip.registration.service.bio.liveness.LivenessOutcome;
import io.mosip.registration.util.restclient.ServiceDelegateUtil;
import javafx.scene.Node;
import javafx.stage.Window;

/**
 * Face liveness steps shared by the capture and authentication screens. Holds no per-screen state: each screen
 * keeps its own {@link FaceLivenessStep}, so one window per screen is kept even though this bean is shared.
 */
@Component
public class LivenessSupport {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessSupport.class);

	@Autowired
	private LivenessOrchestrator livenessOrchestrator;

	@Autowired
	private LivenessAccountLock livenessAccountLock;

	@Autowired
	private FXComponents fXComponents;

	@Autowired
	private ServiceDelegateUtil serviceDelegateUtil;

	/**
	 * Returns the face liveness workflow of an operator or supervisor authentication.
	 *
	 * @param isPacketAuth {@code true} for packet authentication, {@code false} for end-of-day approval
	 * @param isReviewer   {@code true} for the reviewer (supervisor) step of packet authentication
	 * @return {@code packet_auth}, {@code exception_auth} or {@code eod_auth}
	 */
	public Workflow faceAuthWorkflow(boolean isPacketAuth, boolean isReviewer) {
		if (!isPacketAuth) {
			return Workflow.EOD_AUTH;
		}
		return isReviewer ? Workflow.EXCEPTION_AUTH : Workflow.PACKET_AUTH;
	}

	/**
	 * Returns the pass of the liveness session run for this user in the authentication workflow, if any.
	 *
	 * @param isPacketAuth {@code true} for packet authentication, {@code false} for end-of-day approval
	 * @param isReviewer   {@code true} for the reviewer (supervisor) step of packet authentication
	 * @param userId       the user being authenticated
	 * @return the context the liveness gate checks
	 */
	public LivenessContext contextFor(boolean isPacketAuth, boolean isReviewer, String userId) {
		return livenessOrchestrator.contextFor(faceAuthWorkflow(isPacketAuth, isReviewer), userId);
	}

	/**
	 * Tells whether a face capture in a workflow must pass face liveness first (read at each call, so a config sync
	 * applies to the next capture).
	 *
	 * @param workflow the liveness workflow
	 * @return {@code true} when liveness is on and the workflow's policy is not {@code OFF}
	 */
	public boolean isLivenessEnforced(Workflow workflow) {
		return livenessOrchestrator != null && livenessOrchestrator.isEnforced(workflow);
	}

	/**
	 * Opens the face liveness window for a capture. The screen runs its existing capture task from
	 * {@link LivenessDialog.Callbacks#captureReady}.
	 *
	 * @param workflow   the liveness workflow
	 * @param subjectRef registration ID (resident) or user ID (operator, supervisor)
	 * @param owner      window the dialog is modal to; {@code null} uses the main window
	 * @param callbacks  capture, pass and close handlers
	 * @return the open dialog
	 */
	public LivenessDialog openLivenessDialog(Workflow workflow, String subjectRef, Window owner,
			LivenessDialog.Callbacks callbacks) {
		LivenessDialog.Options options = new LivenessDialog.Options(workflow, subjectRef)
				.owner(owner != null ? owner : fXComponents.getStage())
				.language(ApplicationContext.applicationLanguage(),
						ApplicationContext.getInstance().isPrimaryLanguageRightToLeft())
				.onlineCheck(() -> serviceDelegateUtil.isNetworkAvailable());
		return LivenessDialog.open(livenessOrchestrator, options, callbacks);
	}

	/**
	 * Starts the face camera preview of an authentication screen. When liveness is enforced the liveness window
	 * shows the live preview (a plain stream would keep the camera busy), so the face capture starts instead.
	 *
	 * @param workflow    the liveness workflow of the screen
	 * @param captureFace the screen's face capture, which opens the liveness window
	 * @param plainStream the screen's camera stream
	 */
	public void startFaceStream(Workflow workflow, Runnable captureFace, Runnable plainStream) {
		(isLivenessEnforced(workflow) ? captureFace : plainStream).run();
	}

	/**
	 * Runs an operator or supervisor face authentication: behind face liveness when it is enforced for the
	 * workflow, else at once. With liveness, the window opens (one at a time per screen) and the screen's existing
	 * capture-and-match task runs once liveness passed; after the last failed attempt the account lock rule of the
	 * workflow applies.
	 *
	 * @param screen          the calling screen (shows the alerts)
	 * @param step            the screen's liveness state (its window is the one-at-a-time guard)
	 * @param workflow        {@code login_auth}, {@code packet_auth}, {@code exception_auth} or {@code eod_auth}
	 * @param userId          the user being authenticated
	 * @param anchor          a node of the calling screen (owner of the window)
	 * @param captureAndMatch the existing capture and 1:1 match task
	 */
	public void authenticateFace(BaseController screen, FaceLivenessStep step, Workflow workflow, String userId,
			Node anchor, Runnable captureAndMatch) {
		authenticateFace(step, workflow, userId, anchor, captureAndMatch,
				() -> applyLivenessLock(screen, workflow, userId));
	}

	/**
	 * Same as {@link #authenticateFace(BaseController, FaceLivenessStep, Workflow, String, Node, Runnable)} with
	 * the screen's own handling of a liveness failure that requires the account lock (operator login counts it as
	 * a failed login).
	 *
	 * @param step            the screen's liveness state
	 * @param workflow        the liveness workflow
	 * @param userId          the user being authenticated
	 * @param anchor          a node of the calling screen (owner of the window)
	 * @param captureAndMatch the existing capture and 1:1 match task
	 * @param onLockRequired  runs when the last attempt failed and {@code on_max_failure} asks for the lock
	 */
	public void authenticateFace(FaceLivenessStep step, Workflow workflow, String userId, Node anchor,
			Runnable captureAndMatch, Runnable onLockRequired) {
		if (!isLivenessEnforced(workflow)) {
			captureAndMatch.run();
			return;
		}
		if (step.isOpen()) {
			return;
		}
		step.dialog = openLivenessDialog(workflow, userId, windowOf(anchor), new LivenessDialog.Callbacks() {
			@Override
			public void captureReady(LivenessContext context) {
				// the capture picks up the pending pass of this session for the same user
				captureAndMatch.run();
			}

			@Override
			public void closedWithoutPass(Optional<LivenessOutcome> lastOutcome) {
				if (lastOutcome.isPresent() && lastOutcome.get().isLockRequired()) {
					onLockRequired.run();
				}
			}
		});
	}

	/**
	 * Starts the liveness window before a resident or onboarding face capture, when liveness is enforced for the
	 * workflow. The screen's capture task runs once liveness passed and sends {@link FaceLivenessStep#takePass}
	 * with its capture request; the liveness gate binds the capture to the session, so a face is never stored when
	 * liveness fails.
	 *
	 * @param step       the screen's liveness state
	 * @param workflow   {@code registration}, {@code exception_photo} or {@code onboard_auth}
	 * @param subjectRef registration ID (resident) or user ID (operator)
	 * @param anchor     a node of the calling screen (owner of the window)
	 * @param capture    the screen's capture task
	 * @param onClosed   runs when the window closed without a pass (re-enable the screen)
	 * @return {@code false} when liveness is not enforced: the screen captures as before
	 */
	public boolean startFaceLiveness(FaceLivenessStep step, Workflow workflow, String subjectRef, Node anchor,
			Runnable capture, Runnable onClosed) {
		if (!isLivenessEnforced(workflow)) {
			return false;
		}
		LOGGER.info(RegistrationConstants.REGISTRATION_CONTROLLER, APPLICATION_NAME, APPLICATION_ID,
				"Starting face liveness before the " + workflow.id() + " capture");
		SessionContext.setAutoLogout(false);
		step.dialog = openLivenessDialog(workflow, subjectRef, windowOf(anchor), new LivenessDialog.Callbacks() {
			@Override
			public void captureReady(LivenessContext context) {
				step.pass = context;
				capture.run();
			}

			@Override
			public void closedWithoutPass(Optional<LivenessOutcome> lastOutcome) {
				step.pass = null;
				SessionContext.setAutoLogout(true);
				onClosed.run();
			}
		});
		return true;
	}

	/**
	 * Shows the alert of a failed face capture task, unless the liveness window is open and shows the failure with
	 * its Retry button.
	 *
	 * @param screen  the calling screen (shows the alert)
	 * @param step    the screen's liveness state
	 * @param failure the exception of the capture task
	 */
	public void alertCaptureFailure(BaseController screen, FaceLivenessStep step, Throwable failure) {
		if (!step.isOpen()) {
			screen.generateAlert(RegistrationConstants.ERROR, RegistrationUIConstants.getMessageLanguageSpecific(
					isLivenessRefusal(failure) ? RegistrationUIConstants.LIVENESS_CAPTURE_REFUSED
							: RegistrationUIConstants.BIOMETRIC_SCANNING_ERROR));
		}
	}

	/** The liveness window and its pass of one screen; each screen holds its own. */
	public static final class FaceLivenessStep {

		private LivenessDialog dialog;
		private volatile LivenessContext pass;

		/** Creates the state of one screen. */
		public FaceLivenessStep() {
			// no window yet, no pass
		}

		/**
		 * Tells whether the liveness window is open.
		 *
		 * @return {@code true} while it is shown
		 */
		public boolean isOpen() {
			return dialog != null && dialog.isOpen();
		}

		/**
		 * Returns the pass of the liveness session once, for the capture request.
		 *
		 * @param workflow   the workflow of the capture
		 * @param subjectRef the subject of the capture
		 * @return the pass, or a context without a pass (accepted by the gate only when the policy is {@code OFF})
		 */
		public LivenessContext takePass(Workflow workflow, String subjectRef) {
			LivenessContext context = pass;
			pass = null;
			return context != null ? context : LivenessContext.withoutPass(workflow, subjectRef);
		}
	}

	private static Window windowOf(Node anchor) {
		return anchor != null && anchor.getScene() != null ? anchor.getScene().getWindow() : null;
	}

	/** Tells whether a capture failed because the face liveness gate refused it ({@code REG-LIV-*}). */
	private static boolean isLivenessRefusal(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t instanceof RegBaseCheckedException && ((RegBaseCheckedException) t).getErrorCode() != null
					&& ((RegBaseCheckedException) t).getErrorCode().startsWith("REG-LIV-")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Applies {@code on_max_failure=BLOCK_AND_LOCK} after a face authentication used up its liveness attempts: the
	 * failure counts as a failed login of that user (invalid-login count and lock time), and the alert says whether
	 * the account is now locked.
	 */
	private void applyLivenessLock(BaseController screen, Workflow workflow, String userId) {
		LivenessAccountLock.Result result = livenessAccountLock.apply(workflow, userId);
		if (result == LivenessAccountLock.Result.LOCKED) {
			screen.generateAlert(RegistrationConstants.ERROR, String.format("%s %s %s %s %s",
					RegistrationUIConstants.getMessageLanguageSpecific(RegistrationUIConstants.USER_ACCOUNT_LOCK_MESSAGE_NUMBER),
					ApplicationContext.getStringValueFromApplicationMap(RegistrationConstants.INVALID_LOGIN_COUNT),
					RegistrationUIConstants.getMessageLanguageSpecific(RegistrationUIConstants.USER_ACCOUNT_LOCK_MESSAGE),
					ApplicationContext.getStringValueFromApplicationMap(RegistrationConstants.INVALID_LOGIN_TIME),
					RegistrationUIConstants.getMessageLanguageSpecific(RegistrationUIConstants.USER_ACCOUNT_LOCK_MESSAGE_MINUTES)));
			return;
		}
		screen.generateAlert(RegistrationConstants.ERROR,
				RegistrationUIConstants.getMessageLanguageSpecific(RegistrationUIConstants.LIVENESS_MAX_ATTEMPTS_AUTH));
	}
}
