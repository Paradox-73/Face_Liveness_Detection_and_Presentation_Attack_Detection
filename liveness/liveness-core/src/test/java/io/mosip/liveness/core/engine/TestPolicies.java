package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.PolicyMode;

/**
 * Policies for engine tests that exercise the passive-first mechanics (passive pass, escalation, binding after a
 * passive pass) independently of the shipped defaults, which make registration and operator sign-in always
 * active and collect 30 passive frames.
 */
final class TestPolicies {

    /** Passive frames of the test policies: the value the engine tests were written for. */
    static final int PASSIVE_MIN_FRAMES = 8;

    private TestPolicies() {
    }

    /**
     * Returns the defaults of a workflow with the passive-first mode (unless the workflow is off) and 8 passive
     * frames.
     *
     * @param workflow the workflow
     * @return the policy
     */
    static LivenessPolicy passiveFirst(Workflow workflow) {
        LivenessPolicy defaults = LivenessPolicy.defaults(workflow);
        LivenessPolicy.Builder b = defaults.toBuilder().passiveMinFrames(PASSIVE_MIN_FRAMES);
        if (defaults.mode() != PolicyMode.OFF) {
            b.mode(PolicyMode.PASSIVE_THEN_ACTIVE);
        }
        return b.build();
    }
}
