package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.liveness.core.ui.Severity;
import io.mosip.liveness.core.ui.UiPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-CORE-UI-01..10: session states and results to message keys. */
class UiStateMapperTest {

    private static final LivenessPolicy REG = LivenessPolicy.defaults(Workflow.REGISTRATION);
    private static final java.util.List<ReasonCode> NONE = Collections.<ReasonCode>emptyList();

    private static LivenessResult failed(Workflow w, int attempt, FailureReason reason, ReasonCode... codes) {
        LivenessResult.Builder b = LivenessResult.builder("s1", w, SessionHarness.engineInfo(),
                SessionHarness.device(false, true)).attempt(attempt).method(LivenessMethod.PASSIVE)
                .period(FakeClock.EPOCH, FakeClock.EPOCH).fail(reason);
        for (ReasonCode c : codes) {
            b.reason(c);
        }
        return b.build();
    }

    @Test
    void progressStates() {
        LivenessUiStateMapper m = new LivenessUiStateMapper(REG, 1);
        assertEquals(UiPhase.STARTING, m.starting().phase());
        assertEquals(MessageKeys.GUIDE_LOOK_CAMERA, m.positioning(null).messageKey());
        LivenessUiState noFace = m.positioning(ReasonCode.NO_FACE);
        assertEquals(MessageKeys.ERROR_NO_FACE, noFace.messageKey());
        assertEquals(Severity.WARNING, noFace.severity());
        LivenessUiState checking = m.checking(0.5, ReasonCode.TOO_DARK);
        assertEquals(MessageKeys.STATUS_CHECKING, checking.messageKey());
        assertEquals(Collections.singletonList(MessageKeys.GUIDE_LIGHTING), checking.guidanceKeys());
        assertEquals(0.3, checking.progress(), 1e-9);
        LivenessUiState prompt = m.challenge(ChallengeType.TURN_LEFT, 0, 2, false);
        assertEquals(MessageKeys.CHALLENGE_TURN_LEFT, prompt.messageKey());
        assertEquals(PromptIcon.TURN_LEFT, prompt.promptIcon());
        assertEquals(1, prompt.stepIndex());
        assertEquals(2, prompt.stepCount());
        assertEquals(Collections.singletonList(MessageKeys.GUIDE_FOLLOW_ACTION),
                m.challenge(ChallengeType.SMILE, 1, 2, true).guidanceKeys());
        assertEquals(MessageKeys.FEEDBACK_CONTINUE, m.challengeWaiting(0, 2, true).messageKey());
        assertEquals(MessageKeys.GUIDE_LOOK_CAMERA, m.challengeWaiting(0, 2, false).messageKey());
        assertEquals(MessageKeys.FEEDBACK_DETECTED, m.actionDetected(0, 2).messageKey());
        assertEquals(MessageKeys.FEEDBACK_HOLD_STILL, m.holdStill().messageKey());
        assertEquals(UiPhase.VERIFYING, m.verifying().phase());
    }

    @ParameterizedTest
    @EnumSource(value = FailureReason.class, names = {"PAD", "INTEGRITY", "BINDING"})
    void suspectedAttacksShowTheGenericMessage(FailureReason reason) {
        LivenessUiState s = new LivenessUiStateMapper(REG, 1).forResult(failed(Workflow.REGISTRATION, 1, reason,
                ReasonCode.FROZEN), NONE);
        assertEquals(MessageKeys.FAIL_PAD, s.messageKey());
        assertTrue(s.isRetryAvailable());
        assertEquals(Arrays.asList(MessageKeys.RETRY_AVAILABLE), s.guidanceKeys());
        assertFalse(s.toString().contains("FROZEN"), "the reason never reaches the screen");
    }

    @Test
    void livenessFailuresShowGuidance() {
        LivenessUiStateMapper m = new LivenessUiStateMapper(REG, 1);
        LivenessUiState below = m.forResult(failed(Workflow.REGISTRATION, 1, FailureReason.BELOW_THRESHOLD),
                Arrays.asList(ReasonCode.TOO_DARK, ReasonCode.OFF_POSE, ReasonCode.TOO_BRIGHT));
        assertEquals(MessageKeys.FAIL_LIVENESS, below.messageKey());
        assertEquals(Arrays.asList(MessageKeys.GUIDE_LIGHTING, MessageKeys.GUIDE_LOOK_CAMERA, MessageKeys.RETRY_AVAILABLE),
                below.guidanceKeys());
        LivenessUiState neutral = m.forResult(failed(Workflow.REGISTRATION, 1, FailureReason.TIMEOUT,
                ReasonCode.NEUTRAL_TIMEOUT), NONE);
        assertTrue(neutral.guidanceKeys().contains(MessageKeys.GUIDE_LOOK_CAMERA));
        LivenessUiState quality = m.forResult(failed(Workflow.REGISTRATION, 1, FailureReason.QUALITY,
                ReasonCode.PASSIVE_TIMEOUT, ReasonCode.MULTI_FACE), Collections.singletonList(ReasonCode.MULTI_FACE));
        assertEquals(MessageKeys.ERROR_MULTIPLE_FACES, quality.messageKey());
        assertTrue(quality.guidanceKeys().contains(MessageKeys.GUIDE_FACE_VISIBLE));
        assertEquals(MessageKeys.ERROR_NO_FACE, m.forResult(failed(Workflow.REGISTRATION, 1, FailureReason.QUALITY,
                ReasonCode.NO_FACE), Collections.<ReasonCode>emptyList()).messageKey());
        assertEquals(MessageKeys.ERROR_POOR_QUALITY, m.forResult(failed(Workflow.REGISTRATION, 1, FailureReason.QUALITY,
                ReasonCode.BLURRY), Collections.<ReasonCode>emptyList()).messageKey());
    }

    @Test
    void maxAttemptsResidentWithOverride() {
        LivenessPolicy p = REG.toBuilder().onMaxFailure(OnMaxFailure.SUPERVISOR_OVERRIDE).build();
        LivenessUiStateMapper m = new LivenessUiStateMapper(p, 3);
        LivenessUiState s = m.forResult(failed(Workflow.REGISTRATION, 3, FailureReason.ACTIVE), NONE);
        assertEquals(UiPhase.MAX_ATTEMPTS, s.phase());
        assertEquals(MessageKeys.RETRY_MAX_RESIDENT, s.messageKey());
        assertEquals(Collections.singletonList("3"), s.args());
        assertTrue(s.isOverrideAvailable());
        assertFalse(s.isRetryAvailable());
        LivenessUiState attack = m.forResult(failed(Workflow.REGISTRATION, 3, FailureReason.PAD), NONE);
        assertFalse(attack.isOverrideAvailable(), "never after a suspected attack");
        LivenessUiState device = m.forResult(failed(Workflow.REGISTRATION, 3, FailureReason.DEVICE), NONE);
        assertEquals(UiPhase.DEVICE_ERROR, device.phase(), "uncounted failures keep the attempt");
    }

    @Test
    void maxAttemptsAuth() {
        LivenessUiStateMapper m = new LivenessUiStateMapper(LivenessPolicy.defaults(Workflow.LOGIN_AUTH), 3);
        LivenessUiState s = m.forResult(failed(Workflow.LOGIN_AUTH, 3, FailureReason.TIMEOUT), NONE);
        assertEquals(MessageKeys.RETRY_MAX_AUTH, s.messageKey());
        assertTrue(s.args().isEmpty());
        assertFalse(s.isOverrideAvailable());
        assertEquals(PromptIcon.LOCKED, s.promptIcon());
    }

    @Test
    void deviceAndEngineMessages() {
        Map<DeviceErrorCode, String> expected = new HashMap<>();
        expected.put(DeviceErrorCode.DISCONNECTED, MessageKeys.ERROR_DEVICE_DISCONNECTED);
        expected.put(DeviceErrorCode.NO_STREAM, MessageKeys.ERROR_DEVICE_NO_STREAM);
        expected.put(DeviceErrorCode.STALLED, MessageKeys.ERROR_FRAMES_INVALID);
        expected.put(DeviceErrorCode.INVALID_STREAM, MessageKeys.ERROR_FRAMES_INVALID);
        expected.put(DeviceErrorCode.CAMERA_PERMISSION_DENIED, MessageKeys.ERROR_CAMERA_PERMISSION);
        expected.put(DeviceErrorCode.CAMERA_IN_USE, MessageKeys.ERROR_CAMERA_UNAVAILABLE);
        expected.put(DeviceErrorCode.EMULATOR_BLOCKED, MessageKeys.ERROR_EMULATOR);
        expected.put(DeviceErrorCode.NOT_READY, MessageKeys.ERROR_DEVICE_UNAVAILABLE);
        for (Map.Entry<DeviceErrorCode, String> e : expected.entrySet()) {
            assertEquals(e.getValue(), LivenessUiStateMapper.deviceMessageKey(e.getKey()));
        }
        assertEquals(MessageKeys.ERROR_DEVICE_UNAVAILABLE, LivenessUiStateMapper.deviceMessageKey(null));
        LivenessResult engine = LivenessResult.builder("s", Workflow.REGISTRATION, SessionHarness.engineInfo(),
                SessionHarness.device(false, true)).method(LivenessMethod.NONE).period(FakeClock.EPOCH, FakeClock.EPOCH)
                .fail(FailureReason.ENGINE).engineError(EngineErrorCode.MODEL_INTEGRITY).build();
        LivenessUiState s = new LivenessUiStateMapper(REG, 1).forResult(engine, NONE);
        assertEquals(MessageKeys.ERROR_ENGINE, s.messageKey());
        assertFalse(s.isRetryAvailable(), "a model integrity failure cannot be retried away");
    }

    @Test
    void successAndCancel() {
        LivenessUiStateMapper m = new LivenessUiStateMapper(REG, 2);
        LivenessResult pass = LivenessResult.builder("s", Workflow.REGISTRATION, SessionHarness.engineInfo(),
                SessionHarness.device(false, true)).method(LivenessMethod.PASSIVE).period(FakeClock.EPOCH, FakeClock.EPOCH)
                .pass().build();
        assertEquals(MessageKeys.FEEDBACK_SUCCESS, m.forResult(pass, Collections.<ReasonCode>emptyList()).messageKey());
        LivenessUiState cancelled = m.forResult(failed(Workflow.REGISTRATION, 2, FailureReason.CANCELLED),
                NONE);
        assertEquals(UiPhase.CANCELLED, cancelled.phase());
        assertEquals(Arrays.asList("2", "3"), cancelled.args());
    }

    @Test
    void guidanceKeysForEveryQualityCode() {
        for (ReasonCode c : EnumSet.of(ReasonCode.NO_FACE, ReasonCode.MULTI_FACE, ReasonCode.FACE_TOO_SMALL,
                ReasonCode.FACE_OUT_OF_AREA, ReasonCode.OFF_POSE, ReasonCode.TOO_DARK, ReasonCode.TOO_BRIGHT,
                ReasonCode.BLURRY, ReasonCode.POOR_QUALITY)) {
            assertTrue(MessageKeys.all().contains(LivenessUiStateMapper.guidanceKeyFor(c)), c.name());
            assertTrue(MessageKeys.all().contains(LivenessUiStateMapper.messageKeyFor(c)), c.name());
        }
        assertEquals(MessageKeys.ERROR_POOR_QUALITY, LivenessUiStateMapper.messageKeyFor(ReasonCode.POOR_QUALITY));
    }

    /** T-CORE-UI-09: a synced guidance key replaces the bundle text for one language. */
    @Test
    void syncedTextOverridesTheBundle() {
        Map<String, String> cfg = new HashMap<>();
        cfg.put(LivenessConfigKeys.guidanceKey(MessageKeys.GUIDE_LIGHTING, "eng"), "Move to a brighter spot.");
        cfg.put(LivenessConfigKeys.guidanceKey(MessageKeys.GUIDE_IN_AREA, "eng"), "  ");
        ConfigSource config = ConfigSource.of(cfg);
        assertEquals("Move to a brighter spot.",
                LivenessUiStateMapper.resolveText(config, MessageKeys.GUIDE_LIGHTING, "eng", "Improve lighting."));
        assertEquals("Améliorez l'éclairage.",
                LivenessUiStateMapper.resolveText(config, MessageKeys.GUIDE_LIGHTING, "fra", "Améliorez l'éclairage."));
        assertEquals("Keep your face within the capture area.", LivenessUiStateMapper.resolveText(config,
                MessageKeys.GUIDE_IN_AREA, "eng", "Keep your face within the capture area."));
    }

    @Test
    void attemptMustFitThePolicy() {
        assertThrows(IllegalArgumentException.class, () -> new LivenessUiStateMapper(REG, 0));
        assertThrows(IllegalArgumentException.class, () -> new LivenessUiStateMapper(REG, 4));
    }
}
