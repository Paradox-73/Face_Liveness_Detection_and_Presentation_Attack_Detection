package io.mosip.liveness.eval;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.api.LivenessResult;

import java.util.List;

/**
 * ISO/IEC 30107-3 classification of one attempt or transaction.
 *
 * <p>Rule (documented in {@code docs/eval/protocol.md}): an attempt is {@link #ACCEPT}ed when the engine passes
 * it; it is a {@link #REJECT} when the engine made a liveness decision against it, which is exactly when
 * {@link FailureReason#countsAsAttempt()} is true (PAD, integrity, binding, wrong or missing challenge action,
 * below threshold); any other ending (no usable face, device or engine error, runner time limit) is a
 * {@link #NO_RESPONSE} and goes into APNRR or BPNRR, never into APCER or BPCER.
 */
enum Outcome {

    /** Classified as bona fide. */
    ACCEPT,
    /** Classified as an attack or as not live. */
    REJECT,
    /** No liveness decision was produced. */
    NO_RESPONSE;

    /**
     * Classifies one attempt.
     *
     * @param result the session result
     * @return the outcome
     */
    static Outcome of(LivenessResult result) {
        if (result.isPass()) {
            return ACCEPT;
        }
        FailureReason reason = result.failureReason().orElse(FailureReason.ENGINE);
        return reason.countsAsAttempt() ? REJECT : NO_RESPONSE;
    }

    /**
     * Classifies a transaction from its attempts in order: accepted if any attempt was accepted, rejected if any
     * attempt was rejected, otherwise no response.
     *
     * @param attempts outcomes of the attempts; not empty
     * @return the transaction outcome
     */
    static Outcome ofTransaction(List<Outcome> attempts) {
        if (attempts.isEmpty()) {
            throw new IllegalArgumentException("a transaction has at least one attempt");
        }
        if (attempts.contains(ACCEPT)) {
            return ACCEPT;
        }
        return attempts.contains(REJECT) ? REJECT : NO_RESPONSE;
    }
}
