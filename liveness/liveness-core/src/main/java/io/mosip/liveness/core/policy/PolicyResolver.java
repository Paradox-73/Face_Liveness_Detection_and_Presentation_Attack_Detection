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
import io.mosip.liveness.core.config.PolicyMode;
import io.mosip.liveness.core.device.CertificationLevel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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
        boolean enabled = r.yesNo(LivenessConfigKeys.ENABLE, true);
        PolicyMode mode = r.policyMode(LivenessConfigKeys.policyKey(workflow),
                LivenessConfigKeys.defaultPolicyMode(workflow));
        b.mode(enabled ? mode : PolicyMode.OFF);
        resolveThresholds(r, workflow, b);
        resolveActive(r, workflow, b);
        resolveOperation(r, workflow, b);
        return new PolicyResolution(b.build(), r.issues);
    }

    private static void resolveThresholds(Reader r, Workflow w, LivenessPolicy.Builder b) {
        int passive = r.integer(r.workflowFirst(w, LivenessConfigKeys.WORKFLOW_PASSIVE_THRESHOLD_SUFFIX,
                LivenessConfigKeys.PASSIVE_THRESHOLD), LivenessConfigKeys.defaultPassiveThreshold(w),
                LivenessConfigKeys.PASSIVE_THRESHOLD_FLOOR, 100);
        int reject = r.integer(LivenessConfigKeys.PAD_REJECT_THRESHOLD, LivenessConfigKeys.DEFAULT_PAD_REJECT_THRESHOLD,
                LivenessConfigKeys.PAD_REJECT_THRESHOLD_FLOOR, 100);
        if (reject > passive) {
            r.issue(LivenessConfigKeys.PAD_REJECT_THRESHOLD, ConfigIssue.Kind.CLAMPED, Integer.toString(reject),
                    Integer.toString(passive));
            reject = passive;
        }
        b.passiveThreshold(passive).padRejectThreshold(reject);
        b.passiveMinFrames(r.integer(LivenessConfigKeys.PASSIVE_MIN_FRAMES,
                LivenessConfigKeys.DEFAULT_PASSIVE_MIN_FRAMES, 1, 60));
        b.passiveTimeoutMs(r.longValue(LivenessConfigKeys.PASSIVE_TIMEOUT_MS,
                LivenessConfigKeys.DEFAULT_PASSIVE_TIMEOUT_MS, 1_000, 60_000));
        b.bindingMinSimilarity(r.integer(LivenessConfigKeys.BINDING_MIN_SIMILARITY,
                LivenessConfigKeys.DEFAULT_BINDING_MIN_SIMILARITY, 0, 100));
        b.bindingMaxGapMs(r.longValue(LivenessConfigKeys.BINDING_MAX_GAP_MS,
                LivenessConfigKeys.DEFAULT_BINDING_MAX_GAP_MS, 500, 120_000));
    }

    private static void resolveActive(Reader r, Workflow w, LivenessPolicy.Builder b) {
        b.activeEnabled(r.yesNo(LivenessConfigKeys.ACTIVE_ENABLE, true));
        b.minChallenges(r.integer(r.workflowFirst(w, LivenessConfigKeys.WORKFLOW_MIN_CHALLENGES_SUFFIX,
                LivenessConfigKeys.ACTIVE_MIN_CHALLENGES), LivenessConfigKeys.DEFAULT_MIN_CHALLENGES,
                LivenessConfigKeys.MIN_CHALLENGES_LOWER, LivenessConfigKeys.MIN_CHALLENGES_UPPER));
        b.challengeTypes(r.challengeTypes(LivenessConfigKeys.ACTIVE_CHALLENGE_TYPES));
        b.challengeTimeoutMs(r.longValue(LivenessConfigKeys.ACTIVE_CHALLENGE_TIMEOUT_MS,
                LivenessConfigKeys.DEFAULT_CHALLENGE_TIMEOUT_MS, LivenessConfigKeys.CHALLENGE_TIMEOUT_LOWER_MS,
                LivenessConfigKeys.CHALLENGE_TIMEOUT_UPPER_MS));
        b.minOnsetMs(r.longValue(LivenessConfigKeys.ACTIVE_MIN_ONSET_MS, LivenessConfigKeys.DEFAULT_MIN_ONSET_MS, 0, 2_000));
        b.holdMs(r.longValue(LivenessConfigKeys.ACTIVE_HOLD_MS, LivenessConfigKeys.DEFAULT_HOLD_MS, 0, 3_000));
    }

    private static void resolveOperation(Reader r, Workflow w, LivenessPolicy.Builder b) {
        b.maxRetries(r.integer(r.workflowFirst(w, LivenessConfigKeys.WORKFLOW_MAX_RETRIES_SUFFIX,
                LivenessConfigKeys.MAX_RETRIES), LivenessConfigKeys.DEFAULT_MAX_RETRIES,
                LivenessConfigKeys.MAX_RETRIES_LOWER, LivenessConfigKeys.MAX_RETRIES_UPPER));
        b.onMaxFailure(r.onMaxFailure(r.workflowFirst(w, LivenessConfigKeys.WORKFLOW_ON_MAX_FAILURE_SUFFIX,
                LivenessConfigKeys.ON_MAX_FAILURE), w));
        b.streamTimeoutMs(r.longValue(LivenessConfigKeys.STREAM_TIMEOUT_MS, LivenessConfigKeys.DEFAULT_STREAM_TIMEOUT_MS,
                1_000, 600_000));
        b.frameSampleIntervalMs(r.longValue(LivenessConfigKeys.FRAME_SAMPLE_INTERVAL_MS,
                LivenessConfigKeys.DEFAULT_FRAME_SAMPLE_INTERVAL_MS, 0, 5_000));
        b.deviceReconnectAttempts(r.integer(LivenessConfigKeys.DEVICE_RECONNECT_ATTEMPTS,
                LivenessConfigKeys.DEFAULT_DEVICE_RECONNECT_ATTEMPTS, 0, 10));
        b.qualityMinIedPx(r.integer(LivenessConfigKeys.QUALITY_MIN_IED_PX, LivenessConfigKeys.DEFAULT_QUALITY_MIN_IED_PX,
                0, 1_000));
        b.qualityMaxYawDeg(r.integer(LivenessConfigKeys.QUALITY_MAX_YAW_DEG,
                LivenessConfigKeys.DEFAULT_QUALITY_MAX_YAW_DEG, 0, 90));
        b.integrityMaxFrozenFrames(r.integer(LivenessConfigKeys.INTEGRITY_MAX_FROZEN_FRAMES,
                LivenessConfigKeys.DEFAULT_INTEGRITY_MAX_FROZEN_FRAMES, 1, 100));
        b.integrityMinFps(r.integer(LivenessConfigKeys.INTEGRITY_MIN_FPS, LivenessConfigKeys.DEFAULT_INTEGRITY_MIN_FPS,
                1, 60));
        b.noStreamPolicy(r.noStreamPolicy(LivenessConfigKeys.NO_STREAM_POLICY));
        b.modelMinVersion(r.version(LivenessConfigKeys.MODEL_MIN_VERSION, LivenessConfigKeys.DEFAULT_MODEL_MIN_VERSION));
        b.diagnosticEnabled(r.yesNo(LivenessConfigKeys.DIAGNOSTIC_ENABLE, false));
        b.metricsEnabled(r.yesNo(LivenessConfigKeys.METRICS_ENABLE, true));
        b.allowedCertifications(r.certifications(LivenessConfigKeys.MDS_ALLOWED_CERTIFICATIONS));
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
        String workflowFirst(Workflow w, String suffix, String globalKey) {
            String key = LivenessConfigKeys.workflowKey(w, suffix);
            return raw(key).isPresent() ? key : globalKey;
        }

        int integer(String key, int def, int min, int max) {
            return (int) longValue(key, def, min, max);
        }

        long longValue(String key, long def, long min, long max) {
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
            long clamped = Math.max(min, Math.min(max, parsed));
            if (clamped != parsed) {
                issue(key, ConfigIssue.Kind.CLAMPED, v.get(), Long.toString(clamped));
            }
            return clamped;
        }

        boolean yesNo(String key, boolean def) {
            Optional<String> v = raw(key);
            if (!v.isPresent()) {
                return def;
            }
            String s = v.get().toUpperCase(Locale.ROOT);
            if ("Y".equals(s) || "YES".equals(s) || "TRUE".equals(s)) {
                return true;
            }
            if ("N".equals(s) || "NO".equals(s) || "FALSE".equals(s)) {
                return false;
            }
            issue(key, ConfigIssue.Kind.INVALID, v.get(), def ? "Y" : "N");
            return def;
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

        NoStreamPolicy noStreamPolicy(String key) {
            NoStreamPolicy def = LivenessConfigKeys.DEFAULT_NO_STREAM_POLICY;
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

        Set<CertificationLevel> certifications(String key) {
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
                        levels.isEmpty() ? LivenessConfigKeys.DEFAULT_MDS_ALLOWED_CERTIFICATIONS : levels.toString());
            }
            return levels.isEmpty() ? def : levels;
        }

        Set<ChallengeType> challengeTypes(String key) {
            Set<ChallengeType> def = parseTypes(LivenessConfigKeys.DEFAULT_CHALLENGE_TYPES);
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
                issue(key, ConfigIssue.Kind.INVALID, v.get(), LivenessConfigKeys.DEFAULT_CHALLENGE_TYPES);
                return def;
            }
            if (unknown) {
                issue(key, ConfigIssue.Kind.INVALID, v.get(), join(types));
            }
            return types;
        }

        String version(String key, String def) {
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

    private static Set<ChallengeType> parseTypes(String csv) {
        Set<ChallengeType> types = EnumSet.noneOf(ChallengeType.class);
        for (String part : csv.split(",")) {
            Optional<ChallengeType> t = ChallengeType.fromName(part);
            if (t.isPresent()) {
                types.add(t.get());
            }
        }
        return types;
    }

    private static String join(Set<ChallengeType> types) {
        StringBuilder sb = new StringBuilder();
        for (ChallengeType t : types) {
            sb.append(sb.length() > 0 ? "," : "").append(t.name());
        }
        return sb.toString();
    }
}
