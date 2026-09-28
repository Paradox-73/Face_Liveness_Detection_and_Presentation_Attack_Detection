package io.mosip.liveness.core.audit;

/**
 * Receiver of liveness audit events.
 *
 * <p>The desktop client writes them through {@code AuditManagerService}; the Android client through
 * {@code AuditManager.auditWithArguments}. Implementations must not throw; a failing audit store is logged by
 * the client, not propagated into the session.
 */
public interface AuditSink {

    /**
     * Stores one event.
     *
     * @param event the event
     */
    void emit(LivenessAuditEvent event);

    /**
     * Returns a sink that discards every event (tests and tools).
     *
     * @return a no-op sink
     */
    static AuditSink noop() {
        return event -> {
        };
    }
}
