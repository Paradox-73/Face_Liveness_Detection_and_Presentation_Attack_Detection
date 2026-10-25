/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.controller.liveness;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.context.SessionContext;
import io.mosip.registration.dto.RegistrationDTO;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.service.bio.liveness.LivenessOrchestrator;
import io.mosip.registration.service.bio.liveness.LivenessOutcome;
import io.mosip.registration.service.bio.liveness.LivenessRequest;
import io.mosip.registration.service.bio.liveness.LivenessRun;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * One face liveness verification on screen: a modal window with a {@link LivenessPanel}, driven by a
 * {@link LivenessRun} of the {@link LivenessOrchestrator}.
 *
 * <p>Used by every face capture of the desktop client (resident registration, operator onboarding, login, operator
 * packet authentication, reviewer and end-of-day supervisor authentication). The screen keeps its own capture and
 * matching code; it only moves the capture call into {@link Callbacks#captureReady(LivenessContext)}, which runs
 * after liveness passed. The liveness gate in the service layer then binds the signed capture to the session, so a
 * face is never accepted when liveness fails.
 *
 * <p>Flow: open the window, start the stream and the session (passive, then active challenges if the policy asks),
 * capture on pass, show "Good, face captured successfully" and close. On failure the panel shows the guidance and a
 * Retry button while the policy allows another attempt; after the last attempt it shows the recovery message, and
 * for resident workflows a supervisor override when the policy allows it.
 *
 * <p>Supervisor override: after the confirmation, a supervisor who is not the logged-in operator enters a user ID and
 * passes the client's supervisor face authentication (face liveness under the {@code exception_auth} policy, then
 * the 1:1 match). Only then is the override granted ({@link SupervisorOverrideFlow}); the photo taken next is
 * checked for one face and passive PAD before it is stored.
 *
 * <p>Window size: the panel sits in a scroll area, and the window is never taller than the visual bounds of the
 * operator's monitor ({@link LivenessDialogSizing}); on a small screen the content scrolls and Retry and Close stay
 * reachable.
 *
 * <p>Languages: all texts are in the operator's display language. For a resident capture whose first registration
 * language differs from it, the prompt the resident must follow is also shown in the resident's language, as a
 * second, larger line ({@link LivenessViewModel#residentLanguage(boolean, String, String)}).
 *
 * <p>All methods run on the JavaFX application thread.
 */
public final class LivenessDialog {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessDialog.class);
	private static final Duration SUCCESS_DISPLAY = Duration.millis(900);
	/** Room for the vertical scroll bar when the content scrolls (modena scroll bars are about 15 px wide). */
	private static final double SCROLL_BAR_WIDTH = 16;
	private static final String SCROLL_STYLE = "-fx-background-color: transparent; -fx-background-insets: 0;"
			+ " -fx-padding: 0;";

	/** What the screen does at each step; called on the JavaFX application thread. */
	public interface Callbacks {

		/**
		 * Liveness passed (or a supervisor override was granted): put the context on the capture request and run the
		 * screen's existing capture task now. For authentication workflows the stream is already closed and the
		 * device is Ready.
		 *
		 * @param context workflow, subject and single-use pass token
		 */
		void captureReady(LivenessContext context);

		/** The capture was bound to a passing session; the window has closed. */
		default void passed() {
		}

		/**
		 * The window closed without a pass: cancelled by the operator, closed after the last attempt, or no session
		 * could start.
		 *
		 * @param lastOutcome the last session outcome, if a session ended; {@link LivenessOutcome#isLockRequired()}
		 *                    tells authentication screens to apply the invalid-login rule
		 */
		default void closedWithoutPass(Optional<LivenessOutcome> lastOutcome) {
		}
	}

	/** Authenticates the supervisor who approves an override; called on the JavaFX application thread. */
	public interface SupervisorAuthenticator {

		/**
		 * Authenticates a supervisor.
		 *
		 * @param userId       the user ID entered for the override
		 * @param orchestrator the liveness services
		 * @param parent       the options of the liveness window that asks (language, direction)
		 * @param owner        the liveness window
		 * @param done         receives {@code true} on the JavaFX application thread when the supervisor's face
		 *                     passed liveness and matched; {@code false} otherwise
		 */
		void authenticate(String userId, LivenessOrchestrator orchestrator, Options parent, Window owner,
				Consumer<Boolean> done);
	}

	private final LivenessOrchestrator orchestrator;
	private final Options options;
	private final Callbacks callbacks;
	private final LivenessTexts texts;
	private final LivenessTexts residentTexts;
	private final boolean residentRightToLeft;
	private final LivenessPanel panel;
	private final ScrollPane scroller;
	private final FxLivenessListener listener;
	private Stage stage;
	private LivenessRun run;
	private LivenessOutcome lastOutcome;
	private LivenessUiState lastState;
	private boolean passed;
	private boolean closed;
	private boolean overrideRunning;

	private LivenessDialog(LivenessOrchestrator orchestrator, Options options, Callbacks callbacks) {
		this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
		this.options = Objects.requireNonNull(options, "options");
		this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
		this.texts = textsFor(options.languageCode).withOverrides(orchestrator.config(), options.languageCode);
		Optional<String> residentLanguage = LivenessViewModel.residentLanguage(options.workflow.isResident(),
				options.languageCode, options.residentLanguage());
		this.residentTexts = residentLanguage
				.map(lang -> textsFor(lang).withOverrides(orchestrator.config(), lang)).orElse(null);
		this.residentRightToLeft = residentLanguage.map(LivenessDialog::isRightToLeft).orElse(false);
		this.panel = new LivenessPanel();
		this.scroller = new ScrollPane(panel);
		this.listener = new FxLivenessListener(new PanelTarget());
	}

	/**
	 * Opens the window and starts the liveness run.
	 *
	 * @param orchestrator the liveness services
	 * @param options      workflow, subject and window options
	 * @param callbacks    what the screen does at capture ready, pass and close
	 * @return the open dialog
	 */
	public static LivenessDialog open(LivenessOrchestrator orchestrator, Options options, Callbacks callbacks) {
		LivenessDialog dialog = new LivenessDialog(orchestrator, options, callbacks);
		dialog.show();
		return dialog;
	}

	/**
	 * Tells whether the window is still open.
	 *
	 * @return {@code true} until the dialog closes
	 */
	public boolean isOpen() {
		return !closed;
	}

	/**
	 * Returns the panel (for screenshots and tests).
	 *
	 * @return the panel
	 */
	public LivenessPanel panel() {
		return panel;
	}

	/** Cancels the running session and closes the window, as the Cancel button does. */
	public void cancel() {
		if (closed) {
			return;
		}
		if (run != null) {
			run.cancel();
		}
		finish();
		callbacks.closedWithoutPass(Optional.ofNullable(lastOutcome));
	}

	private void show() {
		String title = texts.text(options.titleKey);
		panel.setTexts(texts, title);
		panel.setOnRetry(this::retry);
		panel.setOnCancel(this::cancel);
		panel.setOnOverride(this::override);
		panel.setResidentDirection(residentRightToLeft);
		panel.render(LivenessViewModel.starting(texts));
		scroller.setFitToWidth(true);
		scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		scroller.setStyle(SCROLL_STYLE);

		stage = new Stage();
		stage.setTitle(title);
		stage.setResizable(false);
		if (options.owner != null) {
			stage.initOwner(options.owner);
			stage.initModality(Modality.WINDOW_MODAL);
		} else {
			stage.initModality(Modality.APPLICATION_MODAL);
		}
		Scene scene = new Scene(scroller);
		scene.setNodeOrientation(options.rightToLeft ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
		stage.setScene(scene);
		stage.setOnCloseRequest(event -> cancel());
		SessionContext.setAutoLogout(false);
		stage.show();
		fitWindow();

		checkOnline();
		start();
	}

	private void start() {
		LivenessRequest request = LivenessRequest.builder(options.workflow, options.subjectRef).build();
		try {
			run = orchestrator.start(request, listener);
			updateDiagnostic();
		} catch (RegBaseCheckedException e) {
			LOGGER.error("Face liveness could not start: {}", e.getErrorCode());
			showFinalMessage(startRefusal(e.getErrorCode()));
		} catch (RuntimeException e) {
			LOGGER.error("Face liveness could not start: {}", e.getClass().getSimpleName());
			showFinalMessage(LivenessViewModel.error(MessageKeys.ERROR_ENGINE, texts, PromptIcon.ERROR));
		}
	}

	/** Shows a message after which only Close is left, with the Close button in view. */
	private void showFinalMessage(LivenessViewModel model) {
		panel.render(model);
		panel.setCancelText(texts.text(LivenessTexts.UI_CLOSE));
		fitWindow();
		// on a small screen the content scrolls: show the message's end and the Close button
		scroller.setVvalue(scroller.getVmax());
	}

	/** What the panel shows when the orchestrator refuses to start: attempts used up, or another run is open. */
	private LivenessViewModel startRefusal(String errorCode) {
		if (RegistrationExceptionConstants.LIVENESS_MAX_ATTEMPTS.getErrorCode().equals(errorCode)) {
			return options.workflow.isAuth()
					? LivenessViewModel.error(MessageKeys.RETRY_MAX_AUTH, texts, PromptIcon.LOCKED)
					: LivenessViewModel.error(MessageKeys.RETRY_MAX_RESIDENT, texts, PromptIcon.LOCKED,
							orchestrator.policy(options.workflow).maxRetries());
		}
		return LivenessViewModel.error(LivenessTexts.UI_BUSY, texts, PromptIcon.WARNING);
	}

	private void retry() {
		if (run == null || closed) {
			return;
		}
		try {
			run.retry();
			panel.setCancelText(texts.text(LivenessTexts.UI_CANCEL));
			panel.render(LivenessViewModel.starting(texts));
			scroller.setVvalue(scroller.getVmin());
		} catch (IllegalStateException e) {
			LOGGER.info("Face liveness retry not available");
		}
	}

	private void override() {
		if (run == null || closed || overrideRunning) {
			return;
		}
		Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, texts.text(MessageKeys.OVERRIDE_CONFIRM),
				ButtonType.OK, ButtonType.CANCEL);
		confirm.initOwner(stage);
		confirm.setHeaderText(null);
		confirm.setTitle(texts.text(MessageKeys.TITLE_SUPERVISOR));
		Optional<ButtonType> answer = confirm.showAndWait();
		if (!answer.isPresent() || answer.get() != ButtonType.OK) {
			return;
		}
		LivenessRun.OverrideRequest request;
		try {
			request = run.requestOverride();
		} catch (IllegalStateException e) {
			LOGGER.info("Supervisor override not available");
			showOverrideRefusal(LivenessTexts.OVERRIDE_NOT_ALLOWED);
			return;
		}
		SupervisorOverrideFlow flow = new SupervisorOverrideFlow(request::grant, options.operatorId, options.users,
				Instant::now);
		TextInputDialog prompt = new TextInputDialog();
		prompt.initOwner(stage);
		prompt.setTitle(texts.text(MessageKeys.TITLE_SUPERVISOR));
		prompt.setHeaderText(texts.text(LivenessTexts.OVERRIDE_PROMPT));
		prompt.setContentText(texts.text(LivenessTexts.OVERRIDE_SUPERVISOR_ID));
		Optional<String> entered = prompt.showAndWait();
		if (!entered.isPresent()) {
			return;
		}
		String supervisorId = entered.get().trim();
		Optional<String> refusal = flow.checkCandidate(supervisorId);
		if (refusal.isPresent()) {
			LOGGER.info("Supervisor override refused before authentication");
			showOverrideRefusal(refusal.get());
			return;
		}
		overrideRunning = true;
		options.supervisorAuthenticator.authenticate(supervisorId, orchestrator, options, stage, matched -> {
			overrideRunning = false;
			SupervisorOverrideFlow.Step step = flow.authenticated(supervisorId, Boolean.TRUE.equals(matched));
			if (closed) {
				return;
			}
			if (!step.isGranted()) {
				LOGGER.info("Supervisor override not granted");
				showOverrideRefusal(step.messageKey());
				return;
			}
			finish();
			callbacks.captureReady(step.context());
		});
	}

	private void showOverrideRefusal(String messageKey) {
		showFinalMessage(LivenessViewModel.error(messageKey, texts, PromptIcon.LOCKED));
	}

	private void onSessionEnded(LivenessOutcome outcome) {
		lastOutcome = outcome;
		updateDiagnostic();
		if (outcome.isPassed()) {
			passed = true;
			PauseTransition pause = new PauseTransition(SUCCESS_DISPLAY);
			pause.setOnFinished(event -> {
				if (!closed) {
					finish();
					callbacks.passed();
				}
			});
			pause.play();
			return;
		}
		if (!outcome.isRetryAllowed()) {
			panel.setCancelText(texts.text(LivenessTexts.UI_CLOSE));
		}
	}

	private void finish() {
		if (closed) {
			return;
		}
		closed = true;
		listener.detach();
		if (run != null) {
			run.close();
		}
		SessionContext.setAutoLogout(true);
		if (stage != null) {
			stage.setOnCloseRequest(null);
			stage.close();
		}
		LOGGER.info("Face liveness window closed for {} (passed={})", options.workflow.id(), passed);
	}

	/**
	 * Sizes the window to its content, capped at the visual bounds of the monitor it is on; a taller content scrolls.
	 * Called whenever the panel shows a new state, so a longer message or the buttons get room.
	 */
	private void fitWindow() {
		if (stage == null || !stage.isShowing() || stage.getScene() == null) {
			return;
		}
		Scene scene = stage.getScene();
		Insets insets = scroller.getInsets();
		double panelWidth = panel.prefWidth(-1);
		double contentWidth = panelWidth + insets.getLeft() + insets.getRight();
		double contentHeight = panel.prefHeight(panelWidth) + insets.getTop() + insets.getBottom();
		Rectangle2D bounds = visualBoundsOf(stage);
		LivenessDialogSizing.Placement placement = LivenessDialogSizing.fit(contentWidth, contentHeight,
				decoration(stage.getWidth(), scene.getWidth()), decoration(stage.getHeight(), scene.getHeight()),
				SCROLL_BAR_WIDTH, stage.getX(), stage.getY(), new LivenessDialogSizing.Area(bounds.getMinX(),
						bounds.getMinY(), bounds.getWidth(), bounds.getHeight()));
		stage.setMaxHeight(bounds.getHeight());
		stage.setWidth(placement.width);
		stage.setHeight(placement.height);
		stage.setX(placement.x);
		stage.setY(placement.y);
	}

	/** Visual bounds (without the taskbar) of the monitor that holds the window's centre; the primary one if none. */
	private static Rectangle2D visualBoundsOf(Stage window) {
		double centreX = window.getX() + window.getWidth() / 2;
		double centreY = window.getY() + window.getHeight() / 2;
		List<Screen> screens = Double.isNaN(centreX) || Double.isNaN(centreY) ? Collections.<Screen>emptyList()
				: Screen.getScreensForRectangle(centreX, centreY, 1, 1);
		return (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
	}

	/** Size of the window frame along one axis: window size minus scene size, 0 when not known yet. */
	private static double decoration(double windowSize, double sceneSize) {
		double frame = windowSize - sceneSize;
		return Double.isNaN(frame) || frame < 0 ? 0 : frame;
	}

	/** Tells whether a language is written right to left, from the client's {@code mosip.right_to_left_orientation}. */
	private static boolean isRightToLeft(String languageCode) {
		try {
			return ApplicationContext.getInstance().isLanguageRightToLeft(languageCode);
		} catch (RuntimeException e) {
			return "ara".equals(languageCode);
		}
	}

	private void checkOnline() {
		if (options.onlineCheck == null) {
			return;
		}
		Thread thread = new Thread(() -> {
			boolean online;
			try {
				online = options.onlineCheck.getAsBoolean();
			} catch (RuntimeException e) {
				online = false;
			}
			boolean offline = !online;
			Platform.runLater(() -> {
				if (!closed) {
					panel.setOffline(offline);
				}
			});
		}, "liveness-online-check");
		thread.setDaemon(true);
		thread.start();
	}

	private void updateDiagnostic() {
		LivenessPolicy policy = run != null ? run.policy() : null;
		if (policy == null || !policy.isDiagnosticEnabled()) {
			panel.setDiagnostic(null);
			return;
		}
		DiagnosticText.Input in = new DiagnosticText.Input();
		in.workflow = options.workflow.id();
		in.policy = policy;
		in.engineStatus = String.valueOf(orchestrator.engineStatus());
		in.engine = orchestrator.engineInfo();
		in.phase = lastState == null ? null : lastState.phase();
		in.outcome = lastOutcome;
		in.record = run.lastDiagnostic();
		panel.setDiagnostic(DiagnosticText.format(in));
	}

	/**
	 * Returns the text resolver of a display language: the client's own labels bundle, English as fallback.
	 *
	 * @param languageCode client language code, or {@code null}
	 * @return the resolver
	 */
	static LivenessTexts textsFor(String languageCode) {
		ResourceBundle primary = languageCode == null ? null
				: ApplicationContext.getBundle(languageCode, RegistrationConstants.LABELS);
		ResourceBundle english;
		try {
			english = ResourceBundle.getBundle(RegistrationConstants.LABELS, Locale.ENGLISH);
		} catch (RuntimeException e) {
			english = null;
		}
		return primary != null ? new LivenessTexts(primary, english) : LivenessTexts.forLanguage(languageCode);
	}

	/** Receives the run's callbacks on the UI thread. */
	private final class PanelTarget implements FxLivenessListener.Target {

		@Override
		public void uiState(LivenessUiState state) {
			if (closed) {
				return;
			}
			lastState = state;
			LivenessViewModel model = LivenessViewModel.of(state, texts, residentTexts);
			panel.render(model);
			updateDiagnostic();
			fitWindow();
			if (model.isTerminal()) {
				// on a small screen the content scrolls: show the message's end and the Retry / Close buttons
				scroller.setVvalue(scroller.getVmax());
			}
		}

		@Override
		public void previewFrame(Image image) {
			if (!closed) {
				panel.showFrame(image);
			}
		}

		@Override
		public void captureReady(LivenessContext context) {
			if (!closed) {
				callbacks.captureReady(context);
			}
		}

		@Override
		public void sessionEnded(LivenessOutcome outcome) {
			if (!closed) {
				onSessionEnded(outcome);
			}
		}
	}

	/** What to verify and how the window looks. */
	public static final class Options {

		private final Workflow workflow;
		private final String subjectRef;
		private Window owner;
		private String languageCode;
		private String titleKey = LivenessTexts.UI_TITLE;
		private boolean rightToLeft;
		private BooleanSupplier onlineCheck;
		private Supplier<String> operatorId = Options::sessionUserId;
		private Supplier<String> residentLanguage = Options::sessionResidentLanguage;
		private SupervisorOverrideFlow.UserDirectory users = SupervisorFaceAuth::rolesOf;
		private SupervisorAuthenticator supervisorAuthenticator = new SupervisorFaceAuth();

		/**
		 * Creates options.
		 *
		 * @param workflow   the liveness workflow of the capture
		 * @param subjectRef registration ID (resident workflows) or user ID (operator and supervisor workflows)
		 */
		public Options(Workflow workflow, String subjectRef) {
			this.workflow = Objects.requireNonNull(workflow, "workflow");
			this.subjectRef = Objects.requireNonNull(subjectRef, "subjectRef");
			this.titleKey = workflow == Workflow.EXCEPTION_AUTH || workflow == Workflow.EOD_AUTH
					? MessageKeys.TITLE_SUPERVISOR : LivenessTexts.UI_TITLE;
		}

		/**
		 * Sets the owner window; the dialog is modal to it.
		 *
		 * @param owner the owner, or {@code null} for an application-modal window
		 * @return these options
		 */
		public Options owner(Window owner) {
			this.owner = owner;
			return this;
		}

		/**
		 * Sets the display language.
		 *
		 * @param languageCode client language code ({@code eng}, {@code fra}, {@code ara})
		 * @param rightToLeft  whether the language is written right to left
		 * @return these options
		 */
		public Options language(String languageCode, boolean rightToLeft) {
			this.languageCode = languageCode;
			this.rightToLeft = rightToLeft;
			return this;
		}

		/**
		 * Sets the resident's language for the second prompt line of resident captures (default: the first language
		 * chosen for the registration in the session).
		 *
		 * @param languageCode client language code ({@code eng}, {@code fra}, {@code ara}), or {@code null} for no
		 *                     second line
		 * @return these options
		 */
		public Options residentLanguage(String languageCode) {
			this.residentLanguage = () -> languageCode;
			return this;
		}

		/**
		 * Returns the resident's language for the second prompt line.
		 *
		 * @return the client language code, or {@code null} when not known
		 */
		public String residentLanguage() {
			try {
				return residentLanguage.get();
			} catch (RuntimeException e) {
				return null;
			}
		}

		/**
		 * Sets the title key (default: "Face verification"; "Supervisor verification" for reviewer and EOD).
		 *
		 * @param titleKey label key
		 * @return these options
		 */
		public Options titleKey(String titleKey) {
			this.titleKey = Objects.requireNonNull(titleKey, "titleKey");
			return this;
		}

		/**
		 * Sets the check behind the offline badge; run once on a background thread.
		 *
		 * @param onlineCheck returns {@code true} when the server is reachable
		 * @return these options
		 */
		public Options onlineCheck(BooleanSupplier onlineCheck) {
			this.onlineCheck = onlineCheck;
			return this;
		}

		/**
		 * Sets how the supervisor who approves an override authenticates (default: the client's supervisor face
		 * authentication with face liveness under the {@code exception_auth} policy).
		 *
		 * @param authenticator the authenticator
		 * @return these options
		 */
		public Options supervisorAuthenticator(SupervisorAuthenticator authenticator) {
			this.supervisorAuthenticator = Objects.requireNonNull(authenticator, "authenticator");
			return this;
		}

		/**
		 * Sets where the logged-in operator's user ID and the roles of other users come from (default: the session
		 * and the users registered on this machine).
		 *
		 * @param operatorId the logged-in operator's user ID
		 * @param users      the roles of a user
		 * @return these options
		 */
		Options users(Supplier<String> operatorId, SupervisorOverrideFlow.UserDirectory users) {
			this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
			this.users = Objects.requireNonNull(users, "users");
			return this;
		}

		/**
		 * Returns the display language.
		 *
		 * @return the client language code, or {@code null}
		 */
		public String languageCode() {
			return languageCode;
		}

		/**
		 * Tells whether the display language is written right to left.
		 *
		 * @return {@code true} for right-to-left
		 */
		public boolean isRightToLeft() {
			return rightToLeft;
		}

		/** First language chosen for the registration in the session, or {@code null} outside a registration. */
		private static String sessionResidentLanguage() {
			if (!SessionContext.isSessionContextAvailable() || SessionContext.map() == null) {
				return null;
			}
			Object data = SessionContext.map().get(RegistrationConstants.REGISTRATION_DATA);
			if (!(data instanceof RegistrationDTO)) {
				return null;
			}
			List<String> languages = ((RegistrationDTO) data).getSelectedLanguagesByApplicant();
			return languages == null || languages.isEmpty() ? null : languages.get(0);
		}

		private static String sessionUserId() {
			try {
				return SessionContext.isSessionContextAvailable() ? SessionContext.userId() : "";
			} catch (RuntimeException e) {
				return "";
			}
		}
	}
}
