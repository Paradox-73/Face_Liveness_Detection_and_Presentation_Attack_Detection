/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.policy.PolicyResolver;

/**
 * Supervisor override: who may use it, the packet evidence of the stored photo, audit LV16, and the request and
 * result extras that carry it between ARC and the capture screen.
 */
@RunWith(RobolectricTestRunner.class)
public class SupervisorOverrideTest {

    @Test
    public void onlyResidentWorkflowsWithTheOverridePolicyAllowIt() {
        assertFalse("shipped policy is BLOCK", SupervisorOverride.isAllowedBy(policy(Workflow.REGISTRATION, null)));
        assertTrue(SupervisorOverride.isAllowedBy(policy(Workflow.REGISTRATION, "SUPERVISOR_OVERRIDE")));
        assertFalse(SupervisorOverride.isAllowedBy(policy(Workflow.LOGIN_AUTH, "SUPERVISOR_OVERRIDE")));
        assertFalse(SupervisorOverride.isAllowedBy(null));
    }

    @Test
    public void evidenceKeepsTheFailedTransactionAndFlagsTheOverride() {
        Map<String, String> failed = new HashMap<>();
        failed.put(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_FAIL);
        failed.put(PacketEvidenceKeys.SESSION, "S-7");
        SupervisorOverride grant = new SupervisorOverride(" supervisor1 ", failed);

        assertEquals("supervisor1", grant.supervisorId());
        assertEquals(PacketEvidenceKeys.DECISION_OVERRIDE, grant.evidence().get(PacketEvidenceKeys.DECISION));
        assertEquals("S-7", grant.evidence().get(PacketEvidenceKeys.SESSION));
        assertEquals("the failed evidence is not changed", PacketEvidenceKeys.DECISION_FAIL,
                grant.failedEvidence().get(PacketEvidenceKeys.DECISION));
        assertFalse("no supervisor ID in logs", grant.toString().contains("supervisor1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankSupervisorIsRejected() {
        new SupervisorOverride(" ", Collections.emptyMap());
    }

    @Test
    public void auditEventIsLv16WithTheSupervisorAndTheFailedSession() {
        SupervisorOverride grant = new SupervisorOverride("supervisor1",
                Collections.singletonMap(PacketEvidenceKeys.SESSION, "S-7"));
        Clock clock = new Clock() {
            @Override
            public long monotonicNanos() {
                return 0L;
            }

            @Override
            public Instant now() {
                return Instant.parse("2026-09-30T10:00:00Z");
            }
        };

        LivenessAuditEvent event = grant.auditEvent(Workflow.REGISTRATION, clock);

        assertEquals(AuditEventId.LIVENESS_OVERRIDE_USED, event.eventId());
        assertEquals("REG-EVT-LV16", event.eventId().id());
        assertEquals("S-7", event.sessionId());
        assertTrue(event.descriptionJson(), event.descriptionJson().contains("supervisor1"));
    }

    @Test
    public void overrideRequestAndGrantRoundTripThroughIntents() {
        SupervisorOverride grant = new SupervisorOverride("supervisor1",
                Collections.singletonMap(PacketEvidenceKeys.SESSION, "S-7"));
        LivenessCaptureRequest request = LivenessCaptureRequest.fromIntent(LivenessCaptureRequest
                .builder(Workflow.REGISTRATION, "rid").override(grant).build().writeTo(new Intent()));
        assertEquals("supervisor1", request.override().supervisorId());
        assertEquals("S-7", request.override().failedEvidence().get(PacketEvidenceKeys.SESSION));
        assertFalse(request.isOverrideOffer());

        LivenessCaptureRequest offer = LivenessCaptureRequest.fromIntent(LivenessCaptureRequest
                .builder(Workflow.REGISTRATION, "rid").overrideOffer(true).build().writeTo(new Intent()));
        assertTrue(offer.isOverrideOffer());
        assertNull(offer.override());

        LivenessCaptureResult failed = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.FAIL_LIVENESS)
                .failureReason(FailureReason.ACTIVE).attempts(3, 3).overrideAllowed(true)
                .evidence(PacketEvidenceKeys.SESSION, "S-7").build();
        LivenessCaptureResult asked = LivenessCaptureResult.fromIntent(
                failed.withOverrideRequest(" supervisor1 ").toIntent());
        assertEquals("supervisor1", asked.overrideRequestedBy());
        assertEquals(FailureReason.ACTIVE, asked.failureReason());
        assertEquals("S-7", asked.evidence().get(PacketEvidenceKeys.SESSION));
        assertNull(LivenessCaptureResult.fromIntent(failed.toIntent()).overrideRequestedBy());
    }

    @Test(expected = IllegalStateException.class)
    public void overrideCannotBeRequestedWhenTheEngineRefusedIt() {
        LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.FAIL_PAD).failureReason(FailureReason.PAD)
                .build().withOverrideRequest("supervisor1");
    }

    private static LivenessPolicy policy(Workflow workflow, String onMaxFailure) {
        Map<String, String> config = new HashMap<>();
        if (onMaxFailure != null) {
            config.put(LivenessConfigKeys.workflowKey(workflow, LivenessConfigKeys.WORKFLOW_ON_MAX_FAILURE_SUFFIX),
                    onMaxFailure);
        }
        return new PolicyResolver(ConfigSource.of(config)).resolve(workflow).policy();
    }
}
