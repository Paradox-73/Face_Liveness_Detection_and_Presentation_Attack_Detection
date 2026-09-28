package io.mosip.liveness.core.api;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Result of stream-to-capture binding ({@link LivenessSession#bind(BindingRequest)}).
 *
 * <p>A failed verdict carries {@link FailureReason#BINDING}, or {@link FailureReason#PAD} when the captured image
 * or a device PAD block indicates an attack, plus the reason codes. The similarity and gap feed audit event
 * {@code REG-EVT-LV09} as buckets and the packet evidence {@code LIVENESS_BINDING}.
 */
public final class BindingVerdict {

    private final FailureReason failureReason;
    private final Set<ReasonCode> reasonCodes;
    private final Integer similarity;
    private final Long gapMs;
    private final ChallengeEchoStatus challengeEcho;
    private final Integer capturePadScore;

    private BindingVerdict(Builder b) {
        this.failureReason = b.failureReason;
        this.reasonCodes = Collections.unmodifiableSet(b.reasonCodes.isEmpty()
                ? EnumSet.noneOf(ReasonCode.class) : EnumSet.copyOf(b.reasonCodes));
        this.similarity = b.similarity;
        this.gapMs = b.gapMs;
        this.challengeEcho = b.challengeEcho;
        this.capturePadScore = b.capturePadScore;
    }

    /**
     * Starts a builder for a passing verdict.
     *
     * @return a builder
     */
    public static Builder passed() {
        return new Builder(null);
    }

    /**
     * Starts a builder for a failing verdict.
     *
     * @param reason {@link FailureReason#BINDING} or {@link FailureReason#PAD}
     * @param codes  at least one reason code
     * @return a builder
     * @throws IllegalArgumentException if the reason is another value or no code is given
     */
    public static Builder failed(FailureReason reason, ReasonCode... codes) {
        Objects.requireNonNull(reason, "reason");
        if (reason != FailureReason.BINDING && reason != FailureReason.PAD) {
            throw new IllegalArgumentException("binding fails with BINDING or PAD, not " + reason);
        }
        if (codes.length == 0) {
            throw new IllegalArgumentException("at least one reason code is required");
        }
        Builder b = new Builder(reason);
        for (ReasonCode c : codes) {
            b.reasonCodes.add(Objects.requireNonNull(c, "code"));
        }
        return b;
    }

    /**
     * Tells whether the capture is bound to the session.
     *
     * @return {@code true} if binding passed
     */
    public boolean isPassed() {
        return failureReason == null;
    }

    /**
     * Returns why binding failed.
     *
     * @return BINDING or PAD; empty if it passed
     */
    public Optional<FailureReason> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /**
     * Returns the reason codes of a failure.
     *
     * @return an unmodifiable set; empty if binding passed
     */
    public Set<ReasonCode> reasonCodes() {
        return reasonCodes;
    }

    /**
     * Returns the similarity between the captured face and the session reference face.
     *
     * @return similarity 0-100, if it was computed
     */
    public OptionalInt similarity() {
        return similarity == null ? OptionalInt.empty() : OptionalInt.of(similarity);
    }

    /**
     * Returns the time between the pass (or device Ready) and the capture.
     *
     * @return milliseconds, if known
     */
    public OptionalLong gapMs() {
        return gapMs == null ? OptionalLong.empty() : OptionalLong.of(gapMs);
    }

    /**
     * Returns the outcome of the PAD challenge echo check.
     *
     * @return the echo status
     */
    public ChallengeEchoStatus challengeEcho() {
        return challengeEcho;
    }

    /**
     * Returns the passive PAD score of the captured image.
     *
     * @return score 0-100, if it was computed
     */
    public OptionalInt capturePadScore() {
        return capturePadScore == null ? OptionalInt.empty() : OptionalInt.of(capturePadScore);
    }

    @Override
    public String toString() {
        return "BindingVerdict[" + (isPassed() ? "PASSED" : "FAILED(" + failureReason + ")") + ", codes=" + reasonCodes
                + ", similarity=" + similarity + ", gapMs=" + gapMs + ", echo=" + challengeEcho + "]";
    }

    /** Builder for {@link BindingVerdict}. */
    public static final class Builder {

        private final FailureReason failureReason;
        private final Set<ReasonCode> reasonCodes = EnumSet.noneOf(ReasonCode.class);
        private Integer similarity;
        private Long gapMs;
        private ChallengeEchoStatus challengeEcho = ChallengeEchoStatus.NOT_REQUESTED;
        private Integer capturePadScore;

        private Builder(FailureReason failureReason) {
            this.failureReason = failureReason;
        }

        /**
         * Sets the similarity.
         *
         * @param similarity 0-100
         * @return this builder
         */
        public Builder similarity(int similarity) {
            this.similarity = checkScore(similarity);
            return this;
        }

        /**
         * Sets the pass-to-capture gap.
         *
         * @param gapMs milliseconds; may be negative if the device clock is behind
         * @return this builder
         */
        public Builder gapMs(long gapMs) {
            this.gapMs = gapMs;
            return this;
        }

        /**
         * Sets the echo check outcome; default {@link ChallengeEchoStatus#NOT_REQUESTED}.
         *
         * @param challengeEcho echo status
         * @return this builder
         */
        public Builder challengeEcho(ChallengeEchoStatus challengeEcho) {
            this.challengeEcho = Objects.requireNonNull(challengeEcho, "challengeEcho");
            return this;
        }

        /**
         * Sets the PAD score of the captured image.
         *
         * @param score 0-100
         * @return this builder
         */
        public Builder capturePadScore(int score) {
            this.capturePadScore = checkScore(score);
            return this;
        }

        /**
         * Creates the verdict.
         *
         * @return the verdict
         * @throws IllegalArgumentException if a passing verdict has an unacceptable challenge echo
         */
        public BindingVerdict build() {
            if (failureReason == null && !challengeEcho.isAcceptable()) {
                throw new IllegalArgumentException("a passing verdict needs an acceptable challenge echo");
            }
            return new BindingVerdict(this);
        }

        private static Integer checkScore(int score) {
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("score must be 0-100");
            }
            return score;
        }
    }
}
