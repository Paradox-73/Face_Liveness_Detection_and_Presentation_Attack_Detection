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
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.ui.LivenessUiState;

/**
 * Delivers a session's callbacks to its {@link LivenessListener}. Identical consecutive UI states are sent once.
 * An exception thrown by the listener is swallowed, so a faulty screen cannot leave the state machine half-way
 * through a transition.
 */
final class SessionReporter {

    private final LivenessListener listener;
    private LivenessUiState lastUi;

    SessionReporter(LivenessListener listener) {
        this.listener = listener;
    }

    void ui(LivenessUiState state) {
        if (state.equals(lastUi)) {
            return;
        }
        lastUi = state;
        try {
            listener.onUi(state);
        } catch (RuntimeException e) {
            // The listener's own problem; the session carries on.
        }
    }

    void audit(LivenessAuditEvent event) {
        try {
            listener.onAudit(event);
        } catch (RuntimeException e) {
            // The listener's own problem; the session carries on.
        }
    }

    void stateChanged(SessionState previous, SessionState current) {
        try {
            listener.onStateChanged(previous, current);
        } catch (RuntimeException e) {
            // The listener's own problem; the session carries on.
        }
    }

    void result(LivenessResult result) {
        try {
            listener.onResult(result);
        } catch (RuntimeException e) {
            // The listener's own problem; the session carries on.
        }
    }
}
