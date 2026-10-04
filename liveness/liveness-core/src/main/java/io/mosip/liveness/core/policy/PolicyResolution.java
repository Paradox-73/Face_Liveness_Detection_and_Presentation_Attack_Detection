/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.policy;

import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of {@link PolicyResolver#resolve}: the policy plus every value that fell back to its default or was
 * clamped.
 */
public final class PolicyResolution {

    private final LivenessPolicy policy;
    private final List<ConfigIssue> issues;

    PolicyResolution(LivenessPolicy policy, List<ConfigIssue> issues) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
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
     * Returns the values that could not be used as configured.
     *
     * @return an unmodifiable list, possibly empty
     */
    public List<ConfigIssue> issues() {
        return issues;
    }

    /**
     * Returns the keys whose values were clamped to a floor or range.
     *
     * @return keys in resolution order
     */
    public List<String> clampedKeys() {
        List<String> keys = new ArrayList<>();
        for (ConfigIssue i : issues) {
            if (i.kind() == ConfigIssue.Kind.CLAMPED) {
                keys.add(i.key());
            }
        }
        return keys;
    }

    /**
     * Builds the audit event for clamped values ({@code REG-EVT-LV15}), which the client writes when it applies a
     * new configuration.
     *
     * @param now event time
     * @return the event, or empty when nothing was clamped
     */
    public Optional<LivenessAuditEvent> configAppliedEvent(Instant now) {
        List<String> keys = clampedKeys();
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append(sb.length() > 0 ? "," : "").append(k);
        }
        String changed = sb.length() > LivenessAuditEvent.MAX_VALUE_LENGTH
                ? sb.substring(0, LivenessAuditEvent.MAX_VALUE_LENGTH) : sb.toString();
        return Optional.of(LivenessAuditEvent.builder(AuditEventId.LIVENESS_CONFIG_APPLIED, policy.workflow(), "", now)
                .put(AuditAttributes.CHANGED, changed).build());
    }

    /**
     * Writes one diagnostic record per issue when the policy enables diagnostics.
     *
     * @param sink the diagnostic sink
     * @return the number of records written
     */
    public int writeDiagnostics(DiagnosticSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (!policy.isDiagnosticEnabled()) {
            return 0;
        }
        int written = 0;
        for (ConfigIssue i : issues) {
            String detail = i.describe();
            if (detail.length() > DiagnosticRecord.MAX_DETAIL_LENGTH) {
                detail = detail.substring(0, DiagnosticRecord.MAX_DETAIL_LENGTH);
            }
            try {
                sink.log(DiagnosticRecord.builder("", SessionState.IDLE, 0).detail(detail).build());
                written++;
            } catch (RuntimeException e) {
                // Diagnostics must never affect the caller.
            }
        }
        return written;
    }
}
