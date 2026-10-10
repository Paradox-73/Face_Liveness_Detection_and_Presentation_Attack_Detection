/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.policy;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.NoStreamPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.config.PolicyKey;
import io.mosip.liveness.core.config.PolicyMode;
import io.mosip.liveness.core.device.CertificationLevel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the configuration keys of docs/design/configuration.md and resolves the {@link LivenessPolicy} of a workflow.
 *
 * <p>Rules:
 * <ul>
 *   <li>A missing key uses its default; a per-workflow key ({@code <workflow>.passive.threshold},
 *       {@code .min_challenges}, {@code .max_retries}, {@code .on_max_failure}) wins over the global key, which wins
 *       over the workflow default (95 for the authentication passive thresholds, 80 otherwise).</li>
 *   <li>{@code enable=N} resolves every workflow to {@link PolicyMode#OFF}.</li>
 *   <li>A number outside its floor or range is clamped to the nearest allowed value ({@code passive.threshold}
 *       floor 50, {@code pad.reject_threshold} floor 5, and the ranges of the key table) and reported as
 *       {@link ConfigIssue.Kind#CLAMPED}, which the client audits as {@code REG-EVT-LV15}.</li>
 *   <li>A value that cannot be parsed uses the default and is reported as {@link ConfigIssue.Kind#INVALID}; an
 *       {@code on_max_failure} value not allowed for the workflow uses {@code BLOCK} and is reported as
 *       {@link ConfigIssue.Kind#NOT_ALLOWED}.</li>
 *   <li>The reject threshold never exceeds the passive threshold. Reads are null-safe: a source returning
 *       {@code null} or throwing counts as a missing key.</li>
 * </ul>
 * Resolve at the start of every session, so a config sync applies from the next session.
 */
public final class PolicyResolver {

    private final ConfigSource config;

    /**
     * Creates a resolver.
     *
     * @param config the client's configuration source
     */
    public PolicyResolver(ConfigSource config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Resolves the policy of a workflow.
     *
     * @param workflow the workflow
     * @return the policy and the issues found
     */
    public PolicyResolution resolve(Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        Reader r = new Reader();
        LivenessPolicy.Builder b = LivenessPolicy.builder(workflow);
        boolean enabled = r.yesNo(PolicyKey.ENABLE);
        PolicyMode mode = r.policyMode(LivenessConfigKeys.policyKey(workflow),
                LivenessConfigKeys.defaultPolicyMode(workflow));
        b.mode(enabled ? mode : PolicyMode.OFF);
        resolveThresholds(r, workflow, b);
        resolveActive(r, workflow, b);
        resolveOperation(r, workflow, b);
        return new PolicyResolution(b.build(), r.issues);
    }

    private static void resolveThresholds(Reader r, Workflow w, LivenessPolicy.Builder b) {
        int passive = (int) r.number(r.workflowFirst(w, LivenessConfigKeys.WORKFLOW_PASSIVE_THRESHOLD_SUFFIX,
                PolicyKey.PASSIVE_THRESHOLD), LivenessConfigKeys.defaultPassiveThreshold(w), PolicyKey.PASSIVE_THRESHOLD);
        int reject = (int) r.number(PolicyKey.PAD_REJECT_THRESHOLD);
        if (reject > passive) {
            r.issue(PolicyKey.PAD_REJECT_THRESHOLD.key(), ConfigIssue.Kind.CLAMPED, Integer.toString(reject),
                    Integer.toString(passive));
            reject = passive;
        }
        b.passiveThreshold(passive).padRejectThreshold(reject);
        b.passiveMinFrames((int) r.number(PolicyKey.PASSIVE_MIN_FRAMES));
        b.passiveTimeoutMs(r.number(PolicyKey.PASSIVE_TIMEOUT_MS));
        b.bindingMinSimilarity((int) r.number(PolicyKey.BINDING_MIN_SIMILARITY));
        b.bindingMaxGapMs(r.number(PolicyKey.BINDING_MAX_GAP_MS));
    }

    private static void resolveActive(Reader r, Workflow w, LivenessPolicy.Builder b) {
        b.activeEnabled(r.yesNo(PolicyKey.ACTIVE_ENABLE));
        b.minChallenges((int) r.number(r.workflowFirst(w, LivenessConfigKeys.WORKFLOW_MIN_CHALLENGES_SUFFIX,
                PolicyKey.ACTIVE_MIN_CHALLENGES), PolicyKey.ACTIVE_MIN_CHALLENGES.defaultLong(),
                PolicyKey.ACTIVE_MIN_CHALLENGES));
        b.challengeTypes(r.challengeTypes(PolicyKey.ACTIVE_CHALLENGE_TYPES));
        b.challengeTimeoutMs(r.number(PolicyKey.ACTIVE_CHALLENGE_TIMEOUT_MS));
        b.minOnsetMs(r.number(PolicyKey.ACTIVE_MIN_ONSET_MS));
        b.holdMs(r.number(PolicyKey.ACTIVE_HOLD_MS));
    }

    private static void resolveOperation(Reader r, Workflow w, LivenessPolicy.Builder b) {
        b.maxRetries((int) r.number(r.workflowFirst(w, LivenessConfigKeys.WORKFLOW_MAX_RETRIES_SUFFIX,
                PolicyKey.MAX_RETRIES), PolicyKey.MAX_RETRIES.defaultLong(), PolicyKey.MAX_RETRIES));
        b.onMaxFailure(r.onMaxFailure(r.workflowFirst(w, LivenessConfigKeys.WORKFLOW_ON_MAX_FAILURE_SUFFIX,
                PolicyKey.ON_MAX_FAILURE), w));
        b.streamTimeoutMs(r.number(PolicyKey.STREAM_TIMEOUT_MS));
        b.frameSampleIntervalMs(r.number(PolicyKey.FRAME_SAMPLE_INTERVAL_MS));
        b.deviceReconnectAttempts((int) r.number(PolicyKey.DEVICE_RECONNECT_ATTEMPTS));
        b.qualityMinIedPx((int) r.number(PolicyKey.QUALITY_MIN_IED_PX));
        b.qualityMaxYawDeg((int) r.number(PolicyKey.QUALITY_MAX_YAW_DEG));
        b.integrityMaxFrozenFrames((int) r.number(PolicyKey.INTEGRITY_MAX_FROZEN_FRAMES));
        b.integrityMinFps((int) r.number(PolicyKey.INTEGRITY_MIN_FPS));
        b.noStreamPolicy(r.noStreamPolicy(PolicyKey.NO_STREAM_POLICY));
        b.modelMinVersion(r.version(PolicyKey.MODEL_MIN_VERSION));
        b.diagnosticEnabled(r.yesNo(PolicyKey.DIAGNOSTIC_ENABLE));
        b.metricsEnabled(r.yesNo(PolicyKey.METRICS_ENABLE));
        b.allowedCertifications(r.certifications(PolicyKey.MDS_ALLOWED_CERTIFICATIONS));
    }

    /** Reads and validates values, collecting the issues of one resolution. */
    private final class Reader {

        private final List<ConfigIssue> issues = new ArrayList<>();

        Optional<String> raw(String key) {
            String value;
            try {
                Optional<String> v = config.get(key);
                value = v == null || !v.isPresent() ? null : v.get();
            } catch (RuntimeException e) {
                value = null;
            }
            if (value == null || value.trim().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(value.trim());
        }

        void issue(String key, ConfigIssue.Kind kind, String raw, String applied) {
            issues.add(new ConfigIssue(key, kind, raw, applied));
        }

        /** Returns the per-workflow key when it is set, else the global key. */
        String workflowFirst(Workflow w, String suffix, PolicyKey global) {
            String key = LivenessConfigKeys.workflowKey(w, suffix);
            return raw(key).isPresent() ? key : global.key();
        }

        long number(PolicyKey k) {
            return number(k.key(), k.defaultLong(), k);
        }

        /** Reads {@code key} (the global key or its per-workflow form) within the range of {@code range}. */
        long number(String key, long def, PolicyKey range) {
            Optional<String> v = raw(key);
            if (!v.isPresent()) {
                return def;
            }
            long parsed;
            try {
                parsed = Long.parseLong(v.get());
            } catch (NumberFormatException e) {
                issue(key, ConfigIssue.Kind.INVALID, v.get(), Long.toString(def));
                return def;
            }
            long clamped = range.clamp(parsed);
            if (clamped != parsed) {
                issue(key, ConfigIssue.Kind.CLAMPED, v.get(), Long.toString(clamped));
            }
            return clamped;
        }

        boolean yesNo(PolicyKey k) {
            boolean def = k.defaultOn();
            Optional<String> v = raw(k.key());
            if (!v.isPresent()) {
                return def;
            }
            Optional<Boolean> parsed = PolicyKey.parseYesNo(v.get());
            if (!parsed.isPresent()) {
                issue(k.key(), ConfigIssue.Kind.INVALID, v.get(), k.defaultValue());
                return def;
            }
            return parsed.get();
        }

        PolicyMode policyMode(String key, PolicyMode def) {
            Optional<String> v = raw(key);
            if (!v.isPresent()) {
                return def;
            }
            Optional<PolicyMode> m = PolicyMode.fromName(v.get());
            if (!m.isPresent()) {
                issue(key, ConfigIssue.Kind.INVALID, v.get(), def.name());
                return def;
            }
            return m.get();
        }

        NoStreamPolicy noStreamPolicy(PolicyKey k) {
            String key = k.key();
            NoStreamPolicy def = NoStreamPolicy.valueOf(k.defaultValue());
            Optional<String> v = raw(key);
            if (!v.isPresent()) {
                return def;
            }
            Optional<NoStreamPolicy> p = NoStreamPolicy.fromName(v.get());
            if (!p.isPresent()) {
                issue(key, ConfigIssue.Kind.INVALID, v.get(), def.name());
                return def;
            }
            return p.get();
        }

        OnMaxFailure onMaxFailure(String key, Workflow w) {
            OnMaxFailure def = LivenessConfigKeys.defaultOnMaxFailure(w);
            Optional<String> v = raw(key);
            if (!v.isPresent()) {
                return def;
            }
            Optional<OnMaxFailure> m = OnMaxFailure.fromName(v.get());
            if (!m.isPresent()) {
                issue(key, ConfigIssue.Kind.INVALID, v.get(), def.name());
                return def;
            }
            if (!m.get().isAllowedFor(w)) {
                issue(key, ConfigIssue.Kind.NOT_ALLOWED, v.get(), def.name());
                return def;
            }
            return m.get();
        }

        Set<CertificationLevel> certifications(PolicyKey k) {
            String key = k.key();
            Set<CertificationLevel> def = EnumSet.allOf(CertificationLevel.class);
            Optional<String> v = raw(key);
            if (!v.isPresent()) {
                return def;
            }
            Set<CertificationLevel> levels = EnumSet.noneOf(CertificationLevel.class);
            boolean unknown = false;
            for (String part : v.get().split(",")) {
                Optional<CertificationLevel> l = CertificationLevel.fromCode(part);
                if (l.isPresent()) {
                    levels.add(l.get());
                } else {
                    unknown = unknown || !part.trim().isEmpty();
                }
            }
            if (levels.isEmpty() || unknown) {
                issue(key, ConfigIssue.Kind.INVALID, v.get(),
                        levels.isEmpty() ? k.defaultValue() : levels.toString());
            }
            return levels.isEmpty() ? def : levels;
        }

        Set<ChallengeType> challengeTypes(PolicyKey k) {
            String key = k.key();
            Set<ChallengeType> def = ChallengeType.parseList(k.defaultValue());
            Optional<String> v = raw(key);
            if (!v.isPresent()) {
                return def;
            }
            Set<ChallengeType> types = EnumSet.noneOf(ChallengeType.class);
            boolean unknown = false;
            for (String part : v.get().split(",")) {
                Optional<ChallengeType> t = ChallengeType.fromName(part);
                if (t.isPresent()) {
                    types.add(t.get());
                } else if (!part.trim().isEmpty()) {
                    unknown = true;
                }
            }
            if (types.isEmpty()) {
                issue(key, ConfigIssue.Kind.INVALID, v.get(), k.defaultValue());
                return def;
            }
            if (unknown) {
                issue(key, ConfigIssue.Kind.INVALID, v.get(), join(types));
            }
            return types;
        }

        String version(PolicyKey k) {
            String key = k.key();
            String def = k.defaultValue();
            Optional<String> v = raw(key);
            if (!v.isPresent()) {
                return def;
            }
            // ModelRegistry parses MAJOR.MINOR.PATCH only; "1.2" would stop the engine with CONFIG_INVALID.
            if (!v.get().matches("\\d+\\.\\d+\\.\\d+")) {
                issue(key, ConfigIssue.Kind.INVALID, v.get(), def);
                return def;
            }
            return v.get();
        }
    }

    private static String join(Set<ChallengeType> types) {
        StringBuilder sb = new StringBuilder();
        for (ChallengeType t : types) {
            sb.append(sb.length() > 0 ? "," : "").append(t.name());
        }
        return sb.toString();
    }
}
