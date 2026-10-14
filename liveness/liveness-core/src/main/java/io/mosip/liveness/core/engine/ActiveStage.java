/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.RandomSource;
import io.mosip.liveness.core.api.ChallengeOutcome;
import io.mosip.liveness.core.api.ChallengeRecord;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.vision.FacePoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs the drawn challenge sequence: random wait and neutral face before each prompt, observation of the action,
 * "Action detected" display, and planarity data on head turns (design sections 4.5 and 4.7). After the last
 * challenge, {@link #startCapturePose(long)} waits for a neutral, frontal face held still, so the stored frame never
 * shows a challenge expression or a turned head.
 *
 * <p>The session drives it with frames and clock ticks and reacts to the returned {@link Event}s. Between
 * {@link Event#NEUTRAL_READY} and {@link #prompt(long)} the session runs its same-person check on the neutral
 * frame.
 */
final class ActiveStage {

    /** What the last call changed. */
    enum Event {
        /** Nothing changed. */
        NONE,
        /** The face has been neutral long enough after the random wait; the prompt may be shown. */
        NEUTRAL_READY,
        /** The current challenge passed; "Action detected" is shown. */
        CHALLENGE_PASSED,
        /** The display ended and the wait before the next challenge started. */
        NEXT_CHALLENGE,
        /** The display after the last challenge ended. */
        ALL_DONE,
        /** The current challenge failed, or no neutral face came in time. */
        FAILED,
        /** After the last challenge, a neutral, frontal face has been held for {@code active.neutral_hold_ms}. */
        CAPTURE_POSE_READY,
        /** After the last challenge, no neutral, frontal face was held within {@code challenge_timeout_ms}. */
        CAPTURE_POSE_TIMEOUT
    }

    private enum Phase { WAIT, OBSERVE, PASSED, POSE, DONE, FAILED }

    private final List<ChallengeType> sequence;
    private final List<ChallengeType> detectable;
    private final LivenessPolicy policy;
    private final EngineParameters params;
    private final RandomSource random;
    private final NeutralBaseline neutral = new NeutralBaseline();
    private final ActionLevels levels;
    private final List<ChallengeRecord> records = new ArrayList<>();
    private final List<Double> planarityResiduals = new ArrayList<>();

    private Phase phase;
    private int index;
    private long delayUntilNanos;
    private long neutralDeadlineNanos;
    private long neutralSinceNanos = -1;
    private List<FacePoint> neutralPoints = Collections.emptyList();
    private double neutralIed = Double.NaN;
    private ChallengeObserver observer;
    private List<FacePoint> turnedPoints;
    private double maxTurnDeg;
    private long passedUntilNanos;
    private long poseSinceNanos = -1;
    private long poseDeadlineNanos;
    private boolean poseNeutral;

    ActiveStage(List<ChallengeType> sequence, List<ChallengeType> detectable, LivenessPolicy policy,
            EngineParameters params, RandomSource random, long nowNanos) {
        this.sequence = Collections.unmodifiableList(new ArrayList<>(sequence));
        this.detectable = detectable;
        this.policy = policy;
        this.params = params;
        this.random = random;
        this.levels = new ActionLevels(params);
        startWait(nowNanos);
    }

    private void startWait(long nowNanos) {
        long min = params.getLong(EngineParameter.CHALLENGE_DELAY_MIN_MS);
        long max = params.getLong(EngineParameter.CHALLENGE_DELAY_MAX_MS);
        long delayMs = min + random.nextInt((int) (max - min) + 1);
        phase = Phase.WAIT;
        delayUntilNanos = nowNanos + delayMs * 1_000_000L;
        neutralDeadlineNanos = delayUntilNanos + policy.challengeTimeoutMs() * 1_000_000L;
        neutralSinceNanos = -1;
        neutral.clear();
        observer = null;
    }

    /**
     * Processes one analysed frame.
     *
     * @param nanos  frame time
     * @param s      measures of the subject's face, or {@code null} without a single face
     * @param points rigid landmarks of the face (possibly empty)
     * @return what changed
     */
    Event onFrame(long nanos, FaceSignals s, List<FacePoint> points) {
        return onFrame(nanos, s, points, true);
    }

    /**
     * Processes one analysed frame.
     *
     * @param nanos  frame time
     * @param s      measures of the subject's face, or {@code null} without a single face
     * @param points rigid landmarks of the face (possibly empty)
     * @param good   whether the frame passed the acquisition quality gate (used for the capture pose)
     * @return what changed
     */
    Event onFrame(long nanos, FaceSignals s, List<FacePoint> points, boolean good) {
        poseNeutral = false;
        Event timed = onTime(nanos);
        if (timed != Event.NONE) {
            return timed;
        }
        switch (phase) {
            case WAIT:
                return waitFrame(nanos, s, points);
            case OBSERVE:
                return observeFrame(nanos, s, points);
            case POSE:
                return poseFrame(nanos, s, good);
            default:
                return Event.NONE;
        }
    }

    /**
     * Applies the timers: neutral deadline, challenge timeout and end of the "Action detected" display.
     *
     * @param nanos current time
     * @return what changed
     */
    Event onTime(long nanos) {
        switch (phase) {
            case WAIT:
                if (nanos > neutralDeadlineNanos) {
                    phase = Phase.FAILED;
                    return Event.FAILED;
                }
                return Event.NONE;
            case OBSERVE:
                return observer.onTime(nanos) == ChallengeObserver.Status.FAILED ? challengeFailed(nanos) : Event.NONE;
            case PASSED:
                if (nanos >= passedUntilNanos) {
                    index++;
                    if (index < sequence.size()) {
                        startWait(nanos);
                        return Event.NEXT_CHALLENGE;
                    }
                    phase = Phase.DONE;
                    return Event.ALL_DONE;
                }
                return Event.NONE;
            case POSE:
                if (nanos > poseDeadlineNanos) {
                    phase = Phase.FAILED;
                    return Event.CAPTURE_POSE_TIMEOUT;
                }
                return Event.NONE;
            default:
                return Event.NONE;
        }
    }

    private Event waitFrame(long nanos, FaceSignals s, List<FacePoint> points) {
        if (!isNeutral(s)) {
            neutralSinceNanos = -1;
            neutral.clear();
            return Event.NONE;
        }
        if (neutralSinceNanos < 0) {
            neutralSinceNanos = nanos;
            neutral.clear();
        }
        neutral.add(s);
        neutralPoints = points;
        neutralIed = s.interEyeDistance;
        long holdNanos = params.getLong(EngineParameter.NEUTRAL_HOLD_MS) * 1_000_000L;
        boolean ready = nanos >= delayUntilNanos && nanos - neutralSinceNanos >= holdNanos;
        return ready ? Event.NEUTRAL_READY : Event.NONE;
    }

    /**
     * Tells whether a face is neutral: frontal within the neutral limits and eyes open. Without a head pose any
     * single face counts as neutral.
     */
    boolean isNeutral(FaceSignals s) {
        if (s == null) {
            return false;
        }
        if (s.hasPose() && (Math.abs(s.yaw) > params.get(EngineParameter.NEUTRAL_MAX_YAW_DEG)
                || Math.abs(s.pitch) > params.get(EngineParameter.NEUTRAL_MAX_PITCH_DEG))) {
            return false;
        }
        return Double.isNaN(s.ear) || s.ear >= params.get(EngineParameter.EYES_OPEN_MIN_EAR);
    }

    /**
     * After the last challenge: waits for the capture pose, a face that {@link #isCaptureNeutral} accepts on
     * consecutive frames that pass the quality gate, for {@code active.neutral_hold_ms}, within
     * {@code challenge_timeout_ms}.
     *
     * @param nanos current time
     */
    void startCapturePose(long nanos) {
        phase = Phase.POSE;
        poseSinceNanos = -1;
        poseNeutral = false;
        poseDeadlineNanos = nanos + policy.challengeTimeoutMs() * 1_000_000L;
    }

    private Event poseFrame(long nanos, FaceSignals s, boolean good) {
        if (!good || !isCaptureNeutral(s)) {
            poseSinceNanos = -1;
            return Event.NONE;
        }
        poseNeutral = true;
        if (poseSinceNanos < 0) {
            poseSinceNanos = nanos;
        }
        if (nanos - poseSinceNanos >= params.getLong(EngineParameter.NEUTRAL_HOLD_MS) * 1_000_000L) {
            phase = Phase.DONE;
            return Event.CAPTURE_POSE_READY;
        }
        return Event.NONE;
    }

    /**
     * Tells whether a face may be stored after the challenges: eyes open, and neither a smile nor a blink at or above
     * {@code active.onset_fraction} of its trigger compared with the neutral face before the last prompt. The head
     * pose limits are those of the quality gate, which the caller checks. A measure the analyser does not give is not
     * checked.
     *
     * @param s measures of the face, or {@code null} without a single face
     * @return {@code true} if the face is neutral enough to store
     */
    boolean isCaptureNeutral(FaceSignals s) {
        if (s == null) {
            return false;
        }
        if (!Double.isNaN(s.ear) && s.ear < params.get(EngineParameter.EYES_OPEN_MIN_EAR)) {
            return false;
        }
        double onset = params.get(EngineParameter.ONSET_FRACTION);
        return !(levels.level(ChallengeType.BLINK, s, neutral) >= onset)
                && !(levels.level(ChallengeType.SMILE, s, neutral) >= onset);
    }

    /**
     * Shows the current challenge: starts observing it against the neutral baseline.
     *
     * @param nanos prompt time
     * @return the challenge now prompted
     */
    ChallengeType prompt(long nanos) {
        ChallengeType type = sequence.get(index);
        observer = new ChallengeObserver(type, neutral.copy(), contraryTypes(type), nanos, policy, params);
        phase = Phase.OBSERVE;
        turnedPoints = null;
        maxTurnDeg = 0;
        return type;
    }

    private List<ChallengeType> contraryTypes(ChallengeType target) {
        List<ChallengeType> out = new ArrayList<>();
        for (ChallengeType t : detectable) {
            boolean ignored = t == target || t == ChallengeType.BLINK || (target.isHeadMovement() && t.isGaze());
            if (!ignored) {
                out.add(t);
            }
        }
        return out;
    }

    private Event observeFrame(long nanos, FaceSignals s, List<FacePoint> points) {
        if (s != null && observer.target().isHeadTurn() && !Double.isNaN(s.yaw)) {
            double turn = Math.abs(s.yaw - observer.baseline().yaw());
            if (turn > maxTurnDeg && points.size() == neutralPoints.size() && !points.isEmpty()) {
                maxTurnDeg = turn;
                turnedPoints = points;
            }
        }
        ChallengeObserver.Status st = observer.onFrame(nanos, s);
        if (st == ChallengeObserver.Status.PASSED) {
            ChallengeType type = observer.target();
            long duration = observer.durationMs(nanos);
            records.add(ChallengeRecord.passed(type, index, observer.onsetMs(), observer.holdMs(), duration));
            if (type.isHeadTurn()) {
                planarityResiduals.add(PlanarityChecker.residual(neutralPoints, turnedPoints, neutralIed));
            }
            phase = Phase.PASSED;
            passedUntilNanos = nanos + params.getLong(EngineParameter.PASSED_DISPLAY_MS) * 1_000_000L;
            return Event.CHALLENGE_PASSED;
        }
        return st == ChallengeObserver.Status.FAILED ? challengeFailed(nanos) : Event.NONE;
    }

    private Event challengeFailed(long nanos) {
        records.add(ChallengeRecord.failed(observer.target(), index, observer.outcome(), observer.durationMs(nanos)));
        phase = Phase.FAILED;
        return Event.FAILED;
    }

    /**
     * Ends the stage from outside. A prompted challenge is recorded with the given outcome.
     *
     * @param nanos   current time
     * @param outcome outcome of the interrupted challenge
     */
    void abort(long nanos, ChallengeOutcome outcome) {
        if (phase == Phase.OBSERVE) {
            observer.abort(nanos, outcome);
            records.add(ChallengeRecord.failed(observer.target(), index, outcome, observer.durationMs(nanos)));
        }
        phase = Phase.FAILED;
    }

    /**
     * Returns the observer of the current or last prompted challenge.
     *
     * @return the observer, or {@code null} before the first prompt of a wait
     */
    ChallengeObserver observer() {
        return observer;
    }

    boolean isNeutralNow() {
        return phase == Phase.WAIT && neutralSinceNanos >= 0;
    }

    /**
     * Tells whether the wait for the capture pose runs.
     *
     * @return {@code true} between {@link #startCapturePose(long)} and its ready or timeout event
     */
    boolean isWaitingForCapturePose() {
        return phase == Phase.POSE;
    }

    /**
     * Tells whether the last frame showed the capture pose (neutral, frontal, through the quality gate).
     *
     * @return {@code true} if that frame may be stored
     */
    boolean isCapturePoseFrame() {
        return poseNeutral;
    }

    int index() {
        return index;
    }

    int count() {
        return sequence.size();
    }

    ChallengeType current() {
        return sequence.get(Math.min(index, sequence.size() - 1));
    }

    List<ChallengeRecord> records() {
        return records;
    }

    List<Double> planarityResiduals() {
        return planarityResiduals;
    }
}
