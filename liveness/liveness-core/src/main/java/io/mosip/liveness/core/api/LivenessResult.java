package io.mosip.liveness.core.api;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DevicePadResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Final outcome of a session: decision, reasons, scores, challenges, timings, and the engine, models and device
 * that produced it. Built once when the session reaches a terminal state.
 *
 * <p>Scores are for packet evidence and diagnostics only; they are never shown to the user or written to audits.
 */
public final class LivenessResult {

    private final String sessionId;
    private final Workflow workflow;
    private final int attempt;
    private final Decision decision;
    private final FailureReason failureReason;
    private final LivenessMethod method;
    private final Set<ReasonCode> reasonCodes;
    private final Integer passiveScore;
    private final Integer activePadScore;
    private final Integer bindingSimilarity;
    private final List<ChallengeRecord> challenges;
    private final Instant startedAt;
    private final Instant endedAt;
    private final long passiveMs;
    private final long activeMs;
    private final long totalMs;
    private final EngineInfo engine;
    private final DeviceDescriptor device;
    private final DevicePadResult devicePad;
    private final DeviceErrorCode deviceError;
    private final EngineErrorCode engineError;

    private LivenessResult(Builder b) {
        this.sessionId = b.sessionId;
        this.workflow = b.workflow;
        this.attempt = b.attempt;
        this.decision = b.failureReason == null ? Decision.PASS : b.failureReason.decision();
        this.failureReason = b.failureReason;
        this.method = b.method;
        this.reasonCodes = Collections.unmodifiableSet(b.reasonCodes.isEmpty()
                ? EnumSet.noneOf(ReasonCode.class) : EnumSet.copyOf(b.reasonCodes));
        this.passiveScore = b.passiveScore;
        this.activePadScore = b.activePadScore;
        this.bindingSimilarity = b.bindingSimilarity;
        this.challenges = Collections.unmodifiableList(new ArrayList<>(b.challenges));
        this.startedAt = b.startedAt;
        this.endedAt = b.endedAt;
        this.passiveMs = b.passiveMs;
        this.activeMs = b.activeMs;
        this.totalMs = b.totalMs;
        this.engine = b.engine;
        this.device = b.device;
        this.devicePad = b.devicePad;
        this.deviceError = b.deviceError;
        this.engineError = b.engineError;
    }

    /**
     * Starts a builder.
     *
     * @param sessionId session identifier
     * @param workflow  workflow
     * @param engine    engine that ran the session
     * @param device    device that streamed
     * @return a builder; call {@link Builder#pass()} or {@link Builder#fail(FailureReason)} before building
     */
    public static Builder builder(String sessionId, Workflow workflow, EngineInfo engine, DeviceDescriptor device) {
        return new Builder(sessionId, workflow, engine, device);
    }

    /**
     * Returns the session identifier.
     *
     * @return the identifier
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns the workflow.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return workflow;
    }

    /**
     * Returns the attempt number within the transaction.
     *
     * @return 1-based attempt
     */
    public int attempt() {
        return attempt;
    }

    /**
     * Returns the overall decision.
     *
     * @return PASS, or the decision of the failure reason
     */
    public Decision decision() {
        return decision;
    }

    /**
     * Tells whether the session passed.
     *
     * @return {@code true} for {@link Decision#PASS}
     */
    public boolean isPass() {
        return decision == Decision.PASS;
    }

    /**
     * Returns why the session did not pass.
     *
     * @return the failure reason; empty for a pass
     */
    public Optional<FailureReason> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /**
     * Tells whether the session ended uncounted because passive PAD failed in low light
     * ({@link ReasonCode#LOW_LIGHT}). Callers that track retries pass the number of such results since the last
     * counted failure to {@link SessionRequest.Builder#lowLightRetries(int)}.
     *
     * @return {@code true} for a {@link FailureReason#QUALITY} result with {@link ReasonCode#LOW_LIGHT}
     */
    public boolean isLowLightRetry() {
        return failureReason == FailureReason.QUALITY && reasonCodes.contains(ReasonCode.LOW_LIGHT);
    }

    /**
     * Returns which checks decided the session.
     *
     * @return the method
     */
    public LivenessMethod method() {
        return method;
    }

    /**
     * Returns the detailed reason codes (for audits and diagnostics, never for the screen).
     *
     * @return an unmodifiable set, possibly empty
     */
    public Set<ReasonCode> reasonCodes() {
        return reasonCodes;
    }

    /**
     * Returns the passive score: {@code round(100 * median live probability)} over the passive frames.
     *
     * @return score 0-100, if the passive phase completed
     */
    public OptionalInt passiveScore() {
        return optional(passiveScore);
    }

    /**
     * Returns the median PAD score during the active phase.
     *
     * @return score 0-100, if challenges ran
     */
    public OptionalInt activePadScore() {
        return optional(activePadScore);
    }

    /**
     * Returns the similarity between the captured face and the session face.
     *
     * @return similarity 0-100, if binding ran
     */
    public OptionalInt bindingSimilarity() {
        return optional(bindingSimilarity);
    }

    /**
     * Returns the challenges in the order they were shown.
     *
     * @return an unmodifiable list, possibly empty
     */
    public List<ChallengeRecord> challenges() {
        return challenges;
    }

    /**
     * Returns the challenges in packet evidence form, for example {@code BLINK:OK,TURN_LEFT:OK}.
     *
     * @return comma-separated evidence, or an empty string when no challenge ran
     */
    public String challengeEvidence() {
        StringBuilder sb = new StringBuilder();
        for (ChallengeRecord r : challenges) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(r.evidence());
        }
        return sb.toString();
    }

    /**
     * Returns when the session started.
     *
     * @return wall-clock start
     */
    public Instant startedAt() {
        return startedAt;
    }

    /**
     * Returns when the session ended.
     *
     * @return wall-clock end
     */
    public Instant endedAt() {
        return endedAt;
    }

    /**
     * Returns the time spent in acquisition and the passive phase.
     *
     * @return milliseconds (monotonic clock)
     */
    public long passiveMs() {
        return passiveMs;
    }

    /**
     * Returns the time spent in the active phase.
     *
     * @return milliseconds (monotonic clock); 0 if no challenge ran
     */
    public long activeMs() {
        return activeMs;
    }

    /**
     * Returns the total session time.
     *
     * @return milliseconds (monotonic clock)
     */
    public long totalMs() {
        return totalMs;
    }

    /**
     * Returns the engine and model versions that produced the result.
     *
     * @return the engine description
     */
    public EngineInfo engine() {
        return engine;
    }

    /**
     * Returns the device that streamed.
     *
     * @return the device descriptor
     */
    public DeviceDescriptor device() {
        return device;
    }

    /**
     * Returns the device's own PAD result from the signed capture.
     *
     * @return the device PAD result, if the capture carried one
     */
    public Optional<DevicePadResult> devicePad() {
        return Optional.ofNullable(devicePad);
    }

    /**
     * Returns the device error behind a {@link FailureReason#DEVICE} failure.
     *
     * @return the error code, if any
     */
    public Optional<DeviceErrorCode> deviceError() {
        return Optional.ofNullable(deviceError);
    }

    /**
     * Returns the engine error behind a {@link FailureReason#ENGINE} failure.
     *
     * @return the error code, if any
     */
    public Optional<EngineErrorCode> engineError() {
        return Optional.ofNullable(engineError);
    }

    private static OptionalInt optional(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    @Override
    public String toString() {
        return "LivenessResult[" + sessionId + ", " + workflow.id() + ", " + decision
                + (failureReason == null ? "" : "(" + failureReason + ")") + ", " + method + ", codes=" + reasonCodes
                + ", challenges=" + challengeEvidence() + ", totalMs=" + totalMs + ", engine=" + engine.tag() + "]";
    }

    /** Builder for {@link LivenessResult}. */
    public static final class Builder {

        private final String sessionId;
        private final Workflow workflow;
        private final EngineInfo engine;
        private final DeviceDescriptor device;
        private boolean outcomeSet;
        private FailureReason failureReason;
        private int attempt = 1;
        private LivenessMethod method;
        private final Set<ReasonCode> reasonCodes = EnumSet.noneOf(ReasonCode.class);
        private Integer passiveScore;
        private Integer activePadScore;
        private Integer bindingSimilarity;
        private final List<ChallengeRecord> challenges = new ArrayList<>();
        private Instant startedAt;
        private Instant endedAt;
        private long passiveMs;
        private long activeMs;
        private long totalMs;
        private DevicePadResult devicePad;
        private DeviceErrorCode deviceError;
        private EngineErrorCode engineError;

        private Builder(String sessionId, Workflow workflow, EngineInfo engine, DeviceDescriptor device) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.workflow = Objects.requireNonNull(workflow, "workflow");
            this.engine = Objects.requireNonNull(engine, "engine");
            this.device = Objects.requireNonNull(device, "device");
        }

        /**
         * Marks the session as passed.
         *
         * @return this builder
         */
        public Builder pass() {
            this.failureReason = null;
            this.outcomeSet = true;
            return this;
        }

        /**
         * Marks the session as failed or cancelled.
         *
         * @param reason why it did not pass
         * @return this builder
         */
        public Builder fail(FailureReason reason) {
            this.failureReason = Objects.requireNonNull(reason, "reason");
            this.outcomeSet = true;
            return this;
        }

        /**
         * Sets the attempt number; default 1.
         *
         * @param attempt 1-based attempt
         * @return this builder
         */
        public Builder attempt(int attempt) {
            this.attempt = attempt;
            return this;
        }

        /**
         * Sets the method; required.
         *
         * @param method which checks decided the session
         * @return this builder
         */
        public Builder method(LivenessMethod method) {
            this.method = method;
            return this;
        }

        /**
         * Adds a reason code.
         *
         * @param code reason code
         * @return this builder
         */
        public Builder reason(ReasonCode code) {
            reasonCodes.add(Objects.requireNonNull(code, "code"));
            return this;
        }

        /**
         * Sets the passive score.
         *
         * @param score 0-100
         * @return this builder
         */
        public Builder passiveScore(int score) {
            this.passiveScore = checkScore(score);
            return this;
        }

        /**
         * Sets the median PAD score of the active phase.
         *
         * @param score 0-100
         * @return this builder
         */
        public Builder activePadScore(int score) {
            this.activePadScore = checkScore(score);
            return this;
        }

        /**
         * Sets the binding similarity.
         *
         * @param similarity 0-100
         * @return this builder
         */
        public Builder bindingSimilarity(int similarity) {
            this.bindingSimilarity = checkScore(similarity);
            return this;
        }

        /**
         * Appends a challenge record.
         *
         * @param record the record
         * @return this builder
         */
        public Builder challenge(ChallengeRecord record) {
            challenges.add(Objects.requireNonNull(record, "record"));
            return this;
        }

        /**
         * Sets the wall-clock start and end; required.
         *
         * @param startedAt session start
         * @param endedAt   session end, not before the start
         * @return this builder
         */
        public Builder period(Instant startedAt, Instant endedAt) {
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            return this;
        }

        /**
         * Sets the phase durations measured with the monotonic clock.
         *
         * @param passiveMs acquisition and passive time
         * @param activeMs  active time
         * @param totalMs   total time, at least the sum of the two
         * @return this builder
         */
        public Builder timings(long passiveMs, long activeMs, long totalMs) {
            this.passiveMs = passiveMs;
            this.activeMs = activeMs;
            this.totalMs = totalMs;
            return this;
        }

        /**
         * Sets the device's own PAD result.
         *
         * @param devicePad result, or {@code null}
         * @return this builder
         */
        public Builder devicePad(DevicePadResult devicePad) {
            this.devicePad = devicePad;
            return this;
        }

        /**
         * Sets the device error behind a device failure.
         *
         * @param deviceError error code, or {@code null}
         * @return this builder
         */
        public Builder deviceError(DeviceErrorCode deviceError) {
            this.deviceError = deviceError;
            return this;
        }

        /**
         * Sets the engine error behind an engine failure.
         *
         * @param engineError error code, or {@code null}
         * @return this builder
         */
        public Builder engineError(EngineErrorCode engineError) {
            this.engineError = engineError;
            return this;
        }

        /**
         * Validates the fields and creates the result.
         *
         * @return the result
         * @throws IllegalStateException    if neither {@link #pass()} nor {@link #fail(FailureReason)} was called
         * @throws NullPointerException     if the method or period is missing
         * @throws IllegalArgumentException if the attempt, period or timings are inconsistent
         */
        public LivenessResult build() {
            if (!outcomeSet) {
                throw new IllegalStateException("call pass() or fail(reason) before build()");
            }
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(endedAt, "endedAt");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be at least 1");
            }
            if (endedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("endedAt must not be before startedAt");
            }
            if (passiveMs < 0 || activeMs < 0 || totalMs < passiveMs + activeMs) {
                throw new IllegalArgumentException("timings must be non-negative and total at least passive + active");
            }
            return new LivenessResult(this);
        }

        private static Integer checkScore(int score) {
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("score must be 0-100");
            }
            return score;
        }
    }
}
