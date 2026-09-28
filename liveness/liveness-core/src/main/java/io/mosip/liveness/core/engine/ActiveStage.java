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
 * "Action detected" display, and planarity data on head turns (design sections 4.5 and 4.7).
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
        FAILED
    }

    private enum Phase { WAIT, OBSERVE, PASSED, DONE, FAILED }

    private final List<ChallengeType> sequence;
    private final List<ChallengeType> detectable;
    private final LivenessPolicy policy;
    private final EngineParameters params;
    private final RandomSource random;
    private final NeutralBaseline neutral = new NeutralBaseline();
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
    private long totalChallengeMs;

    ActiveStage(List<ChallengeType> sequence, List<ChallengeType> detectable, LivenessPolicy policy,
            EngineParameters params, RandomSource random, long nowNanos) {
        this.sequence = Collections.unmodifiableList(new ArrayList<>(sequence));
        this.detectable = detectable;
        this.policy = policy;
        this.params = params;
        this.random = random;
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
        Event timed = onTime(nanos);
        if (timed != Event.NONE) {
            return timed;
        }
        switch (phase) {
            case WAIT:
                return waitFrame(nanos, s, points);
            case OBSERVE:
                return observeFrame(nanos, s, points);
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
            totalChallengeMs += duration;
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

    boolean isObserving() {
        return phase == Phase.OBSERVE;
    }

    boolean isWaiting() {
        return phase == Phase.WAIT;
    }

    boolean isNeutralNow() {
        return phase == Phase.WAIT && neutralSinceNanos >= 0;
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

    List<ChallengeType> sequence() {
        return sequence;
    }

    List<ChallengeRecord> records() {
        return records;
    }

    List<Double> planarityResiduals() {
        return planarityResiduals;
    }

    long totalChallengeMs() {
        return totalChallengeMs;
    }
}
