/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.device.CertificationLevel;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The liveness settings in force for one workflow, after defaults, per-workflow overrides and floors have been
 * applied (typed view of the keys in {@link LivenessConfigKeys}).
 *
 * <p>A policy is resolved from a {@link ConfigSource} at the start of every session, so a config sync takes
 * effect on the next session. The master switch {@code enable=N} resolves to {@link PolicyMode#OFF}. Units are
 * milliseconds for durations, 0-100 for scores and similarity, pixels for sizes and degrees for angles.
 *
 * <p>{@link Builder#build()} rejects a number outside the range of its key in {@link PolicyKey}; the policy resolver
 * clamps a configured value to the same range (for example the 50-point passive floor) and audits the clamp.
 */
public final class LivenessPolicy {

    private final Workflow workflow;
    private final PolicyMode mode;
    private final int passiveThreshold;
    private final int padRejectThreshold;
    private final int passiveMinFrames;
    private final long passiveTimeoutMs;
    private final boolean activeEnabled;
    private final int minChallenges;
    private final Set<ChallengeType> challengeTypes;
    private final long challengeTimeoutMs;
    private final long minOnsetMs;
    private final long holdMs;
    private final int maxRetries;
    private final OnMaxFailure onMaxFailure;
    private final long streamTimeoutMs;
    private final long frameSampleIntervalMs;
    private final int deviceReconnectAttempts;
    private final int qualityMinIedPx;
    private final int qualityMaxYawDeg;
    private final int integrityMaxFrozenFrames;
    private final int integrityMinFps;
    private final int bindingMinSimilarity;
    private final long bindingMaxGapMs;
    private final NoStreamPolicy noStreamPolicy;
    private final String modelMinVersion;
    private final boolean diagnosticEnabled;
    private final boolean metricsEnabled;
    private final Set<CertificationLevel> allowedCertifications;

    private LivenessPolicy(Builder b) {
        this.workflow = b.workflow;
        this.mode = b.mode;
        this.passiveThreshold = b.passiveThreshold;
        this.padRejectThreshold = b.padRejectThreshold;
        this.passiveMinFrames = b.passiveMinFrames;
        this.passiveTimeoutMs = b.passiveTimeoutMs;
        this.activeEnabled = b.activeEnabled;
        this.minChallenges = b.minChallenges;
        this.challengeTypes = Collections.unmodifiableSet(EnumSet.copyOf(b.challengeTypes));
        this.challengeTimeoutMs = b.challengeTimeoutMs;
        this.minOnsetMs = b.minOnsetMs;
        this.holdMs = b.holdMs;
        this.maxRetries = b.maxRetries;
        this.onMaxFailure = b.onMaxFailure;
        this.streamTimeoutMs = b.streamTimeoutMs;
        this.frameSampleIntervalMs = b.frameSampleIntervalMs;
        this.deviceReconnectAttempts = b.deviceReconnectAttempts;
        this.qualityMinIedPx = b.qualityMinIedPx;
        this.qualityMaxYawDeg = b.qualityMaxYawDeg;
        this.integrityMaxFrozenFrames = b.integrityMaxFrozenFrames;
        this.integrityMinFps = b.integrityMinFps;
        this.bindingMinSimilarity = b.bindingMinSimilarity;
        this.bindingMaxGapMs = b.bindingMaxGapMs;
        this.noStreamPolicy = b.noStreamPolicy;
        this.modelMinVersion = b.modelMinVersion;
        this.diagnosticEnabled = b.diagnosticEnabled;
        this.metricsEnabled = b.metricsEnabled;
        this.allowedCertifications = Collections.unmodifiableSet(EnumSet.copyOf(b.allowedCertifications));
    }

    /**
     * Returns the shipped default policy of a workflow.
     *
     * @param workflow the workflow
     * @return the default policy
     */
    public static LivenessPolicy defaults(Workflow workflow) {
        return builder(workflow).build();
    }

    /**
     * Starts a builder preset with the shipped defaults of a workflow.
     *
     * @param workflow the workflow
     * @return a builder
     */
    public static Builder builder(Workflow workflow) {
        return new Builder(workflow);
    }

    /**
     * Starts a builder preset with this policy's values.
     *
     * @return a builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns the workflow this policy applies to.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return workflow;
    }

    /**
     * Returns how liveness is enforced ({@code policy.<workflow>}).
     *
     * @return the mode
     */
    public PolicyMode mode() {
        return mode;
    }

    /**
     * Tells whether any liveness check runs.
     *
     * @return {@code false} when the mode is {@link PolicyMode#OFF}
     */
    public boolean isEnabled() {
        return mode != PolicyMode.OFF;
    }

    /**
     * Returns the passive score at or above which a face passes without a challenge ({@code passive.threshold}).
     *
     * @return score 0-100
     */
    public int passiveThreshold() {
        return passiveThreshold;
    }

    /**
     * Returns the passive score below which the face fails as a presentation attack ({@code pad.reject_threshold}).
     *
     * @return score 0-100, at most {@link #passiveThreshold()}
     */
    public int padRejectThreshold() {
        return padRejectThreshold;
    }

    /**
     * Returns the number of good frames for the passive decision ({@code passive.min_frames}).
     *
     * @return frame count
     */
    public int passiveMinFrames() {
        return passiveMinFrames;
    }

    /**
     * Returns the time limit for acquisition plus the passive phase ({@code passive.timeout_ms}).
     *
     * @return milliseconds
     */
    public long passiveTimeoutMs() {
        return passiveTimeoutMs;
    }

    /**
     * Tells whether escalation to challenges is allowed ({@code active.enable}).
     *
     * @return {@code true} if active checks may run
     */
    public boolean isActiveEnabled() {
        return activeEnabled;
    }

    /**
     * Returns the number of challenges in the active phase ({@code min_challenges}).
     *
     * @return challenge count, 1 or more
     */
    public int minChallenges() {
        return minChallenges;
    }

    /**
     * Returns the challenge pool ({@code active.challenge_types}) before filtering by stream frame rate, face
     * size and analyser features.
     *
     * @return an unmodifiable, non-empty set
     */
    public Set<ChallengeType> challengeTypes() {
        return challengeTypes;
    }

    /**
     * Returns the time allowed per challenge ({@code active.challenge_timeout_ms}).
     *
     * @return milliseconds
     */
    public long challengeTimeoutMs() {
        return challengeTimeoutMs;
    }

    /**
     * Returns the shortest accepted reaction time after a prompt ({@code active.min_onset_ms}).
     *
     * @return milliseconds
     */
    public long minOnsetMs() {
        return minOnsetMs;
    }

    /**
     * Returns how long an action must be held ({@code active.hold_ms}).
     *
     * @return milliseconds
     */
    public long holdMs() {
        return holdMs;
    }

    /**
     * Returns the number of attempts per transaction ({@code max_retries}).
     *
     * @return attempt count, 1 or more
     */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Returns the behaviour after the last failed attempt ({@code on_max_failure}).
     *
     * @return the behaviour; always allowed for {@link #workflow()}
     */
    public OnMaxFailure onMaxFailure() {
        return onMaxFailure;
    }

    /**
     * Returns the STREAM timeout ({@code stream.timeout_ms}).
     *
     * @return milliseconds
     */
    public long streamTimeoutMs() {
        return streamTimeoutMs;
    }

    /**
     * Returns the frame sampling interval ({@code frame.sample_interval_ms}).
     *
     * @return milliseconds; 0 means every frame
     */
    public long frameSampleIntervalMs() {
        return frameSampleIntervalMs;
    }

    /**
     * Returns the number of automatic reconnects ({@code device.reconnect_attempts}).
     *
     * @return reconnect count, 0 or more
     */
    public int deviceReconnectAttempts() {
        return deviceReconnectAttempts;
    }

    /**
     * Returns the minimum inter-eye distance during acquisition ({@code quality.min_ied_px}).
     *
     * @return pixels
     */
    public int qualityMinIedPx() {
        return qualityMinIedPx;
    }

    /**
     * Returns the largest head turn accepted during acquisition ({@code quality.max_yaw_deg}).
     *
     * @return degrees
     */
    public int qualityMaxYawDeg() {
        return qualityMaxYawDeg;
    }

    /**
     * Returns the number of identical frames allowed in a row ({@code integrity.max_frozen_frames}).
     *
     * @return frame count
     */
    public int integrityMaxFrozenFrames() {
        return integrityMaxFrozenFrames;
    }

    /**
     * Returns the minimum stream frame rate ({@code integrity.min_fps}).
     *
     * @return frames per second
     */
    public int integrityMinFps() {
        return integrityMinFps;
    }

    /**
     * Returns the minimum capture-to-session face similarity ({@code binding.min_similarity}).
     *
     * @return similarity 0-100
     */
    public int bindingMinSimilarity() {
        return bindingMinSimilarity;
    }

    /**
     * Returns the time limit from pass to capture ({@code binding.max_gap_ms}).
     *
     * @return milliseconds
     */
    public long bindingMaxGapMs() {
        return bindingMaxGapMs;
    }

    /**
     * Returns the behaviour for devices that cannot stream ({@code no_stream_policy}).
     *
     * @return the policy
     */
    public NoStreamPolicy noStreamPolicy() {
        return noStreamPolicy;
    }

    /**
     * Returns the minimum model bundle version ({@code model.min_version}).
     *
     * @return a semantic version such as {@code "1.0.0"}
     */
    public String modelMinVersion() {
        return modelMinVersion;
    }

    /**
     * Tells whether the diagnostic log and overlay are on ({@code diagnostic.enable}).
     *
     * @return {@code true} if on
     */
    public boolean isDiagnosticEnabled() {
        return diagnosticEnabled;
    }

    /**
     * Tells whether anonymised metrics are collected ({@code metrics.enable}).
     *
     * @return {@code true} if on
     */
    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    /**
     * Returns the face device certification levels accepted ({@code mosip.registration.mds.allowed_certifications}).
     *
     * @return non-empty set; default L0 and L1
     */
    public Set<CertificationLevel> allowedCertifications() {
        return allowedCertifications;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LivenessPolicy)) {
            return false;
        }
        LivenessPolicy p = (LivenessPolicy) o;
        return workflow == p.workflow && mode == p.mode && passiveThreshold == p.passiveThreshold
                && padRejectThreshold == p.padRejectThreshold && passiveMinFrames == p.passiveMinFrames
                && passiveTimeoutMs == p.passiveTimeoutMs && activeEnabled == p.activeEnabled
                && minChallenges == p.minChallenges && challengeTypes.equals(p.challengeTypes)
                && challengeTimeoutMs == p.challengeTimeoutMs && minOnsetMs == p.minOnsetMs && holdMs == p.holdMs
                && maxRetries == p.maxRetries && onMaxFailure == p.onMaxFailure && streamTimeoutMs == p.streamTimeoutMs
                && frameSampleIntervalMs == p.frameSampleIntervalMs
                && deviceReconnectAttempts == p.deviceReconnectAttempts && qualityMinIedPx == p.qualityMinIedPx
                && qualityMaxYawDeg == p.qualityMaxYawDeg && integrityMaxFrozenFrames == p.integrityMaxFrozenFrames
                && integrityMinFps == p.integrityMinFps && bindingMinSimilarity == p.bindingMinSimilarity
                && bindingMaxGapMs == p.bindingMaxGapMs && noStreamPolicy == p.noStreamPolicy
                && modelMinVersion.equals(p.modelMinVersion) && diagnosticEnabled == p.diagnosticEnabled
                && metricsEnabled == p.metricsEnabled && allowedCertifications.equals(p.allowedCertifications);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workflow, mode, passiveThreshold, padRejectThreshold, passiveMinFrames, passiveTimeoutMs,
                activeEnabled, minChallenges, challengeTypes, challengeTimeoutMs, minOnsetMs, holdMs, maxRetries,
                onMaxFailure, streamTimeoutMs, frameSampleIntervalMs, deviceReconnectAttempts, qualityMinIedPx,
                qualityMaxYawDeg, integrityMaxFrozenFrames, integrityMinFps, bindingMinSimilarity, bindingMaxGapMs,
                noStreamPolicy, modelMinVersion, diagnosticEnabled, metricsEnabled, allowedCertifications);
    }

    @Override
    public String toString() {
        return "LivenessPolicy[" + workflow.id() + ", " + mode + ", passive=" + passiveThreshold + ", reject="
                + padRejectThreshold + ", frames=" + passiveMinFrames + ", active=" + activeEnabled + ", challenges="
                + minChallenges + " of " + challengeTypes + ", retries=" + maxRetries + ", onMax=" + onMaxFailure + "]";
    }

    /** Builder for {@link LivenessPolicy}, preset with the shipped defaults of one workflow. */
    public static final class Builder {

        private final Workflow workflow;
        private PolicyMode mode;
        private int passiveThreshold;
        private int padRejectThreshold = PolicyKey.PAD_REJECT_THRESHOLD.defaultInt();
        private int passiveMinFrames = PolicyKey.PASSIVE_MIN_FRAMES.defaultInt();
        private long passiveTimeoutMs = PolicyKey.PASSIVE_TIMEOUT_MS.defaultLong();
        private boolean activeEnabled = PolicyKey.ACTIVE_ENABLE.defaultOn();
        private int minChallenges = PolicyKey.ACTIVE_MIN_CHALLENGES.defaultInt();
        private Set<ChallengeType> challengeTypes = ChallengeType.parseList(
                PolicyKey.ACTIVE_CHALLENGE_TYPES.defaultValue());
        private long challengeTimeoutMs = PolicyKey.ACTIVE_CHALLENGE_TIMEOUT_MS.defaultLong();
        private long minOnsetMs = PolicyKey.ACTIVE_MIN_ONSET_MS.defaultLong();
        private long holdMs = PolicyKey.ACTIVE_HOLD_MS.defaultLong();
        private int maxRetries = PolicyKey.MAX_RETRIES.defaultInt();
        private OnMaxFailure onMaxFailure;
        private long streamTimeoutMs = PolicyKey.STREAM_TIMEOUT_MS.defaultLong();
        private long frameSampleIntervalMs = PolicyKey.FRAME_SAMPLE_INTERVAL_MS.defaultLong();
        private int deviceReconnectAttempts = PolicyKey.DEVICE_RECONNECT_ATTEMPTS.defaultInt();
        private int qualityMinIedPx = PolicyKey.QUALITY_MIN_IED_PX.defaultInt();
        private int qualityMaxYawDeg = PolicyKey.QUALITY_MAX_YAW_DEG.defaultInt();
        private int integrityMaxFrozenFrames = PolicyKey.INTEGRITY_MAX_FROZEN_FRAMES.defaultInt();
        private int integrityMinFps = PolicyKey.INTEGRITY_MIN_FPS.defaultInt();
        private int bindingMinSimilarity = PolicyKey.BINDING_MIN_SIMILARITY.defaultInt();
        private long bindingMaxGapMs = PolicyKey.BINDING_MAX_GAP_MS.defaultLong();
        private NoStreamPolicy noStreamPolicy = NoStreamPolicy.valueOf(PolicyKey.NO_STREAM_POLICY.defaultValue());
        private String modelMinVersion = PolicyKey.MODEL_MIN_VERSION.defaultValue();
        private boolean diagnosticEnabled = PolicyKey.DIAGNOSTIC_ENABLE.defaultOn();
        private boolean metricsEnabled = PolicyKey.METRICS_ENABLE.defaultOn();
        private Set<CertificationLevel> allowedCertifications = EnumSet.allOf(CertificationLevel.class);

        private Builder(Workflow workflow) {
            this.workflow = Objects.requireNonNull(workflow, "workflow");
            this.mode = LivenessConfigKeys.defaultPolicyMode(workflow);
            this.passiveThreshold = LivenessConfigKeys.defaultPassiveThreshold(workflow);
            this.onMaxFailure = LivenessConfigKeys.defaultOnMaxFailure(workflow);
        }

        private Builder(LivenessPolicy p) {
            this(p.workflow);
            copyDetection(p);
            copyOperation(p);
        }

        private void copyDetection(LivenessPolicy p) {
            mode = p.mode;
            passiveThreshold = p.passiveThreshold;
            padRejectThreshold = p.padRejectThreshold;
            passiveMinFrames = p.passiveMinFrames;
            passiveTimeoutMs = p.passiveTimeoutMs;
            activeEnabled = p.activeEnabled;
            minChallenges = p.minChallenges;
            challengeTypes = EnumSet.copyOf(p.challengeTypes);
            challengeTimeoutMs = p.challengeTimeoutMs;
            minOnsetMs = p.minOnsetMs;
            holdMs = p.holdMs;
            qualityMinIedPx = p.qualityMinIedPx;
            qualityMaxYawDeg = p.qualityMaxYawDeg;
            integrityMaxFrozenFrames = p.integrityMaxFrozenFrames;
            integrityMinFps = p.integrityMinFps;
            bindingMinSimilarity = p.bindingMinSimilarity;
            bindingMaxGapMs = p.bindingMaxGapMs;
        }

        private void copyOperation(LivenessPolicy p) {
            maxRetries = p.maxRetries;
            onMaxFailure = p.onMaxFailure;
            streamTimeoutMs = p.streamTimeoutMs;
            frameSampleIntervalMs = p.frameSampleIntervalMs;
            deviceReconnectAttempts = p.deviceReconnectAttempts;
            noStreamPolicy = p.noStreamPolicy;
            modelMinVersion = p.modelMinVersion;
            diagnosticEnabled = p.diagnosticEnabled;
            metricsEnabled = p.metricsEnabled;
            allowedCertifications = EnumSet.copyOf(p.allowedCertifications);
        }

        /**
         * Sets the mode.
         *
         * @param mode how liveness is enforced
         * @return this builder
         */
        public Builder mode(PolicyMode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Sets the passive threshold.
         *
         * @param passiveThreshold score 0-100
         * @return this builder
         */
        public Builder passiveThreshold(int passiveThreshold) {
            this.passiveThreshold = passiveThreshold;
            return this;
        }

        /**
         * Sets the PAD reject threshold.
         *
         * @param padRejectThreshold score 0-100, at most the passive threshold
         * @return this builder
         */
        public Builder padRejectThreshold(int padRejectThreshold) {
            this.padRejectThreshold = padRejectThreshold;
            return this;
        }

        /**
         * Sets the number of good frames for the passive decision.
         *
         * @param passiveMinFrames 1 or more
         * @return this builder
         */
        public Builder passiveMinFrames(int passiveMinFrames) {
            this.passiveMinFrames = passiveMinFrames;
            return this;
        }

        /**
         * Sets the acquisition plus passive time limit.
         *
         * @param passiveTimeoutMs milliseconds; positive
         * @return this builder
         */
        public Builder passiveTimeoutMs(long passiveTimeoutMs) {
            this.passiveTimeoutMs = passiveTimeoutMs;
            return this;
        }

        /**
         * Allows or forbids escalation to challenges.
         *
         * @param activeEnabled whether active checks may run
         * @return this builder
         */
        public Builder activeEnabled(boolean activeEnabled) {
            this.activeEnabled = activeEnabled;
            return this;
        }

        /**
         * Sets the number of challenges.
         *
         * @param minChallenges 1 or more
         * @return this builder
         */
        public Builder minChallenges(int minChallenges) {
            this.minChallenges = minChallenges;
            return this;
        }

        /**
         * Sets the challenge pool.
         *
         * @param challengeTypes non-empty set; copied
         * @return this builder
         */
        public Builder challengeTypes(Set<ChallengeType> challengeTypes) {
            this.challengeTypes = challengeTypes == null || challengeTypes.isEmpty()
                    ? EnumSet.noneOf(ChallengeType.class) : EnumSet.copyOf(challengeTypes);
            return this;
        }

        /**
         * Sets the time allowed per challenge.
         *
         * @param challengeTimeoutMs milliseconds; positive
         * @return this builder
         */
        public Builder challengeTimeoutMs(long challengeTimeoutMs) {
            this.challengeTimeoutMs = challengeTimeoutMs;
            return this;
        }

        /**
         * Sets the shortest accepted reaction time.
         *
         * @param minOnsetMs milliseconds; 0 or more
         * @return this builder
         */
        public Builder minOnsetMs(long minOnsetMs) {
            this.minOnsetMs = minOnsetMs;
            return this;
        }

        /**
         * Sets how long an action must be held.
         *
         * @param holdMs milliseconds; 0 or more
         * @return this builder
         */
        public Builder holdMs(long holdMs) {
            this.holdMs = holdMs;
            return this;
        }

        /**
         * Sets the number of attempts per transaction.
         *
         * @param maxRetries 1 or more
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the behaviour after the last failed attempt.
         *
         * @param onMaxFailure a behaviour allowed for the workflow
         * @return this builder
         */
        public Builder onMaxFailure(OnMaxFailure onMaxFailure) {
            this.onMaxFailure = onMaxFailure;
            return this;
        }

        /**
         * Sets the STREAM timeout.
         *
         * @param streamTimeoutMs milliseconds; positive
         * @return this builder
         */
        public Builder streamTimeoutMs(long streamTimeoutMs) {
            this.streamTimeoutMs = streamTimeoutMs;
            return this;
        }

        /**
         * Sets the frame sampling interval.
         *
         * @param frameSampleIntervalMs milliseconds; 0 means every frame
         * @return this builder
         */
        public Builder frameSampleIntervalMs(long frameSampleIntervalMs) {
            this.frameSampleIntervalMs = frameSampleIntervalMs;
            return this;
        }

        /**
         * Sets the number of automatic reconnects.
         *
         * @param deviceReconnectAttempts 0 or more
         * @return this builder
         */
        public Builder deviceReconnectAttempts(int deviceReconnectAttempts) {
            this.deviceReconnectAttempts = deviceReconnectAttempts;
            return this;
        }

        /**
         * Sets the minimum inter-eye distance during acquisition.
         *
         * @param qualityMinIedPx pixels; 0 or more
         * @return this builder
         */
        public Builder qualityMinIedPx(int qualityMinIedPx) {
            this.qualityMinIedPx = qualityMinIedPx;
            return this;
        }

        /**
         * Sets the largest head turn accepted during acquisition.
         *
         * @param qualityMaxYawDeg degrees, 0-90
         * @return this builder
         */
        public Builder qualityMaxYawDeg(int qualityMaxYawDeg) {
            this.qualityMaxYawDeg = qualityMaxYawDeg;
            return this;
        }

        /**
         * Sets the number of identical frames allowed in a row.
         *
         * @param integrityMaxFrozenFrames 1 or more
         * @return this builder
         */
        public Builder integrityMaxFrozenFrames(int integrityMaxFrozenFrames) {
            this.integrityMaxFrozenFrames = integrityMaxFrozenFrames;
            return this;
        }

        /**
         * Sets the minimum stream frame rate.
         *
         * @param integrityMinFps frames per second; 1 or more
         * @return this builder
         */
        public Builder integrityMinFps(int integrityMinFps) {
            this.integrityMinFps = integrityMinFps;
            return this;
        }

        /**
         * Sets the minimum capture-to-session similarity.
         *
         * @param bindingMinSimilarity similarity 0-100
         * @return this builder
         */
        public Builder bindingMinSimilarity(int bindingMinSimilarity) {
            this.bindingMinSimilarity = bindingMinSimilarity;
            return this;
        }

        /**
         * Sets the time limit from pass to capture.
         *
         * @param bindingMaxGapMs milliseconds; positive
         * @return this builder
         */
        public Builder bindingMaxGapMs(long bindingMaxGapMs) {
            this.bindingMaxGapMs = bindingMaxGapMs;
            return this;
        }

        /**
         * Sets the behaviour for devices that cannot stream.
         *
         * @param noStreamPolicy the policy
         * @return this builder
         */
        public Builder noStreamPolicy(NoStreamPolicy noStreamPolicy) {
            this.noStreamPolicy = noStreamPolicy;
            return this;
        }

        /**
         * Sets the minimum model bundle version.
         *
         * @param modelMinVersion semantic version, not empty
         * @return this builder
         */
        public Builder modelMinVersion(String modelMinVersion) {
            this.modelMinVersion = modelMinVersion;
            return this;
        }

        /**
         * Turns the diagnostic log and overlay on or off.
         *
         * @param diagnosticEnabled whether diagnostics are on
         * @return this builder
         */
        public Builder diagnosticEnabled(boolean diagnosticEnabled) {
            this.diagnosticEnabled = diagnosticEnabled;
            return this;
        }

        /**
         * Turns anonymised metrics on or off.
         *
         * @param metricsEnabled whether metrics are on
         * @return this builder
         */
        public Builder metricsEnabled(boolean metricsEnabled) {
            this.metricsEnabled = metricsEnabled;
            return this;
        }

        /**
         * Sets the accepted face device certification levels.
         *
         * @param levels non-empty set; copied; empty or {@code null} keeps every level
         * @return this builder
         */
        public Builder allowedCertifications(Set<CertificationLevel> levels) {
            this.allowedCertifications = levels == null || levels.isEmpty() ? EnumSet.allOf(CertificationLevel.class)
                    : EnumSet.copyOf(levels);
            return this;
        }

        /**
         * Validates the fields and creates the policy.
         *
         * @return the policy
         * @throws NullPointerException     if the mode, failure behaviour, no-stream policy or model version is missing
         * @throws IllegalArgumentException if a value breaks a structural rule (see the setter documentation) or the
         *                                  failure behaviour is not allowed for the workflow
         */
        public LivenessPolicy build() {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(onMaxFailure, "onMaxFailure");
            Objects.requireNonNull(noStreamPolicy, "noStreamPolicy");
            Objects.requireNonNull(modelMinVersion, "modelMinVersion");
            checkScores();
            checkCountsAndDurations();
            if (!onMaxFailure.isAllowedFor(workflow)) {
                throw new IllegalArgumentException(onMaxFailure + " is not allowed for " + workflow.id());
            }
            return new LivenessPolicy(this);
        }

        /** Every number within the range of its key ({@link PolicyKey}), the same range the resolver clamps to. */
        private void checkScores() {
            PolicyKey.PASSIVE_THRESHOLD.require(passiveThreshold);
            PolicyKey.PAD_REJECT_THRESHOLD.require(padRejectThreshold);
            require(padRejectThreshold <= passiveThreshold, "padRejectThreshold must not exceed passiveThreshold");
            PolicyKey.BINDING_MIN_SIMILARITY.require(bindingMinSimilarity);
            PolicyKey.QUALITY_MAX_YAW_DEG.require(qualityMaxYawDeg);
        }

        private void checkCountsAndDurations() {
            PolicyKey.PASSIVE_MIN_FRAMES.require(passiveMinFrames);
            PolicyKey.PASSIVE_TIMEOUT_MS.require(passiveTimeoutMs);
            PolicyKey.ACTIVE_MIN_CHALLENGES.require(minChallenges);
            require(!challengeTypes.isEmpty(), "challengeTypes must not be empty");
            PolicyKey.ACTIVE_CHALLENGE_TIMEOUT_MS.require(challengeTimeoutMs);
            PolicyKey.ACTIVE_MIN_ONSET_MS.require(minOnsetMs);
            PolicyKey.ACTIVE_HOLD_MS.require(holdMs);
            PolicyKey.MAX_RETRIES.require(maxRetries);
            PolicyKey.STREAM_TIMEOUT_MS.require(streamTimeoutMs);
            PolicyKey.FRAME_SAMPLE_INTERVAL_MS.require(frameSampleIntervalMs);
            PolicyKey.DEVICE_RECONNECT_ATTEMPTS.require(deviceReconnectAttempts);
            PolicyKey.QUALITY_MIN_IED_PX.require(qualityMinIedPx);
            PolicyKey.INTEGRITY_MAX_FROZEN_FRAMES.require(integrityMaxFrozenFrames);
            PolicyKey.INTEGRITY_MIN_FPS.require(integrityMinFps);
            PolicyKey.BINDING_MAX_GAP_MS.require(bindingMaxGapMs);
            require(!modelMinVersion.trim().isEmpty(), "modelMinVersion must not be empty");
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}
