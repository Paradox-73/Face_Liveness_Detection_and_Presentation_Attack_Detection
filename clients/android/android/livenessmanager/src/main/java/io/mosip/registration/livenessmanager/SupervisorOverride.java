/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;

/**
 * A supervisor override of a resident face capture ({@code on_max_failure=SUPERVISOR_OVERRIDE}), granted after
 * the resident used up the attempts and a supervisor then passed face authentication with liveness.
 *
 * <p>The resident's face is then taken without a liveness check. Its packet evidence is the evidence of the
 * failed transaction with {@link PacketEvidenceKeys#DECISION} replaced by
 * {@link PacketEvidenceKeys#DECISION_OVERRIDE}, and audit {@code REG-EVT-LV16} names the supervisor, as on the
 * desktop client. An override is never possible for authentication workflows or after a failure that
 * {@code FailureReason.allowsOverride()} excludes (suspected attack, frame integrity, capture binding); the
 * engine's {@code LivenessTransaction} decides that and the capture result reports it
 * ({@link LivenessCaptureResult#isOverrideAllowed()}).
 */
public final class SupervisorOverride {

    private final String supervisorId;
    private final Map<String, String> failedEvidence;

    /**
     * Creates an override.
     *
     * @param supervisorId   user ID of the supervisor who verified
     * @param failedEvidence packet evidence of the failed transaction ({@code LIVENESS_*} keys); may be empty
     * @throws IllegalArgumentException if the supervisor ID is blank
     */
    public SupervisorOverride(String supervisorId, Map<String, String> failedEvidence) {
        if (supervisorId == null || supervisorId.trim().isEmpty()) {
            throw new IllegalArgumentException("supervisorId is required");
        }
        this.supervisorId = supervisorId.trim();
        this.failedEvidence = Collections.unmodifiableMap(new LinkedHashMap<>(
                failedEvidence == null ? Collections.emptyMap() : failedEvidence));
    }

    /**
     * Tells whether a policy lets a supervisor override the resident's failed face capture.
     *
     * @param policy the resolved policy
     * @return {@code true} for a resident workflow whose {@code on_max_failure} is {@code SUPERVISOR_OVERRIDE}
     */
    public static boolean isAllowedBy(LivenessPolicy policy) {
        return policy != null && policy.workflow().isResident()
                && policy.onMaxFailure() == OnMaxFailure.SUPERVISOR_OVERRIDE;
    }

    /**
     * Returns the supervisor's user ID.
     *
     * @return the trimmed ID
     */
    public String supervisorId() {
        return supervisorId;
    }

    /**
     * Returns the packet evidence of the failed transaction.
     *
     * @return an unmodifiable map
     */
    public Map<String, String> failedEvidence() {
        return failedEvidence;
    }

    /**
     * Returns the packet evidence of the face stored under this override.
     *
     * @return the failed transaction's evidence with {@code LIVENESS_DECISION=OVERRIDE}
     */
    public Map<String, String> evidence() {
        Map<String, String> evidence = new LinkedHashMap<>(failedEvidence);
        evidence.put(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_OVERRIDE);
        return evidence;
    }

    /**
     * Builds audit {@code REG-EVT-LV16} (override used) for the failed transaction's session.
     *
     * @param workflow the resident workflow
     * @param clock    clock
     * @return the event
     */
    public LivenessAuditEvent auditEvent(Workflow workflow, Clock clock) {
        String session = failedEvidence.getOrDefault(PacketEvidenceKeys.SESSION, "");
        return new AuditPayloadBuilder(Objects.requireNonNull(workflow, "workflow"), session,
                Objects.requireNonNull(clock, "clock")).overrideUsed(supervisorId);
    }

    @Override
    public String toString() {
        // The supervisor ID is not written to logs.
        return "SupervisorOverride[evidence=" + failedEvidence.keySet() + "]";
    }
}
