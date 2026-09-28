package io.mosip.liveness.core.api;

import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.ui.LivenessUiState;

/**
 * Receiver of everything a session reports.
 *
 * <p>Callbacks run on the session's owning thread, inside the session call that caused them. They must return
 * quickly and must not call back into the session; clients hand UI states to their UI thread
 * ({@code Platform.runLater} on the desktop, the main-thread {@code Handler} on Android).
 */
public interface LivenessListener {

    /**
     * Receives a new UI state to render.
     *
     * @param state the state
     */
    void onUi(LivenessUiState state);

    /**
     * Receives an audit event; the client stores it through its audit service.
     *
     * @param event the event
     */
    void onAudit(LivenessAuditEvent event);

    /**
     * Receives a state change. The caller starts the signed capture when {@code current} is
     * {@link SessionState#CAPTURE_READY}.
     *
     * @param previous state before the change
     * @param current  state after the change
     */
    default void onStateChanged(SessionState previous, SessionState current) {
    }

    /**
     * Receives the final result once the session reaches a terminal state.
     *
     * @param result the result
     */
    default void onResult(LivenessResult result) {
    }
}
