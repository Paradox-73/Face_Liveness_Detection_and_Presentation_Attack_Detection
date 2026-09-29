package io.mosip.registration.livenessmanager.audit;

import android.util.Log;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.spi.AuditManagerService;

/**
 * Stores liveness audit events ({@code REG-EVT-LV01..17}) through ARC's {@link AuditManagerService}, with the
 * same event IDs as the desktop client and component {@link Components#LIVENESS} ({@code REG-MOD-111}).
 *
 * <p>The description column receives {@link LivenessAuditEvent#descriptionJson()}: workflow, session ID and short
 * attributes such as score buckets and reason codes, never images, templates or raw scores. Because the IDs
 * contain {@code REG-EVT}, ARC records them against the registration ID during a registration, so they travel
 * inside the packet with the other client audits.
 *
 * <p>Events are written in order on one background thread, so a slow database never delays frame analysis. A
 * failing audit store is logged and never reaches the liveness session.
 */
public final class ArcAuditSink implements AuditSink {

    private static final String TAG = "Liveness";
    private static final int QUEUE_CAPACITY = 1_000;

    private final AuditManagerService auditManagerService;
    private final Executor executor;

    /**
     * Creates the sink with its own writer thread.
     *
     * @param auditManagerService ARC's audit service
     */
    public ArcAuditSink(AuditManagerService auditManagerService) {
        this(auditManagerService, newWriter());
    }

    /**
     * Creates the sink with a caller-supplied executor (tests pass {@code Runnable::run}).
     *
     * @param auditManagerService ARC's audit service
     * @param executor            executor that stores the events in submission order
     */
    public ArcAuditSink(AuditManagerService auditManagerService, Executor executor) {
        this.auditManagerService = Objects.requireNonNull(auditManagerService, "auditManagerService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Maps a core event ID to ARC's audit event.
     *
     * @param id core event ID
     * @return the ARC event with the same name and ID
     */
    public static AuditEvent toAuditEvent(AuditEventId id) {
        return AuditEvent.valueOf(id.name());
    }

    @Override
    public void emit(LivenessAuditEvent event) {
        if (event == null) {
            return;
        }
        try {
            executor.execute(() -> store(event));
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Liveness audit " + event.eventId().id() + " dropped: audit queue full");
        }
    }

    private void store(LivenessAuditEvent event) {
        try {
            auditManagerService.auditWithArguments(toAuditEvent(event.eventId()), Components.LIVENESS.getId(),
                    Components.LIVENESS.getName(), event.descriptionJson());
        } catch (RuntimeException e) {
            Log.e(TAG, "Liveness audit " + event.eventId().id() + " could not be stored: "
                    + e.getClass().getSimpleName());
        }
    }

    private static ExecutorService newWriter() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "liveness-audit");
                    t.setDaemon(true);
                    return t;
                });
    }
}
