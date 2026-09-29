package io.mosip.registration.controller.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.liveness.core.ui.Severity;
import io.mosip.liveness.core.ui.UiPhase;

/** Mapping of engine UI states to what the liveness panel shows. */
public class LivenessViewModelTest {

	private final LivenessTexts english = LivenessTexts.forLanguage("eng");

	@Test
	public void passiveCheckShowsStatusWithBusyIndicatorAndNoButtons() {
		LivenessViewModel vm = LivenessViewModel.of(
				LivenessUiState.builder(UiPhase.CHECKING, MessageKeys.STATUS_CHECKING).progress(0.2).attempt(1, 3)
						.build(), english);

		assertTrue(vm.prompt().startsWith("Checking face liveness"));
		assertTrue(vm.isBusy());
		assertFalse(vm.isRetryVisible());
		assertFalse(vm.isOverrideVisible());
		assertEquals("", vm.stepText());
		assertEquals("Attempt 1 of 3", vm.attemptText());
		assertEquals(PromptIcon.FACE_OVAL, vm.icon());
		assertEquals(LivenessViewModel.STYLE_INFO, vm.styleClass());
	}

	@Test
	public void challengeShowsPromptIconAndStepCounter() {
		LivenessViewModel vm = LivenessViewModel.of(LivenessUiState
				.builder(UiPhase.CHALLENGE, MessageKeys.CHALLENGE_TURN_LEFT).promptIcon(PromptIcon.TURN_LEFT)
				.step(1, 2).attempt(2, 3).progress(0.5).build(), english);

		assertEquals("Please turn your head to the left", vm.prompt());
		assertEquals(PromptIcon.TURN_LEFT, vm.icon());
		assertEquals("Step 1 of 2", vm.stepText());
		assertEquals("Attempt 2 of 3", vm.attemptText());
		assertFalse(vm.isBusy());
		assertFalse(vm.isTerminal());
	}

	@Test
	public void singleAttemptPolicyHidesTheAttemptCounter() {
		LivenessViewModel vm = LivenessViewModel.of(
				LivenessUiState.builder(UiPhase.CHECKING, MessageKeys.STATUS_CHECKING).build(), english);

		assertEquals("", vm.attemptText());
	}

	@Test
	public void livenessFailureShowsPs04MessageGuidanceAndRetry() {
		LivenessViewModel vm = LivenessViewModel.of(LivenessUiState
				.builder(UiPhase.FAILED, MessageKeys.FAIL_LIVENESS).guidance(MessageKeys.GUIDE_LOOK_CAMERA)
				.guidance(MessageKeys.GUIDE_LIGHTING).severity(Severity.WARNING).attempt(1, 3).retryAvailable(true)
				.build(), english);

		assertEquals("We could not verify face liveness. Please try again.", vm.prompt());
		assertEquals(Arrays.asList("Look directly at the camera.", "Improve lighting."), vm.guidance());
		assertTrue(vm.isRetryVisible());
		assertTrue(vm.isTerminal());
		assertEquals(PromptIcon.WARNING, vm.icon());
		assertEquals(LivenessViewModel.STYLE_WARNING, vm.styleClass());
	}

	@Test
	public void retryGuidanceIsFilledAndReplacesTheAttemptCounter() {
		LivenessViewModel vm = LivenessViewModel.of(LivenessUiState.builder(UiPhase.FAILED, MessageKeys.FAIL_PAD)
				.guidance(MessageKeys.RETRY_AVAILABLE).severity(Severity.ERROR).attempt(1, 3).retryAvailable(true)
				.build(), english);

		assertEquals(Arrays.asList("Attempt 1 of 3. Select Retry to try again."), vm.guidance());
		assertEquals("", vm.attemptText());
	}

	@Test
	public void padFailureShowsOnlyTheGenericMessage() {
		LivenessViewModel vm = LivenessViewModel.of(LivenessUiState.builder(UiPhase.FAILED, MessageKeys.FAIL_PAD)
				.severity(Severity.ERROR).retryAvailable(true).attempt(1, 3).build(), english);

		assertEquals("Face verification could not be completed. Please try again.", vm.prompt());
		assertTrue(vm.guidance().isEmpty());
		assertEquals(PromptIcon.ERROR, vm.icon());
	}

	@Test
	public void maxAttemptsFillsTheCountAndOffersNoRetry() {
		LivenessViewModel vm = LivenessViewModel.of(LivenessUiState
				.builder(UiPhase.MAX_ATTEMPTS, MessageKeys.RETRY_MAX_RESIDENT).args("3").severity(Severity.ERROR)
				.attempt(3, 3).overrideAvailable(true).build(), english);

		assertTrue(vm.prompt().startsWith("Face capture could not be completed after 3 attempts."));
		assertTrue(vm.prompt().contains("centre's recovery process"));
		assertFalse(vm.isRetryVisible());
		assertTrue(vm.isOverrideVisible());
	}

	@Test
	public void retryAndOverrideAreNeverShownOutsideTerminalStates() {
		LivenessViewModel vm = LivenessViewModel.of(LivenessUiState.builder(UiPhase.HOLD_STILL,
				MessageKeys.FEEDBACK_HOLD_STILL).retryAvailable(true).overrideAvailable(true).build(), english);

		assertFalse(vm.isRetryVisible());
		assertFalse(vm.isOverrideVisible());
		assertEquals("Hold still", vm.prompt());
	}

	@Test
	public void deviceErrorUsesTheDeviceMessage() {
		LivenessViewModel vm = LivenessViewModel.of(LivenessUiState
				.builder(UiPhase.DEVICE_ERROR, MessageKeys.ERROR_DEVICE_UNAVAILABLE).promptIcon(PromptIcon.CAMERA_OFF)
				.severity(Severity.ERROR).retryAvailable(true).build(), english);

		assertEquals("The face camera is not available. Check that it is connected and select Retry.", vm.prompt());
		assertEquals(PromptIcon.CAMERA_OFF, vm.icon());
		assertTrue(vm.isRetryVisible());
		assertEquals(LivenessViewModel.STYLE_ERROR, vm.styleClass());
	}

	@Test
	public void successIsGreenWithACheck() {
		LivenessViewModel vm = LivenessViewModel.of(LivenessUiState
				.builder(UiPhase.SUCCESS, MessageKeys.FEEDBACK_SUCCESS).severity(Severity.SUCCESS).progress(1).build(),
				english);

		assertEquals("Good, face captured successfully", vm.prompt());
		assertEquals(PromptIcon.CHECK, vm.icon());
		assertEquals(LivenessViewModel.STYLE_SUCCESS, vm.styleClass());
	}

	@Test
	public void frenchTextsKeepApostrophesAndFillPlaceholders() {
		LivenessTexts french = LivenessTexts.forLanguage("fra");
		LivenessViewModel vm = LivenessViewModel.of(LivenessUiState
				.builder(UiPhase.CHALLENGE, MessageKeys.CHALLENGE_SMILE).step(2, 2).attempt(1, 3).build(), french);

		assertEquals("Veuillez sourire", vm.prompt());
		assertTrue(vm.stepText().contains("2") && vm.stepText().endsWith("sur 2"));
		assertTrue(french.text(MessageKeys.GUIDE_LIGHTING).startsWith("Am"));
		assertTrue(french.text(MessageKeys.GUIDE_LIGHTING).contains("l'"));
	}

	@Test
	public void arabicTextsResolve() {
		LivenessTexts arabic = LivenessTexts.forLanguage("ara");

		assertTrue(arabic.hasOwnText(MessageKeys.FAIL_LIVENESS));
		assertFalse(arabic.text(MessageKeys.FAIL_LIVENESS).equals(english.text(MessageKeys.FAIL_LIVENESS)));
	}

	@Test
	public void startingAndErrorModels() {
		LivenessViewModel starting = LivenessViewModel.starting(english);
		LivenessViewModel busy = LivenessViewModel.error(LivenessTexts.UI_BUSY, english, PromptIcon.WARNING);

		assertTrue(starting.isBusy());
		assertFalse(starting.isTerminal());
		assertTrue(busy.isTerminal());
		assertFalse(busy.isRetryVisible());
		assertEquals("Another face verification is in progress. Close it and try again.", busy.prompt());
	}

	@Test
	public void attemptsUsedUpModelNamesTheAttemptCount() {
		LivenessViewModel blocked = LivenessViewModel.error(MessageKeys.RETRY_MAX_RESIDENT, english, PromptIcon.LOCKED, 3);

		assertTrue(blocked.isTerminal());
		assertFalse(blocked.isRetryVisible());
		assertTrue(blocked.prompt(), blocked.prompt().startsWith("Face capture could not be completed after 3 attempts."));
	}
}
