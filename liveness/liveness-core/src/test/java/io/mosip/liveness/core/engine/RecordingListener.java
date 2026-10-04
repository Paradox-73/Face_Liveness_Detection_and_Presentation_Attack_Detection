/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.ui.LivenessUiState;

import java.util.ArrayList;
import java.util.List;

/** Listener that records every callback. */
final class RecordingListener implements LivenessListener {

    final List<LivenessUiState> ui = new ArrayList<>();
    final List<LivenessAuditEvent> audits = new ArrayList<>();
    final List<SessionState> states = new ArrayList<>();
    final List<LivenessResult> results = new ArrayList<>();

    @Override
    public void onUi(LivenessUiState state) {
        ui.add(state);
    }

    @Override
    public void onAudit(LivenessAuditEvent event) {
        audits.add(event);
    }

    @Override
    public void onStateChanged(SessionState previous, SessionState current) {
        states.add(current);
    }

    @Override
    public void onResult(LivenessResult result) {
        results.add(result);
    }

    LivenessUiState lastUi() {
        return ui.get(ui.size() - 1);
    }

    List<AuditEventId> auditIds() {
        List<AuditEventId> ids = new ArrayList<>();
        for (LivenessAuditEvent e : audits) {
            ids.add(e.eventId());
        }
        return ids;
    }

    LivenessAuditEvent lastAudit(AuditEventId id) {
        LivenessAuditEvent found = null;
        for (LivenessAuditEvent e : audits) {
            if (e.eventId() == id) {
                found = e;
            }
        }
        return found;
    }

    boolean sawUi(String messageKey) {
        for (LivenessUiState s : ui) {
            if (s.messageKey().equals(messageKey)) {
                return true;
            }
        }
        return false;
    }
}
