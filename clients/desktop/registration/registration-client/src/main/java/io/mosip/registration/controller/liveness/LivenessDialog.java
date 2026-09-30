package io.mosip.registration.controller.liveness;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.BooleanSupplier;
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
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.service.bio.liveness.LivenessOrchestrator;
import io.mosip.registration.service.bio.liveness.LivenessOutcome;
import io.mosip.registration.service.bio.liveness.LivenessRequest;
import io.mosip.registration.service.bio.liveness.LivenessRun;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.NodeOrientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.stage.Modality;
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
 * <p>All methods run on the JavaFX application thread.
 */
public final class LivenessDialog {

	private static final Logger LOGGER = AppConfig.getLogger(LivenessDialog.class);
	private static final Duration SUCCESS_DISPLAY = Duration.millis(900);

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

	private final LivenessOrchestrator orchestrator;
	private final Options options;
	private final Callbacks callbacks;
	private final LivenessTexts texts;
	private final LivenessPanel panel;
	private final FxLivenessListener listener;
	private Stage stage;
	private LivenessRun run;
	private LivenessOutcome lastOutcome;
	private LivenessUiState lastState;
	private boolean passed;
	private boolean closed;

	private LivenessDialog(LivenessOrchestrator orchestrator, Options options, Callbacks callbacks) {
		this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
		this.options = Objects.requireNonNull(options, "options");
		this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
		this.texts = textsFor(options.languageCode).withOverrides(orchestrator.config(), options.languageCode);
		this.panel = new LivenessPanel();
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
		panel.render(LivenessViewModel.starting(texts));

		stage = new Stage();
		stage.setTitle(title);
		stage.setResizable(false);
		if (options.owner != null) {
			stage.initOwner(options.owner);
			stage.initModality(Modality.WINDOW_MODAL);
		} else {
			stage.initModality(Modality.APPLICATION_MODAL);
		}
		Scene scene = new Scene(panel);
		scene.setNodeOrientation(options.rightToLeft ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
		stage.setScene(scene);
		stage.setOnCloseRequest(event -> cancel());
		SessionContext.setAutoLogout(false);
		stage.show();

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
			panel.render(startRefusal(e.getErrorCode()));
			panel.setCancelText(texts.text(LivenessTexts.UI_CLOSE));
			fitWindow();
		} catch (RuntimeException e) {
			LOGGER.error("Face liveness could not start: {}", e.getClass().getSimpleName());
			panel.render(LivenessViewModel.error(MessageKeys.ERROR_ENGINE, texts, PromptIcon.ERROR));
			panel.setCancelText(texts.text(LivenessTexts.UI_CLOSE));
		}
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
		} catch (IllegalStateException e) {
			LOGGER.info("Face liveness retry not available");
		}
	}

	private void override() {
		if (run == null || closed) {
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
		Optional<String> supervisor = options.supervisorId.get();
		if (!supervisor.isPresent()) {
			panel.render(LivenessViewModel.error(MessageKeys.ERROR_NOT_SUPERVISOR, texts, PromptIcon.LOCKED));
			return;
		}
		try {
			LivenessContext context = run.grantOverride(supervisor.get());
			finish();
			callbacks.captureReady(context);
		} catch (IllegalStateException | IllegalArgumentException e) {
			LOGGER.info("Supervisor override refused: {}", e.getClass().getSimpleName());
			panel.render(LivenessViewModel.error(MessageKeys.ERROR_NOT_SUPERVISOR, texts, PromptIcon.LOCKED));
		}
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

	/** Grows the window when a longer message or the buttons need more room, so nothing is cut off. */
	private void fitWindow() {
		if (stage != null && stage.isShowing()) {
			stage.sizeToScene();
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
			Platform.runLater(() -> panel.setOffline(offline));
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
			panel.render(LivenessViewModel.of(state, texts));
			updateDiagnostic();
			fitWindow();
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
		private Supplier<Optional<String>> supervisorId = Optional::empty;

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
		 * Sets who may grant a supervisor override (resident workflows, after the last attempt, when the policy's
		 * {@code on_max_failure} is {@code SUPERVISOR_OVERRIDE}).
		 *
		 * @param supervisorId returns the logged-in supervisor's user ID, or empty when the user is not a supervisor
		 * @return these options
		 */
		public Options supervisorId(Supplier<Optional<String>> supervisorId) {
			this.supervisorId = Objects.requireNonNull(supervisorId, "supervisorId");
			return this;
		}
	}
}
