/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.policy;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.NoStreamPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.config.PolicyKey;
import io.mosip.liveness.core.config.PolicyMode;
import io.mosip.liveness.core.device.CertificationLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-CORE-POL-01..08: configuration keys to per-workflow policy. */
class PolicyResolverTest {

    private static final String P = LivenessConfigKeys.PREFIX;
    private final Map<String, String> cfg = new HashMap<>();

    private PolicyResolution resolve(Workflow w) {
        return new PolicyResolver(ConfigSource.of(cfg)).resolve(w);
    }

    @Test
    void emptyConfigGivesTheShippedDefaults() {
        for (Workflow w : Workflow.values()) {
            PolicyResolution r = resolve(w);
            assertEquals(LivenessPolicy.defaults(w), r.policy(), w.id());
            assertTrue(r.issues().isEmpty());
        }
    }

    @Test
    void shippedDefaultValuesResolveToTheDefaultPolicies() {
        cfg.putAll(LivenessConfigKeys.defaultValues());
        for (Workflow w : Workflow.values()) {
            PolicyResolution r = resolve(w);
            assertEquals(LivenessPolicy.defaults(w), r.policy(), w.id());
            assertTrue(r.issues().isEmpty(), r.issues().toString());
        }
    }

    /** T-CORE-POL-01: master switch. */
    @Test
    void masterSwitchOffDisablesEveryWorkflow() {
        cfg.put(LivenessConfigKeys.ENABLE, "n");
        cfg.put(LivenessConfigKeys.policyKey(Workflow.REGISTRATION), "ALWAYS_ACTIVE");
        for (Workflow w : Workflow.values()) {
            assertEquals(PolicyMode.OFF, resolve(w).policy().mode());
        }
    }

    /** T-CORE-POL-02: passive threshold, floors and per-workflow overrides. */
    @Test
    void thresholdsFloorsAndOverrides() {
        cfg.put(LivenessConfigKeys.PASSIVE_THRESHOLD, "70");
        cfg.put(LivenessConfigKeys.workflowKey(Workflow.EOD_AUTH, LivenessConfigKeys.WORKFLOW_PASSIVE_THRESHOLD_SUFFIX), "30");
        assertEquals(70, resolve(Workflow.REGISTRATION).policy().passiveThreshold());
        assertEquals(70, resolve(Workflow.PACKET_AUTH).policy().passiveThreshold(), "unset workflow key: global value");
        PolicyResolution eod = resolve(Workflow.EOD_AUTH);
        assertEquals(50, eod.policy().passiveThreshold(), "clamped to the floor of 50");
        assertEquals(1, eod.clampedKeys().size());
        assertEquals(ConfigIssue.Kind.CLAMPED, eod.issues().get(0).kind());
        assertEquals("30", eod.issues().get(0).rawValue());
        assertEquals("50", eod.issues().get(0).appliedValue());

        cfg.put(LivenessConfigKeys.PAD_REJECT_THRESHOLD, "2");
        assertEquals(5, resolve(Workflow.REGISTRATION).policy().padRejectThreshold());
        cfg.put(LivenessConfigKeys.PAD_REJECT_THRESHOLD, "90");
        PolicyResolution reg = resolve(Workflow.REGISTRATION);
        assertEquals(70, reg.policy().padRejectThreshold(), "never above the passive threshold");
        assertTrue(reg.clampedKeys().contains(LivenessConfigKeys.PAD_REJECT_THRESHOLD));
    }

    /** T-CORE-POL-03 / 08: policy per workflow and active switch. */
    @Test
    void policyModesPerWorkflow() {
        cfg.put(LivenessConfigKeys.policyKey(Workflow.LOGIN_AUTH), "always_active");
        cfg.put(LivenessConfigKeys.policyKey(Workflow.PACKET_AUTH), "sometimes");
        cfg.put(LivenessConfigKeys.ACTIVE_ENABLE, "no");
        assertEquals(PolicyMode.ALWAYS_ACTIVE, resolve(Workflow.LOGIN_AUTH).policy().mode());
        PolicyResolution packet = resolve(Workflow.PACKET_AUTH);
        assertEquals(PolicyMode.ALWAYS_ACTIVE, packet.policy().mode(), "an invalid value falls back to the default");
        assertEquals(ConfigIssue.Kind.INVALID, packet.issues().get(0).kind());
        assertFalse(packet.policy().isActiveEnabled());
        assertEquals(PolicyMode.OFF, resolve(Workflow.EXCEPTION_PHOTO).policy().mode());
    }

    /** T-CORE-POL-04: challenge pool. */
    @Test
    void challengeTypes() {
        cfg.put(LivenessConfigKeys.ACTIVE_CHALLENGE_TYPES, " turn_left, LOOK_DOWN ,WINK,,");
        PolicyResolution r = resolve(Workflow.REGISTRATION);
        assertEquals(EnumSet.of(ChallengeType.TURN_LEFT, ChallengeType.LOOK_DOWN), r.policy().challengeTypes());
        assertEquals("TURN_LEFT,LOOK_DOWN", r.issues().get(0).appliedValue());
        cfg.put(LivenessConfigKeys.ACTIVE_CHALLENGE_TYPES, "WINK,NOD");
        assertEquals(LivenessPolicy.defaults(Workflow.REGISTRATION).challengeTypes(),
                resolve(Workflow.REGISTRATION).policy().challengeTypes());
    }

    /** T-CORE-POL-05..07: counts, timeouts and retries within their ranges. */
    @Test
    void rangesAreEnforced() {
        cfg.put(LivenessConfigKeys.ACTIVE_MIN_CHALLENGES, "9");
        cfg.put(LivenessConfigKeys.workflowKey(Workflow.REGISTRATION, LivenessConfigKeys.WORKFLOW_MIN_CHALLENGES_SUFFIX), "3");
        cfg.put(LivenessConfigKeys.ACTIVE_CHALLENGE_TIMEOUT_MS, "500");
        cfg.put(LivenessConfigKeys.MAX_RETRIES, "0");
        cfg.put(LivenessConfigKeys.workflowKey(Workflow.LOGIN_AUTH, LivenessConfigKeys.WORKFLOW_MAX_RETRIES_SUFFIX), "5");
        cfg.put(LivenessConfigKeys.ACTIVE_HOLD_MS, "abc");
        LivenessPolicy reg = resolve(Workflow.REGISTRATION).policy();
        assertEquals(3, reg.minChallenges());
        assertEquals(2_000, reg.challengeTimeoutMs());
        assertEquals(1, reg.maxRetries());
        assertEquals(PolicyKey.ACTIVE_HOLD_MS.defaultLong(), reg.holdMs());
        assertEquals(5, resolve(Workflow.PACKET_AUTH).policy().minChallenges());
        assertEquals(5, resolve(Workflow.LOGIN_AUTH).policy().maxRetries());
    }

    /** T-CORE-TX-02 input: {@code on_max_failure} per workflow. */
    @Test
    void loginLocksTheAccountByDefault() {
        assertEquals(OnMaxFailure.BLOCK_AND_LOCK, resolve(Workflow.LOGIN_AUTH).policy().onMaxFailure());
        assertEquals(OnMaxFailure.BLOCK, resolve(Workflow.PACKET_AUTH).policy().onMaxFailure());
        assertEquals(OnMaxFailure.BLOCK, resolve(Workflow.REGISTRATION).policy().onMaxFailure());
        cfg.put(LivenessConfigKeys.ON_MAX_FAILURE, "BLOCK");
        assertEquals(OnMaxFailure.BLOCK, resolve(Workflow.LOGIN_AUTH).policy().onMaxFailure(), "set explicitly");
        assertEquals(OnMaxFailure.BLOCK_AND_LOCK, LivenessPolicy.defaults(Workflow.LOGIN_AUTH).onMaxFailure());
    }

    @Test
    void onMaxFailurePerWorkflow() {
        cfg.put(LivenessConfigKeys.ON_MAX_FAILURE, "SUPERVISOR_OVERRIDE");
        cfg.put(LivenessConfigKeys.workflowKey(Workflow.LOGIN_AUTH, LivenessConfigKeys.WORKFLOW_ON_MAX_FAILURE_SUFFIX),
                "BLOCK_AND_LOCK");
        assertEquals(OnMaxFailure.SUPERVISOR_OVERRIDE, resolve(Workflow.REGISTRATION).policy().onMaxFailure());
        assertEquals(OnMaxFailure.BLOCK_AND_LOCK, resolve(Workflow.LOGIN_AUTH).policy().onMaxFailure());
        PolicyResolution packet = resolve(Workflow.PACKET_AUTH);
        assertEquals(OnMaxFailure.BLOCK, packet.policy().onMaxFailure(), "override is not allowed for auth");
        assertEquals(ConfigIssue.Kind.NOT_ALLOWED, packet.issues().get(0).kind());
        cfg.put(LivenessConfigKeys.ON_MAX_FAILURE, "PANIC");
        assertEquals(ConfigIssue.Kind.INVALID, resolve(Workflow.REGISTRATION).issues().get(0).kind());
    }

    @Test
    void operationalKeys() {
        cfg.put(LivenessConfigKeys.NO_STREAM_POLICY, "capture_only");
        cfg.put(LivenessConfigKeys.MODEL_MIN_VERSION, "1.2.4");
        cfg.put(LivenessConfigKeys.DIAGNOSTIC_ENABLE, "true");
        cfg.put(LivenessConfigKeys.METRICS_ENABLE, "N");
        cfg.put(LivenessConfigKeys.FRAME_SAMPLE_INTERVAL_MS, "100");
        cfg.put(LivenessConfigKeys.QUALITY_MAX_YAW_DEG, "120");
        cfg.put(LivenessConfigKeys.BINDING_MIN_SIMILARITY, "75");
        LivenessPolicy p = resolve(Workflow.REGISTRATION).policy();
        assertEquals(NoStreamPolicy.CAPTURE_ONLY, p.noStreamPolicy());
        assertEquals("1.2.4", p.modelMinVersion());
        assertTrue(p.isDiagnosticEnabled());
        assertFalse(p.isMetricsEnabled());
        assertEquals(100, p.frameSampleIntervalMs());
        assertEquals(90, p.qualityMaxYawDeg());
        assertEquals(75, p.bindingMinSimilarity());
        cfg.put(LivenessConfigKeys.NO_STREAM_POLICY, "sometimes");
        cfg.put(LivenessConfigKeys.MODEL_MIN_VERSION, "latest");
        cfg.put(LivenessConfigKeys.DIAGNOSTIC_ENABLE, "maybe");
        PolicyResolution bad = resolve(Workflow.REGISTRATION);
        assertEquals(NoStreamPolicy.BLOCK, bad.policy().noStreamPolicy());
        assertEquals("1.3.0", bad.policy().modelMinVersion());
        assertFalse(bad.policy().isDiagnosticEnabled());
        assertEquals(4, bad.issues().size(), bad.issues().toString());
    }

    @Test
    void allowedCertificationsAreParsed() {
        assertEquals(EnumSet.allOf(CertificationLevel.class),
                resolve(Workflow.REGISTRATION).policy().allowedCertifications());
        cfg.put(LivenessConfigKeys.MDS_ALLOWED_CERTIFICATIONS, " l1 ");
        assertEquals(EnumSet.of(CertificationLevel.L1), resolve(Workflow.LOGIN_AUTH).policy().allowedCertifications());
        cfg.put(LivenessConfigKeys.MDS_ALLOWED_CERTIFICATIONS, "L2");
        PolicyResolution bad = resolve(Workflow.REGISTRATION);
        assertEquals(EnumSet.allOf(CertificationLevel.class), bad.policy().allowedCertifications());
        assertEquals(ConfigIssue.Kind.INVALID, bad.issues().get(0).kind());
    }

    /** The model registry needs MAJOR.MINOR.PATCH; a shorter value must not reach it and stop the engine. */
    @Test
    void modelMinVersionNeedsThreeParts() {
        for (String shortVersion : new String[] {"1", "1.2", "1.2.3.4"}) {
            cfg.put(LivenessConfigKeys.MODEL_MIN_VERSION, shortVersion);
            PolicyResolution r = resolve(Workflow.REGISTRATION);
            assertEquals(PolicyKey.MODEL_MIN_VERSION.defaultValue(), r.policy().modelMinVersion(), shortVersion);
            assertEquals(ConfigIssue.Kind.INVALID, r.issues().get(0).kind(), shortVersion);
        }
    }

    /** T-CORE-POL-01: null-safe reads. */
    @Test
    void misbehavingSourcesCountAsMissingKeys() {
        ConfigSource nulls = key -> null;
        assertEquals(LivenessPolicy.defaults(Workflow.PACKET_AUTH),
                new PolicyResolver(nulls).resolve(Workflow.PACKET_AUTH).policy());
        ConfigSource throwing = key -> {
            throw new IllegalStateException("database closed");
        };
        assertEquals(LivenessPolicy.defaults(Workflow.REGISTRATION),
                new PolicyResolver(throwing).resolve(Workflow.REGISTRATION).policy());
        ConfigSource blanks = key -> Optional.of("   ");
        assertEquals(LivenessPolicy.defaults(Workflow.REGISTRATION),
                new PolicyResolver(blanks).resolve(Workflow.REGISTRATION).policy());
    }

    @Test
    void clampsAreAuditedAndIssuesLogged() {
        cfg.put(LivenessConfigKeys.PASSIVE_THRESHOLD, "10");
        cfg.put(LivenessConfigKeys.PASSIVE_MIN_FRAMES, "x");
        PolicyResolution r = resolve(Workflow.REGISTRATION);
        Instant now = Instant.parse("2026-09-28T10:00:00Z");
        LivenessAuditEvent e = r.configAppliedEvent(now).get();
        assertEquals(AuditEventId.LIVENESS_CONFIG_APPLIED, e.eventId());
        assertEquals(LivenessConfigKeys.PASSIVE_THRESHOLD, e.attributes().get(AuditAttributes.CHANGED));
        assertEquals("", e.sessionId());
        List<DiagnosticRecord> out = new ArrayList<>();
        assertEquals(0, r.writeDiagnostics(out::add), "diagnostics are off");
        cfg.put(LivenessConfigKeys.DIAGNOSTIC_ENABLE, "Y");
        PolicyResolution withDiag = resolve(Workflow.REGISTRATION);
        assertEquals(2, withDiag.writeDiagnostics(out::add));
        assertTrue(out.get(0).toLogLine().contains("issue=CLAMPED"), out.get(0).toLogLine());
        assertTrue(out.get(1).toLogLine().contains("issue=INVALID"));
        assertEquals(0, withDiag.writeDiagnostics(rec -> {
            throw new IllegalStateException("x");
        }));
        cfg.clear();
        assertFalse(resolve(Workflow.REGISTRATION).configAppliedEvent(now).isPresent());
    }

    @Test
    void issueDescription() {
        char[] longValue = new char[100];
        java.util.Arrays.fill(longValue, 'v');
        ConfigIssue i = new ConfigIssue(P + "x", ConfigIssue.Kind.INVALID, new String(longValue), "d");
        assertEquals(64, i.rawValue().length());
        assertTrue(i.toString().startsWith("config key=" + P + "x issue=INVALID"));
        assertEquals("", new ConfigIssue("k", ConfigIssue.Kind.CLAMPED, null, "1").rawValue());
    }
}
