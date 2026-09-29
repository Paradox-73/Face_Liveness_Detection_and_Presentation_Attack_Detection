package io.mosip.registration.controller.liveness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.liveness.core.ui.Severity;
import io.mosip.liveness.core.ui.UiPhase;

/**
 * What the {@link LivenessPanel} shows for one {@link LivenessUiState}: texts already resolved, which controls are
 * visible, and the style to apply. Built without JavaFX, so the mapping is unit-tested on its own.
 *
 * <p>Rules (plan section 11.4):
 * <ul>
 * <li>No scores and no buttons during the passive phase; the Retry button appears only when the engine allows
 * another attempt.</li>
 * <li>The step counter is shown only during the challenge phase, the attempt counter only when more than one
 * attempt is allowed.</li>
 * <li>Every state carries text, an icon and a colour; the colour is never the only signal.</li>
 * </ul>
 */
public final class LivenessViewModel {

	/** CSS style class for each severity, defined in {@code liveness-panel.css}. */
	static final String STYLE_INFO = "liveness-info";
	static final String STYLE_SUCCESS = "liveness-success";
	static final String STYLE_WARNING = "liveness-warning";
	static final String STYLE_ERROR = "liveness-error";

	private final UiPhase phase;
	private final String prompt;
	private final List<String> guidance;
	private final PromptIcon icon;
	private final String styleClass;
	private final double progress;
	private final String stepText;
	private final String attemptText;
	private final boolean retryVisible;
	private final boolean overrideVisible;
	private final boolean busy;
	private final boolean terminal;

	private LivenessViewModel(UiPhase phase, String prompt, List<String> guidance, PromptIcon icon, String styleClass,
			double progress, String stepText, String attemptText, boolean retryVisible, boolean overrideVisible,
			boolean busy, boolean terminal) {
		this.phase = phase;
		this.prompt = prompt;
		this.guidance = Collections.unmodifiableList(guidance);
		this.icon = icon;
		this.styleClass = styleClass;
		this.progress = progress;
		this.stepText = stepText;
		this.attemptText = attemptText;
		this.retryVisible = retryVisible;
		this.overrideVisible = overrideVisible;
		this.busy = busy;
		this.terminal = terminal;
	}

	/**
	 * Maps an engine UI state to what the panel shows.
	 *
	 * @param state the state from the liveness run
	 * @param texts text resolver of the display language
	 * @return the view model
	 */
	public static LivenessViewModel of(LivenessUiState state, LivenessTexts texts) {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(texts, "texts");
		UiPhase phase = state.phase();
		String prompt = texts.text(state.messageKey(), state.args());
		String step = phase == UiPhase.CHALLENGE || phase == UiPhase.ACTION_DETECTED
				? stepText(state, texts) : "";
		// "Attempt {0} of {1}. Select Retry..." is a guidance line; it replaces the plain attempt counter
		boolean retryLine = state.guidanceKeys().contains(MessageKeys.RETRY_AVAILABLE);
		String attempt = state.maxAttempts() > 1 && !retryLine
				? texts.text(LivenessTexts.UI_ATTEMPTS, state.attempt(), state.maxAttempts()) : "";
		boolean terminal = phase.isTerminal();
		return new LivenessViewModel(phase, prompt, guidanceOf(state, texts), iconOf(state),
				styleOf(state.severity()), state.progress(), step, attempt, terminal && state.isRetryAvailable(),
				terminal && state.isOverrideAvailable(), isBusy(phase), terminal);
	}

	/**
	 * Builds the first state shown before the engine reports anything ("Checking face liveness...").
	 *
	 * @param texts text resolver
	 * @return the starting view model
	 */
	public static LivenessViewModel starting(LivenessTexts texts) {
		return new LivenessViewModel(UiPhase.STARTING, texts.text(MessageKeys.STATUS_CHECKING),
				Collections.<String>emptyList(), PromptIcon.FACE_OVAL, STYLE_INFO, 0, "", "", false, false, true,
				false);
	}

	/**
	 * Builds an error state for a problem found by the screen itself (for example another verification running).
	 *
	 * @param messageKey message key
	 * @param texts      text resolver
	 * @param icon       icon to show
	 * @param args       values for the message's {@code {0}}, {@code {1}}... placeholders
	 * @return a terminal error view model without a Retry button
	 */
	public static LivenessViewModel error(String messageKey, LivenessTexts texts, PromptIcon icon, Object... args) {
		return new LivenessViewModel(UiPhase.DEVICE_ERROR, texts.text(messageKey, args), Collections.<String>emptyList(),
				icon, STYLE_ERROR, 0, "", "", false, false, false, true);
	}

	private static List<String> guidanceOf(LivenessUiState state, LivenessTexts texts) {
		List<String> lines = new ArrayList<>();
		for (String key : state.guidanceKeys()) {
			lines.add(texts.text(key, state.attempt(), state.maxAttempts()));
		}
		return lines;
	}

	private static String stepText(LivenessUiState state, LivenessTexts texts) {
		return state.stepCount() > 0 && state.stepIndex() > 0
				? texts.text(LivenessTexts.UI_STEP, state.stepIndex(), state.stepCount()) : "";
	}

	private static PromptIcon iconOf(LivenessUiState state) {
		if (state.promptIcon() != PromptIcon.NONE) {
			return state.promptIcon();
		}
		switch (state.severity()) {
		case SUCCESS:
			return PromptIcon.CHECK;
		case WARNING:
			return PromptIcon.WARNING;
		case ERROR:
			return PromptIcon.ERROR;
		default:
			return PromptIcon.FACE_OVAL;
		}
	}

	static String styleOf(Severity severity) {
		switch (severity) {
		case SUCCESS:
			return STYLE_SUCCESS;
		case WARNING:
			return STYLE_WARNING;
		case ERROR:
			return STYLE_ERROR;
		default:
			return STYLE_INFO;
		}
	}

	private static boolean isBusy(UiPhase phase) {
		return phase == UiPhase.STARTING || phase == UiPhase.CHECKING || phase == UiPhase.VERIFYING;
	}

	/**
	 * Returns the phase.
	 *
	 * @return the engine phase
	 */
	public UiPhase phase() {
		return phase;
	}

	/**
	 * Returns the main prompt or status text (large text in the banner).
	 *
	 * @return the text
	 */
	public String prompt() {
		return prompt;
	}

	/**
	 * Returns the guidance lines shown under a failure message.
	 *
	 * @return unmodifiable list, possibly empty
	 */
	public List<String> guidance() {
		return guidance;
	}

	/**
	 * Returns the icon shown next to the prompt.
	 *
	 * @return the icon, never {@link PromptIcon#NONE}
	 */
	public PromptIcon icon() {
		return icon;
	}

	/**
	 * Returns the CSS style class for the severity.
	 *
	 * @return one of the {@code liveness-*} classes
	 */
	public String styleClass() {
		return styleClass;
	}

	/**
	 * Returns the overall progress of the attempt.
	 *
	 * @return 0 to 1
	 */
	public double progress() {
		return progress;
	}

	/**
	 * Returns the challenge step text ("Step 1 of 2").
	 *
	 * @return the text, empty outside the challenge phase
	 */
	public String stepText() {
		return stepText;
	}

	/**
	 * Returns the attempt counter text ("Attempt 2 of 3").
	 *
	 * @return the text, empty when only one attempt is allowed
	 */
	public String attemptText() {
		return attemptText;
	}

	/**
	 * Tells whether the Retry button is shown.
	 *
	 * @return {@code true} in a terminal state that allows another attempt
	 */
	public boolean isRetryVisible() {
		return retryVisible;
	}

	/**
	 * Tells whether the supervisor override button is shown.
	 *
	 * @return {@code true} only for resident workflows after the last attempt, never after a suspected attack
	 */
	public boolean isOverrideVisible() {
		return overrideVisible;
	}

	/**
	 * Tells whether a busy indicator is shown (the engine is analysing, the person only needs to look at the camera).
	 *
	 * @return {@code true} in the starting, checking and verifying phases
	 */
	public boolean isBusy() {
		return busy;
	}

	/**
	 * Tells whether the state ends the session (success, failure, device error, cancelled).
	 *
	 * @return {@code true} for a terminal phase
	 */
	public boolean isTerminal() {
		return terminal;
	}
}
