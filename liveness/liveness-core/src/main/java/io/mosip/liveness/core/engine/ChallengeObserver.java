package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.api.ChallengeOutcome;
import io.mosip.liveness.core.config.LivenessPolicy;

import java.util.List;

/**
 * Watches one prompted challenge and decides whether the requested action was performed (design section 4.7).
 *
 * <p>Rules, in the order they are applied to each frame:
 * <ol>
 *   <li>timeout: no success within {@code challenge_timeout_ms} fails with {@code TIMEOUT}, or with
 *       {@code NOT_HELD} if the action reached its trigger but was never held long enough;</li>
 *   <li>exclusivity: any other detectable action at or above its trigger on {@code contrary_min_frames} frames in
 *       a row fails with {@code WRONG_ACTION} (spontaneous blinks are ignored, gaze is ignored during head
 *       movements, because eyes lead the head when it turns, and the smile is unmeasured while the head is turned
 *       more than {@code active.smile_max_yaw_deg}, see {@link ActionLevels});</li>
 *   <li>onset: the uninterrupted run above the onset level that leads to the trigger must start at least
 *       {@code min_onset_ms} after the prompt, otherwise {@code ONSET_TOO_FAST};</li>
 *   <li>hold: the action must stay at or above its trigger for {@code hold_ms} and at least two frames; a blink
 *       instead must close the eyes and reopen them within 80-500 ms.</li>
 * </ol>
 * A frame without exactly one measured face gives no information and changes nothing.
 */
final class ChallengeObserver {

    /** Progress of the challenge. */
    enum Status {
        /** Still waiting for the action. */
        PENDING,
        /** The action was performed correctly. */
        PASSED,
        /** The challenge failed; see {@link #outcome()}. */
        FAILED
    }

    private final ChallengeType target;
    private final NeutralBaseline baseline;
    private final List<ChallengeType> contrary;
    private final ActionLevels levels;
    private final long promptNanos;
    private final long minOnsetNanos;
    private final long holdNanos;
    private final long timeoutNanos;
    private final double onsetFraction;
    private final double blinkOpenRatio;
    private final long blinkMinNanos;
    private final long blinkMaxNanos;
    private final int contraryMinFrames;
    private final int[] contraryRuns = new int[ChallengeType.values().length];

    private long runStartNanos = -1;
    private long triggerNanos = -1;
    private int triggeredFrames;
    private boolean everTriggered;
    private boolean awaitingReopen;
    private long onsetNanos = -1;
    private Status status = Status.PENDING;
    private ChallengeOutcome outcome;
    private ReasonCode reason;
    private long holdMs;
    private long endNanos;

    ChallengeObserver(ChallengeType target, NeutralBaseline baseline, List<ChallengeType> contrary,
            long promptNanos, LivenessPolicy policy, EngineParameters p) {
        this.target = target;
        this.baseline = baseline;
        this.contrary = contrary;
        this.levels = new ActionLevels(p);
        this.promptNanos = promptNanos;
        this.minOnsetNanos = policy.minOnsetMs() * 1_000_000L;
        this.holdNanos = policy.holdMs() * 1_000_000L;
        this.timeoutNanos = policy.challengeTimeoutMs() * 1_000_000L;
        this.onsetFraction = p.get(EngineParameter.ONSET_FRACTION);
        this.blinkOpenRatio = p.get(EngineParameter.BLINK_OPEN_RATIO);
        this.blinkMinNanos = p.getLong(EngineParameter.BLINK_MIN_CLOSED_MS) * 1_000_000L;
        this.blinkMaxNanos = p.getLong(EngineParameter.BLINK_MAX_CLOSED_MS) * 1_000_000L;
        this.contraryMinFrames = p.getInt(EngineParameter.CONTRARY_MIN_FRAMES);
    }

    /**
     * Processes one frame.
     *
     * @param nanos frame time
     * @param s     measures of the subject's face, or {@code null} if the frame has no single face
     * @return the status after this frame
     */
    Status onFrame(long nanos, FaceSignals s) {
        if (status != Status.PENDING || onTime(nanos) != Status.PENDING || s == null) {
            return status;
        }
        if (checkContrary(nanos, s)) {
            return status;
        }
        return target == ChallengeType.BLINK ? blinkFrame(nanos, s) : holdFrame(nanos, s);
    }

    /**
     * Applies the timeout.
     *
     * @param nanos current time
     * @return the status
     */
    Status onTime(long nanos) {
        if (status == Status.PENDING && nanos - promptNanos > timeoutNanos) {
            if (everTriggered) {
                fail(nanos, ChallengeOutcome.NOT_HELD, ReasonCode.NOT_HELD);
            } else {
                fail(nanos, ChallengeOutcome.TIMEOUT, ReasonCode.CHALLENGE_TIMEOUT);
            }
        }
        return status;
    }

    private boolean checkContrary(long nanos, FaceSignals s) {
        for (ChallengeType other : contrary) {
            double l = levels.level(other, s, baseline);
            int i = other.ordinal();
            contraryRuns[i] = l >= 1 ? contraryRuns[i] + 1 : 0;
            if (contraryRuns[i] >= contraryMinFrames) {
                fail(nanos, ChallengeOutcome.WRONG_ACTION, ReasonCode.WRONG_ACTION);
                return true;
            }
        }
        return false;
    }

    private Status holdFrame(long nanos, FaceSignals s) {
        double l = levels.level(target, s, baseline);
        if (Double.isNaN(l)) {
            return status;
        }
        trackRun(nanos, l);
        if (l < 1) {
            triggerNanos = -1;
            triggeredFrames = 0;
            return status;
        }
        if (triggerNanos < 0 && !startTrigger(nanos)) {
            return status;
        }
        triggeredFrames++;
        long held = nanos - triggerNanos;
        int framesNeeded = holdNanos == 0 ? 1 : 2;
        if (held >= holdNanos && triggeredFrames >= framesNeeded) {
            pass(nanos, held);
        }
        return status;
    }

    private Status blinkFrame(long nanos, FaceSignals s) {
        double l = levels.level(ChallengeType.BLINK, s, baseline);
        if (Double.isNaN(l)) {
            return status;
        }
        trackRun(nanos, l);
        boolean open = s.ear >= baseline.ear() * blinkOpenRatio;
        if (triggerNanos < 0) {
            if (awaitingReopen) {
                awaitingReopen = !open;
            } else if (l >= 1) {
                startTrigger(nanos);
            }
            return status;
        }
        long closed = nanos - triggerNanos;
        if (open) {
            if (closed >= blinkMinNanos && closed <= blinkMaxNanos) {
                pass(nanos, closed);
            } else {
                triggerNanos = -1;
            }
        } else if (closed > blinkMaxNanos) {
            // Eyes held closed: not a blink. A new blink needs the eyes to open first.
            triggerNanos = -1;
            awaitingReopen = true;
        }
        return status;
    }

    private void trackRun(long nanos, double level) {
        if (level >= onsetFraction) {
            if (runStartNanos < 0) {
                runStartNanos = nanos;
            }
        } else {
            runStartNanos = -1;
        }
    }

    /**
     * Starts a trigger period and applies the onset rule.
     *
     * @return {@code false} if the onset was too fast and the challenge failed
     */
    private boolean startTrigger(long nanos) {
        long onset = runStartNanos >= 0 ? runStartNanos : nanos;
        everTriggered = true;
        if (onset - promptNanos < minOnsetNanos) {
            onsetNanos = onset;
            fail(nanos, ChallengeOutcome.ONSET_TOO_FAST, ReasonCode.ONSET_TOO_FAST);
            return false;
        }
        onsetNanos = onset;
        triggerNanos = nanos;
        triggeredFrames = 0;
        return true;
    }

    private void pass(long nanos, long heldNanos) {
        status = Status.PASSED;
        outcome = ChallengeOutcome.PASSED;
        holdMs = heldNanos / 1_000_000L;
        endNanos = nanos;
    }

    private void fail(long nanos, ChallengeOutcome o, ReasonCode r) {
        status = Status.FAILED;
        outcome = o;
        reason = r;
        endNanos = nanos;
    }

    /**
     * Ends a pending challenge from outside (PAD failure, integrity failure, cancel, device error).
     *
     * @param nanos current time
     * @param o     outcome to record
     */
    void abort(long nanos, ChallengeOutcome o) {
        if (status == Status.PENDING) {
            fail(nanos, o, null);
        }
    }

    ChallengeType target() {
        return target;
    }

    NeutralBaseline baseline() {
        return baseline;
    }

    Status status() {
        return status;
    }

    ChallengeOutcome outcome() {
        return outcome;
    }

    /**
     * Returns the reason code of a failure detected by this observer.
     *
     * @return the code, or {@code null} for a pass or an abort
     */
    ReasonCode reason() {
        return reason;
    }

    /**
     * Returns the session failure reason of the outcome.
     *
     * @return TIMEOUT for a timeout, ACTIVE for the other challenge failures
     */
    FailureReason failureReason() {
        return outcome == ChallengeOutcome.TIMEOUT ? FailureReason.TIMEOUT : FailureReason.ACTIVE;
    }

    long onsetMs() {
        return onsetNanos < 0 ? 0 : Math.max(0, (onsetNanos - promptNanos) / 1_000_000L);
    }

    long holdMs() {
        return holdMs;
    }

    long durationMs(long nowNanos) {
        long end = status == Status.PENDING ? nowNanos : endNanos;
        return Math.max(0, (end - promptNanos) / 1_000_000L);
    }

    long promptNanos() {
        return promptNanos;
    }
}
