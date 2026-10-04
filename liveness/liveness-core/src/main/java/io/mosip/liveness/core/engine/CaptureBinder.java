/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.ChallengeEchoStatus;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceAnalyzer;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.PassivePad;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Stream-to-capture binding (docs/design/engine.md section 7): checks that the signed capture belongs to the live
 * session.
 *
 * <ol>
 *   <li>a pass token is presented, unused, equal to the one this session issued and not expired;</li>
 *   <li>the capture was signed by the device that streamed;</li>
 *   <li>a {@code padCapability} device echoed the token nonce as its PAD challenge;</li>
 *   <li>the capture time lies in the window: from the pass (resident) or from the start of the capture after
 *       device Ready (authentication), minus the clock-skew tolerance, up to {@code binding.max_gap_ms} plus the
 *       capture timeout;</li>
 *   <li>a device PAD block does not report an attack;</li>
 *   <li>the captured image has exactly one face, similar enough to the session reference, and its passive PAD
 *       score is not below {@code pad.reject_threshold}.</li>
 * </ol>
 * When an identity embedder is configured, a similarity that cannot be computed (the session built no reference,
 * or the captured face has no landmarks) fails as {@link ReasonCode#SIMILARITY_LOW}: the face check fails closed
 * instead of being skipped. Without an embedder no similarity is checked.
 * All checks run so the verdict lists every problem. Image checks are skipped when no liveness check ran
 * (policy {@code OFF} or {@code CAPTURE_ONLY}). A device PAD failure or a low capture PAD score fails with
 * {@link FailureReason#PAD}; any other problem with {@link FailureReason#BINDING}.
 */
final class CaptureBinder {

    /** Verdict plus the engine error that interrupted the image checks, if any. */
    static final class Outcome {
        final BindingVerdict verdict;
        final EngineException engineError;

        Outcome(BindingVerdict verdict, EngineException engineError) {
            this.verdict = verdict;
            this.engineError = engineError;
        }
    }

    private final FaceAnalyzer analyzer;
    private final PassivePad pad;
    private final IdentityTracker identity;
    private final LivenessPolicy policy;
    private final long captureTimeoutMs;
    private final long skewMs;

    CaptureBinder(FaceAnalyzer analyzer, PassivePad pad, IdentityTracker identity, LivenessPolicy policy,
            EngineParameters params) {
        this.analyzer = analyzer;
        this.pad = pad;
        this.identity = identity;
        this.policy = policy;
        this.captureTimeoutMs = params.getLong(EngineParameter.CAPTURE_TIMEOUT_MS);
        this.skewMs = params.getLong(EngineParameter.CLOCK_SKEW_TOLERANCE_MS);
    }

    /**
     * Checks a capture.
     *
     * @param req            the capture and presented token
     * @param issued         the token this session issued
     * @param consumed       whether that token was already used
     * @param padCapable     whether the device advertised {@code padCapability}
     * @param now            current wall-clock time
     * @param windowStart    start of the time window
     * @param livenessChecks whether to run the image checks
     * @return the outcome
     */
    Outcome bind(BindingRequest req, LivenessPassToken issued, boolean consumed, boolean padCapable, Instant now,
            Instant windowStart, boolean livenessChecks) {
        Set<ReasonCode> bindingCodes = EnumSet.noneOf(ReasonCode.class);
        Set<ReasonCode> padCodes = EnumSet.noneOf(ReasonCode.class);
        checkToken(req.token(), issued, consumed, now, bindingCodes);
        if (!req.deviceSerial().equals(issued.deviceSerial())) {
            bindingCodes.add(ReasonCode.DEVICE_MISMATCH);
        }
        ChallengeEchoStatus echo = echo(req, issued, padCapable);
        if (!echo.isAcceptable()) {
            bindingCodes.add(ReasonCode.CHALLENGE_ECHO_MISMATCH);
        }
        long gapMs = Duration.between(windowStart, req.capturedAt()).toMillis();
        if (gapMs < -skewMs || gapMs > policy.bindingMaxGapMs() + captureTimeoutMs) {
            bindingCodes.add(ReasonCode.OUTSIDE_TIME_WINDOW);
        }
        if (req.devicePad().isPresent() && req.devicePad().get().isFail()) {
            padCodes.add(ReasonCode.DEVICE_PAD);
        }
        int[] scores = {-1, -1};
        if (livenessChecks) {
            try {
                checkImage(req.captureImage(), bindingCodes, padCodes, scores);
            } catch (EngineException e) {
                BindingVerdict v = BindingVerdict.failed(FailureReason.BINDING, ReasonCode.ENGINE_ERROR)
                        .challengeEcho(echo).gapMs(gapMs).build();
                return new Outcome(v, e);
            }
        }
        return new Outcome(verdict(bindingCodes, padCodes, echo, gapMs, scores), null);
    }

    private static void checkToken(Optional<LivenessPassToken> presented, LivenessPassToken issued, boolean consumed,
            Instant now, Set<ReasonCode> codes) {
        if (!presented.isPresent()) {
            codes.add(ReasonCode.TOKEN_MISSING);
        } else if (consumed) {
            codes.add(ReasonCode.TOKEN_REUSED);
        } else if (!presented.get().equals(issued)) {
            codes.add(ReasonCode.TOKEN_MISMATCH);
        } else if (issued.isExpiredAt(now)) {
            codes.add(ReasonCode.TOKEN_EXPIRED);
        }
    }

    private static ChallengeEchoStatus echo(BindingRequest req, LivenessPassToken issued, boolean padCapable) {
        if (!padCapable) {
            return ChallengeEchoStatus.NOT_REQUESTED;
        }
        if (!req.echoedChallenge().isPresent()) {
            return ChallengeEchoStatus.MISSING;
        }
        return req.echoedChallenge().get().equals(issued.nonce()) ? ChallengeEchoStatus.MATCHED
                : ChallengeEchoStatus.MISMATCHED;
    }

    private void checkImage(ImageBuffer image, Set<ReasonCode> bindingCodes, Set<ReasonCode> padCodes, int[] scores)
            throws EngineException {
        FaceObservation obs = analyzer.analyze(image);
        if (obs.faceCount() == 0) {
            bindingCodes.add(ReasonCode.CAPTURE_NO_FACE);
            return;
        }
        if (obs.faceCount() > 1) {
            bindingCodes.add(ReasonCode.CAPTURE_MULTI_FACE);
            return;
        }
        DetectedFace face = obs.faces().get(0);
        int similarity = identity.similarity(image, face.box());
        if (similarity >= 0) {
            scores[0] = similarity;
            if (similarity < policy.bindingMinSimilarity()) {
                bindingCodes.add(ReasonCode.SIMILARITY_LOW);
            }
        } else if (identity.isEnabled()) {
            // An embedder is configured but no similarity exists: the capture cannot be shown to be the same face.
            bindingCodes.add(ReasonCode.SIMILARITY_LOW);
        }
        scores[1] = pad.score(image, face.box()).score100();
        if (scores[1] < policy.padRejectThreshold()) {
            padCodes.add(ReasonCode.CAPTURE_PAD_SCORE);
        }
    }

    private static BindingVerdict verdict(Set<ReasonCode> bindingCodes, Set<ReasonCode> padCodes,
            ChallengeEchoStatus echo, long gapMs, int[] scores) {
        BindingVerdict.Builder b;
        if (!padCodes.isEmpty()) {
            Set<ReasonCode> all = EnumSet.copyOf(padCodes);
            all.addAll(bindingCodes);
            b = BindingVerdict.failed(FailureReason.PAD, all.toArray(new ReasonCode[0]));
        } else if (!bindingCodes.isEmpty()) {
            b = BindingVerdict.failed(FailureReason.BINDING, bindingCodes.toArray(new ReasonCode[0]));
        } else {
            b = BindingVerdict.passed();
        }
        b.challengeEcho(echo).gapMs(gapMs);
        if (scores[0] >= 0) {
            b.similarity(scores[0]);
        }
        if (scores[1] >= 0) {
            b.capturePadScore(scores[1]);
        }
        return b.build();
    }
}
