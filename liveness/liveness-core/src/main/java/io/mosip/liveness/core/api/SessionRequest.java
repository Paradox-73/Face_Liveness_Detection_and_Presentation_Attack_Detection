/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.RandomSource;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.DeviceDescriptor;

import java.util.Objects;

/**
 * Everything an engine needs to open one liveness session.
 *
 * <p>The policy is resolved by the caller at session start, so a config sync applies from the next session.
 * The subject reference (registration ID or user ID) binds the pass token; it is never written to the
 * diagnostic log.
 */
public final class SessionRequest {

    /** Value of {@link #lowLightRetries()} when the caller does not track low-light results. */
    public static final int LOW_LIGHT_NOT_TRACKED = -1;

    private final LivenessPolicy policy;
    private final String subjectRef;
    private final DeviceDescriptor device;
    private final int attempt;
    private final int lowLightRetries;
    private final boolean priorSuspectedAttack;
    private final Clock clock;
    private final RandomSource random;
    private final DiagnosticSink diagnosticSink;

    private SessionRequest(Builder b) {
        this.policy = b.policy;
        this.subjectRef = b.subjectRef;
        this.device = b.device;
        this.attempt = b.attempt;
        this.lowLightRetries = b.lowLightRetries;
        this.priorSuspectedAttack = b.priorSuspectedAttack;
        this.clock = b.clock;
        this.random = b.random;
        this.diagnosticSink = b.diagnosticSink;
    }

    /**
     * Starts a builder.
     *
     * @param policy     resolved policy of the workflow
     * @param subjectRef registration ID (resident workflows) or user ID (authentication workflows)
     * @param device     the face device that streams
     * @return a builder with attempt 1, the system clock, secure randomness and no diagnostics
     */
    public static Builder builder(LivenessPolicy policy, String subjectRef, DeviceDescriptor device) {
        return new Builder(policy, subjectRef, device);
    }

    /**
     * Returns the workflow, taken from the policy.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return policy.workflow();
    }

    /**
     * Returns the resolved policy.
     *
     * @return the policy
     */
    public LivenessPolicy policy() {
        return policy;
    }

    /**
     * Returns the subject reference the pass token is bound to.
     *
     * @return registration ID or user ID
     */
    public String subjectRef() {
        return subjectRef;
    }

    /**
     * Returns the face device.
     *
     * @return the device descriptor
     */
    public DeviceDescriptor device() {
        return device;
    }

    /**
     * Returns the attempt number within the caller's transaction.
     *
     * @return 1-based attempt, at most the policy's {@code max_retries}
     */
    public int attempt() {
        return attempt;
    }

    /**
     * Returns how many sessions of the caller's transaction ended uncounted for low light
     * ({@link LivenessResult#isLowLightRetry()}) since its last counted failure.
     *
     * <p>A passive PAD failure in low light ends uncounted only while this number is below the engine's
     * {@code passive.low_light_max_uncounted}; with {@link #LOW_LIGHT_NOT_TRACKED} it always counts, as any PAD
     * failure, so a caller that does not track these results never gives unlimited attempts.
     *
     * @return 0 or more, or {@link #LOW_LIGHT_NOT_TRACKED}
     */
    public int lowLightRetries() {
        return lowLightRetries;
    }

    /**
     * Tells whether an earlier session of this transaction failed for a suspected attack, which forbids a supervisor
     * override after the last attempt.
     *
     * @return {@code true} after a PAD, integrity or binding failure
     */
    public boolean priorSuspectedAttack() {
        return priorSuspectedAttack;
    }

    /**
     * Returns the clock the session uses for every timeout.
     *
     * @return the clock
     */
    public Clock clock() {
        return clock;
    }

    /**
     * Returns the randomness for challenge draws, delays and nonces.
     *
     * @return the random source
     */
    public RandomSource random() {
        return random;
    }

    /**
     * Returns the diagnostic sink; used only when the policy enables diagnostics.
     *
     * @return the diagnostic sink
     */
    public DiagnosticSink diagnosticSink() {
        return diagnosticSink;
    }

    @Override
    public String toString() {
        return "SessionRequest[" + workflow().id() + ", attempt=" + attempt + "/" + policy.maxRetries() + ", device="
                + device.serialNumber() + "]";
    }

    /** Builder for {@link SessionRequest}. */
    public static final class Builder {

        private final LivenessPolicy policy;
        private final String subjectRef;
        private final DeviceDescriptor device;
        private int attempt = 1;
        private int lowLightRetries = LOW_LIGHT_NOT_TRACKED;
        private boolean priorSuspectedAttack;
        private Clock clock = Clock.system();
        private RandomSource random;
        private DiagnosticSink diagnosticSink = DiagnosticSink.noop();

        private Builder(LivenessPolicy policy, String subjectRef, DeviceDescriptor device) {
            this.policy = Objects.requireNonNull(policy, "policy");
            this.subjectRef = Objects.requireNonNull(subjectRef, "subjectRef");
            this.device = Objects.requireNonNull(device, "device");
            if (subjectRef.trim().isEmpty()) {
                throw new IllegalArgumentException("subjectRef must not be empty");
            }
        }

        /**
         * Sets the attempt number; default 1.
         *
         * @param attempt 1-based attempt, at most the policy's {@code max_retries}
         * @return this builder
         */
        public Builder attempt(int attempt) {
            this.attempt = attempt;
            return this;
        }

        /**
         * Sets the number of results of this transaction that ended uncounted for low light since its last counted
         * failure ({@code LivenessTransaction.lowLightRetries()} in the engine package). Default
         * {@link #LOW_LIGHT_NOT_TRACKED}: every PAD failure counts.
         *
         * @param lowLightRetries 0 or more
         * @return this builder
         * @throws IllegalArgumentException if the value is negative
         */
        public Builder lowLightRetries(int lowLightRetries) {
            if (lowLightRetries < 0) {
                throw new IllegalArgumentException("lowLightRetries must not be negative");
            }
            this.lowLightRetries = lowLightRetries;
            return this;
        }

        /**
         * Sets whether an earlier session of this transaction failed for a suspected attack.
         *
         * @param priorSuspectedAttack {@code LivenessTransaction.isSuspectedAttack()} before this session
         * @return this builder
         */
        public Builder priorSuspectedAttack(boolean priorSuspectedAttack) {
            this.priorSuspectedAttack = priorSuspectedAttack;
            return this;
        }

        /**
         * Sets the clock; production code keeps the system clock.
         *
         * @param clock clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets the randomness; production code keeps the default {@link RandomSource#secure()}. Only test code
         * passes a seeded source.
         *
         * @param random random source
         * @return this builder
         */
        public Builder random(RandomSource random) {
            this.random = Objects.requireNonNull(random, "random");
            return this;
        }

        /**
         * Sets the diagnostic sink; default discards records.
         *
         * @param diagnosticSink diagnostic sink
         * @return this builder
         */
        public Builder diagnosticSink(DiagnosticSink diagnosticSink) {
            this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
            return this;
        }

        /**
         * Validates the fields and creates the request.
         *
         * @return the request
         * @throws IllegalArgumentException if the attempt is outside 1 to {@code max_retries}
         */
        public SessionRequest build() {
            if (attempt < 1 || attempt > policy.maxRetries()) {
                throw new IllegalArgumentException("attempt must be between 1 and max_retries (" + policy.maxRetries() + ")");
            }
            if (random == null) {
                random = RandomSource.secure();
            }
            return new SessionRequest(this);
        }
    }
}
