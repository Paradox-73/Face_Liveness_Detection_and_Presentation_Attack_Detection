/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns session progress and results into the {@link LivenessUiState}s both clients render, using the message
 * keys of docs/ux/message-catalogue.csv. The texts themselves live in the clients' bundles
 * ({@code docs/ux/message-catalogue.csv}).
 *
 * <p>Rules for failures (section 11.2):
 * <ul>
 *   <li>PAD, integrity (including planarity) and binding failures all show {@link MessageKeys#FAIL_PAD}, never the
 *       specific reason;</li>
 *   <li>below-threshold, challenge and timeout failures show {@link MessageKeys#FAIL_LIVENESS} plus guidance
 *       chosen from the quality problems seen during the attempt;</li>
 *   <li>quality failures show the no-face, multiple-faces or poor-quality message; device and engine failures
 *       their own messages;</li>
 *   <li>a passive PAD failure in low light that the engine ended uncounted ({@link ReasonCode#LOW_LIGHT}) shows
 *       {@link MessageKeys#FAIL_LIVENESS} with {@link MessageKeys#GUIDE_LIGHTING} first, not the attack message;</li>
 *   <li>a counted failure on the last attempt shows the maximum-attempts message of the workflow type, with the
 *       supervisor override offered only when the workflow is configured for it and the failure was not a
 *       suspected attack.</li>
 * </ul>
 * <b>Argument convention:</b> {@link MessageKeys#RETRY_AVAILABLE} appears as a guidance key when a Retry button
 * is shown; clients format it with the state's {@code attempt} and {@code maxAttempts} as {@code {0}} and
 * {@code {1}}. Every other guidance key has no arguments.
 */
public final class LivenessUiStateMapper {

    private final LivenessPolicy policy;
    private final int attempt;

    /**
     * Creates a mapper for one attempt.
     *
     * @param policy  resolved policy of the workflow
     * @param attempt 1-based attempt number, at most {@code max_retries}
     */
    public LivenessUiStateMapper(LivenessPolicy policy, int attempt) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (attempt < 1 || attempt > policy.maxRetries()) {
            throw new IllegalArgumentException("attempt must be between 1 and max_retries");
        }
        this.attempt = attempt;
    }

    private LivenessUiState.Builder state(UiPhase phase, String key) {
        return LivenessUiState.builder(phase, key).attempt(attempt, policy.maxRetries());
    }

    /**
     * Returns the state while the stream opens.
     *
     * @return the starting state
     */
    public LivenessUiState starting() {
        return state(UiPhase.STARTING, MessageKeys.GUIDE_LOOK_CAMERA).promptIcon(PromptIcon.FACE_OVAL).build();
    }

    /**
     * Returns the positioning state of the acquisition phase.
     *
     * @param problem the quality problem of the last frame, or {@code null} if the frame was good
     * @return the positioning state
     */
    public LivenessUiState positioning(ReasonCode problem) {
        if (problem == null) {
            return state(UiPhase.POSITIONING, MessageKeys.GUIDE_LOOK_CAMERA).promptIcon(PromptIcon.FACE_OVAL)
                    .progress(0.05).build();
        }
        return state(UiPhase.POSITIONING, messageKeyFor(problem)).promptIcon(PromptIcon.WARNING)
                .severity(Severity.WARNING).progress(0.05).build();
    }

    /**
     * Returns the "Checking face liveness..." state of the passive phase.
     *
     * @param fraction share of the passive frames collected, 0-1
     * @param problem  the quality problem of the last frame, or {@code null}
     * @return the checking state
     */
    public LivenessUiState checking(double fraction, ReasonCode problem) {
        LivenessUiState.Builder b = state(UiPhase.CHECKING, MessageKeys.STATUS_CHECKING).promptIcon(PromptIcon.PROGRESS)
                .progress(0.1 + 0.4 * clamp(fraction));
        if (problem != null) {
            b.guidance(guidanceKeyFor(problem));
        }
        return b.build();
    }

    /**
     * Returns the state between challenges, while waiting for a neutral face.
     *
     * @param index   0-based index of the next challenge
     * @param count   number of challenges
     * @param neutral whether the face is currently neutral
     * @return the waiting state
     */
    public LivenessUiState challengeWaiting(int index, int count, boolean neutral) {
        String key = neutral ? MessageKeys.FEEDBACK_CONTINUE : MessageKeys.GUIDE_LOOK_CAMERA;
        return state(UiPhase.CHALLENGE, key).promptIcon(PromptIcon.FACE_OVAL).step(index + 1, count)
                .progress(activeProgress(index, count)).build();
    }

    /**
     * Returns the prompt of a challenge.
     *
     * @param type   the challenge
     * @param index  0-based index
     * @param count  number of challenges
     * @param remind whether to add "Follow the prompted action." (after half the time has passed)
     * @return the prompt state
     */
    public LivenessUiState challenge(ChallengeType type, int index, int count, boolean remind) {
        LivenessUiState.Builder b = state(UiPhase.CHALLENGE, MessageKeys.challengeKey(type))
                .promptIcon(PromptIcon.forChallenge(type)).step(index + 1, count).progress(activeProgress(index, count));
        if (remind) {
            b.guidance(MessageKeys.GUIDE_FOLLOW_ACTION).severity(Severity.WARNING);
        }
        return b.build();
    }

    /**
     * Returns "Action detected" after a challenge.
     *
     * @param index 0-based index of the challenge just passed
     * @param count number of challenges
     * @return the state
     */
    public LivenessUiState actionDetected(int index, int count) {
        return state(UiPhase.ACTION_DETECTED, MessageKeys.FEEDBACK_DETECTED).promptIcon(PromptIcon.CHECK)
                .severity(Severity.SUCCESS).step(index + 1, count).progress(activeProgress(index + 1, count)).build();
    }

    /**
     * Returns "Hold still" while the signed capture is taken.
     *
     * @return the state
     */
    public LivenessUiState holdStill() {
        return state(UiPhase.HOLD_STILL, MessageKeys.FEEDBACK_HOLD_STILL).promptIcon(PromptIcon.HOLD_STILL)
                .progress(0.9).build();
    }

    /**
     * Returns "Look directly at the camera and hold still." shown after the last challenge until a neutral, frontal
     * face gives the frame that is stored.
     *
     * @param problem the quality problem of the last frame, or {@code null}
     * @return the state
     */
    public LivenessUiState lookStraight(ReasonCode problem) {
        LivenessUiState.Builder b = state(UiPhase.HOLD_STILL, MessageKeys.FEEDBACK_LOOK_STRAIGHT)
                .promptIcon(PromptIcon.FACE_OVAL).progress(0.9);
        if (problem != null) {
            b.guidance(guidanceKeyFor(problem));
        }
        return b.build();
    }

    /**
     * Returns the state while the capture is checked against the session.
     *
     * @return the state
     */
    public LivenessUiState verifying() {
        return state(UiPhase.VERIFYING, MessageKeys.STATUS_CHECKING).promptIcon(PromptIcon.PROGRESS).progress(0.95)
                .build();
    }

    /**
     * Returns the final state of a session.
     *
     * @param result          the session result
     * @param qualityProblems quality problems seen during the attempt, most relevant first (may be empty)
     * @return the final state
     */
    public LivenessUiState forResult(LivenessResult result, Collection<ReasonCode> qualityProblems) {
        Optional<FailureReason> reason = result.failureReason();
        if (!reason.isPresent()) {
            return state(UiPhase.SUCCESS, MessageKeys.FEEDBACK_SUCCESS).promptIcon(PromptIcon.CHECK)
                    .severity(Severity.SUCCESS).progress(1).build();
        }
        FailureReason r = reason.get();
        if (r == FailureReason.CANCELLED) {
            return state(UiPhase.CANCELLED, MessageKeys.RETRY_AVAILABLE)
                    .args(Integer.toString(attempt), Integer.toString(policy.maxRetries())).retryAvailable(true).build();
        }
        if (r.countsAsAttempt() && attempt >= policy.maxRetries()) {
            return maxAttempts(r);
        }
        return failed(r, result, qualityProblems);
    }

    private LivenessUiState maxAttempts(FailureReason r) {
        boolean resident = policy.workflow().isResident();
        LivenessUiState.Builder b = state(UiPhase.MAX_ATTEMPTS,
                resident ? MessageKeys.RETRY_MAX_RESIDENT : MessageKeys.RETRY_MAX_AUTH)
                .promptIcon(PromptIcon.LOCKED).severity(Severity.ERROR);
        if (resident) {
            b.args(Integer.toString(policy.maxRetries()));
        }
        boolean override = resident && policy.onMaxFailure() == OnMaxFailure.SUPERVISOR_OVERRIDE && r.allowsOverride();
        return b.overrideAvailable(override).build();
    }

    private LivenessUiState failed(FailureReason r, LivenessResult result, Collection<ReasonCode> qualityProblems) {
        boolean device = r == FailureReason.DEVICE;
        boolean retry = r != FailureReason.ENGINE || !result.engineError().isPresent()
                || !result.engineError().get().isModelIntegrityFailure();
        String key = failureMessageKey(r, result.reasonCodes(), result.deviceError().orElse(null));
        LivenessUiState.Builder b = state(device ? UiPhase.DEVICE_ERROR : UiPhase.FAILED, key)
                .promptIcon(device ? PromptIcon.CAMERA_OFF : PromptIcon.ERROR).severity(Severity.ERROR)
                .retryAvailable(retry);
        for (String g : failureGuidance(r, result.reasonCodes(), qualityProblems)) {
            b.guidance(g);
        }
        if (retry) {
            b.guidance(MessageKeys.RETRY_AVAILABLE);
        }
        return b.build();
    }

    /**
     * Returns the main message of a failure.
     *
     * @param reason      the failure reason
     * @param codes       the result's reason codes
     * @param deviceError the device error code, or {@code null}
     * @return the message key
     */
    public static String failureMessageKey(FailureReason reason, Set<ReasonCode> codes, DeviceErrorCode deviceError) {
        switch (reason) {
            case PAD:
            case INTEGRITY:
            case BINDING:
                return MessageKeys.FAIL_PAD;
            case QUALITY:
                if (codes.contains(ReasonCode.LOW_LIGHT)) {
                    return MessageKeys.FAIL_LIVENESS;
                }
                if (codes.contains(ReasonCode.NO_FACE)) {
                    return MessageKeys.ERROR_NO_FACE;
                }
                return codes.contains(ReasonCode.MULTI_FACE) ? MessageKeys.ERROR_MULTIPLE_FACES
                        : MessageKeys.ERROR_POOR_QUALITY;
            case DEVICE:
                return deviceMessageKey(deviceError);
            case ENGINE:
                return MessageKeys.ERROR_ENGINE;
            default:
                return MessageKeys.FAIL_LIVENESS;
        }
    }

    /**
     * Returns the message of a device error.
     *
     * @param code the device error code, or {@code null}
     * @return the message key
     */
    public static String deviceMessageKey(DeviceErrorCode code) {
        if (code == null) {
            return MessageKeys.ERROR_DEVICE_UNAVAILABLE;
        }
        switch (code) {
            case DISCONNECTED:
                return MessageKeys.ERROR_DEVICE_DISCONNECTED;
            case NO_STREAM:
                return MessageKeys.ERROR_DEVICE_NO_STREAM;
            case STALLED:
            case INVALID_STREAM:
                return MessageKeys.ERROR_FRAMES_INVALID;
            case CAMERA_PERMISSION_DENIED:
                return MessageKeys.ERROR_CAMERA_PERMISSION;
            case CAMERA_IN_USE:
                return MessageKeys.ERROR_CAMERA_UNAVAILABLE;
            case EMULATOR_BLOCKED:
                return MessageKeys.ERROR_EMULATOR;
            default:
                return MessageKeys.ERROR_DEVICE_UNAVAILABLE;
        }
    }

    private static List<String> failureGuidance(FailureReason r, Set<ReasonCode> codes,
            Collection<ReasonCode> qualityProblems) {
        Set<String> keys = new LinkedHashSet<>();
        if (r == FailureReason.TIMEOUT) {
            keys.add(MessageKeys.ERROR_TIMEOUT);
            keys.add(codes.contains(ReasonCode.NEUTRAL_TIMEOUT) ? MessageKeys.GUIDE_LOOK_CAMERA
                    : MessageKeys.GUIDE_FOLLOW_ACTION);
        } else if (r == FailureReason.ACTIVE) {
            keys.add(MessageKeys.GUIDE_FOLLOW_ACTION);
        } else if (r == FailureReason.BELOW_THRESHOLD || r == FailureReason.QUALITY) {
            if (codes.contains(ReasonCode.LOW_LIGHT)) {
                keys.add(MessageKeys.GUIDE_LIGHTING);
            }
            for (ReasonCode q : qualityProblems) {
                keys.add(guidanceKeyFor(q));
            }
            if (keys.isEmpty() && r == FailureReason.BELOW_THRESHOLD) {
                keys.add(MessageKeys.GUIDE_LOOK_CAMERA);
                keys.add(MessageKeys.GUIDE_LIGHTING);
            }
        }
        return new ArrayList<>(keys);
    }

    /**
     * Returns the main message shown while positioning for a quality problem.
     *
     * @param problem a quality reason code
     * @return the message key
     */
    public static String messageKeyFor(ReasonCode problem) {
        switch (problem) {
            case NO_FACE:
                return MessageKeys.ERROR_NO_FACE;
            case MULTI_FACE:
                return MessageKeys.ERROR_MULTIPLE_FACES;
            case POOR_QUALITY:
                return MessageKeys.ERROR_POOR_QUALITY;
            default:
                return guidanceKeyFor(problem);
        }
    }

    /**
     * Returns the short guidance for a quality problem (PS04 UI tips).
     *
     * @param problem a quality reason code
     * @return the guidance key
     */
    public static String guidanceKeyFor(ReasonCode problem) {
        switch (problem) {
            case NO_FACE:
            case FACE_OUT_OF_AREA:
                return MessageKeys.GUIDE_IN_AREA;
            case OFF_POSE:
                return MessageKeys.GUIDE_LOOK_CAMERA;
            case TOO_DARK:
            case TOO_BRIGHT:
            case LOW_LIGHT:
                return MessageKeys.GUIDE_LIGHTING;
            case MULTI_FACE:
            case FACE_TOO_SMALL:
            case BLURRY:
                return MessageKeys.GUIDE_FACE_VISIBLE;
            default:
                return MessageKeys.GUIDE_LOOK_CAMERA;
        }
    }

    /**
     * Returns the text to show for a message: the synced replacement
     * {@code mosip.registration.liveness.guidance.<messageKey>.<lang>} when set and not blank, otherwise the
     * bundle text (docs/design/configuration.md section 9, test T-CORE-UI-09).
     *
     * @param config     configuration source
     * @param messageKey message key
     * @param language   language code used by the client, for example {@code "eng"}
     * @param bundleText text from the client's bundle
     * @return the text to show
     */
    public static String resolveText(ConfigSource config, String messageKey, String language, String bundleText) {
        Optional<String> override = config.get(LivenessConfigKeys.guidanceKey(messageKey, language));
        return override.isPresent() && !override.get().trim().isEmpty() ? override.get() : bundleText;
    }

    private static double activeProgress(int index, int count) {
        return count <= 0 ? 0.5 : 0.5 + 0.4 * clamp((double) index / count);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
