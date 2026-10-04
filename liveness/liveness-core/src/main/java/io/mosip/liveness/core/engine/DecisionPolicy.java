/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.PolicyMode;

/**
 * The passive-to-active decision of docs/design/decision-flow.md, as a pure function (table-tested at threshold - 1,
 * threshold and threshold + 1).
 *
 * <pre>
 * integrity violation                    -&gt; FAILED(INTEGRITY)
 * S &lt; pad.reject_threshold               -&gt; FAILED(PAD)
 * OFF                                    -&gt; CAPTURE_READY (method NONE)
 * PASSIVE_ONLY        S &gt;= threshold     -&gt; CAPTURE_READY (PASSIVE), else FAILED(BELOW_THRESHOLD)
 * PASSIVE_THEN_ACTIVE S &gt;= threshold     -&gt; CAPTURE_READY (PASSIVE)
 *                     else active usable -&gt; challenges (PASSIVE_ACTIVE, reason BELOW_THRESHOLD)
 *                     else               -&gt; FAILED(BELOW_THRESHOLD)
 * ALWAYS_ACTIVE       active usable      -&gt; challenges (ACTIVE, reason POLICY)
 *                     else               -&gt; FAILED(ENGINE): the policy cannot be met on this stream
 * </pre>
 * "Active usable" means {@code active.enable=Y} and at least one challenge type can run on the stream.
 * {@code ALWAYS_ACTIVE} ignores {@code active.enable}, because that key only controls escalation.
 */
final class DecisionPolicy {

    /** What happens after the passive phase. */
    enum Outcome {
        /** Liveness is satisfied; issue the pass token. */
        CAPTURE_READY,
        /** Start the challenges. */
        CHALLENGE,
        /** The session fails. */
        FAIL
    }

    final Outcome outcome;
    final LivenessMethod method;
    final FailureReason failureReason;
    final ReasonCode reason;

    private DecisionPolicy(Outcome outcome, LivenessMethod method, FailureReason failureReason, ReasonCode reason) {
        this.outcome = outcome;
        this.method = method;
        this.failureReason = failureReason;
        this.reason = reason;
    }

    /**
     * Decides what follows the passive phase.
     *
     * @param policy             resolved workflow policy
     * @param score              passive score 0-100
     * @param integrityViolation whether a frame integrity check failed
     * @param challengesUsable   whether at least one challenge type can run on this stream
     * @return the decision
     */
    static DecisionPolicy decide(LivenessPolicy policy, int score, boolean integrityViolation,
            boolean challengesUsable) {
        if (integrityViolation) {
            return fail(FailureReason.INTEGRITY, ReasonCode.INVALID_FRAMES);
        }
        switch (policy.mode()) {
            case OFF:
                return new DecisionPolicy(Outcome.CAPTURE_READY, LivenessMethod.NONE, null, null);
            case ALWAYS_ACTIVE:
                if (score < policy.padRejectThreshold()) {
                    return fail(FailureReason.PAD, ReasonCode.PAD_SCORE);
                }
                return challengesUsable
                        ? new DecisionPolicy(Outcome.CHALLENGE, LivenessMethod.ACTIVE, null, ReasonCode.POLICY)
                        : fail(FailureReason.ENGINE, ReasonCode.ENGINE_ERROR);
            default:
                return passive(policy, score, challengesUsable);
        }
    }

    private static DecisionPolicy passive(LivenessPolicy policy, int score, boolean challengesUsable) {
        if (score < policy.padRejectThreshold()) {
            return fail(FailureReason.PAD, ReasonCode.PAD_SCORE);
        }
        if (score >= policy.passiveThreshold()) {
            return new DecisionPolicy(Outcome.CAPTURE_READY, LivenessMethod.PASSIVE, null, null);
        }
        boolean escalate = policy.mode() == PolicyMode.PASSIVE_THEN_ACTIVE
                && policy.isActiveEnabled() && challengesUsable;
        return escalate
                ? new DecisionPolicy(Outcome.CHALLENGE, LivenessMethod.PASSIVE_ACTIVE, null, ReasonCode.BELOW_THRESHOLD)
                : fail(FailureReason.BELOW_THRESHOLD, ReasonCode.BELOW_THRESHOLD);
    }

    private static DecisionPolicy fail(FailureReason failureReason, ReasonCode reason) {
        return new DecisionPolicy(Outcome.FAIL, null, failureReason, reason);
    }

    @Override
    public String toString() {
        return "Decision[" + outcome + ", " + method + ", " + failureReason + ", " + reason + "]";
    }
}
