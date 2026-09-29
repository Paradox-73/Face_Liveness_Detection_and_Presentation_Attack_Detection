package io.mosip.registration.service.bio.liveness;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.AuditReferenceIdTypes;
import io.mosip.registration.constants.Components;
import io.mosip.registration.constants.RegistrationConstants;

/**
 * Stores liveness audit events ({@code REG-EVT-LV01..17}) through the client's {@link AuditManagerService}, with
 * component {@link Components#REG_LIVENESS} ({@code REG-MOD-153}).
 *
 * <p>The description column receives {@link LivenessAuditEvent#descriptionJson()}: workflow, session ID and
 * short attributes such as score buckets, never images, templates or raw scores. Because the event IDs contain
 * {@code REG-EVT}, the audit service records them against the registration ID during a registration, and the
 * audits travel inside the packet like every other client audit.
 *
 * <p>Events are written on one background thread, in order, so a slow database never delays frame analysis. A
 * failing audit store is logged and never reaches the liveness session.
 */
@Component
public class DesktopAuditSink implements AuditSink {

	private static final Logger LOGGER = AppConfig.getLogger(DesktopAuditSink.class);
	private static final int QUEUE_CAPACITY = 1_000;

	private final AuditManagerService auditManagerService;
	private final Executor executor;
	private final ExecutorService ownedExecutor;

	/**
	 * Creates the sink with its own background writer thread.
	 *
	 * @param auditManagerService the client audit service
	 */
	@Autowired
	public DesktopAuditSink(AuditManagerService auditManagerService) {
		this.auditManagerService = Objects.requireNonNull(auditManagerService, "auditManagerService");
		ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(QUEUE_CAPACITY), r -> {
					Thread t = new Thread(r, "liveness-audit");
					t.setDaemon(true);
					return t;
				});
		this.executor = pool;
		this.ownedExecutor = pool;
	}

	/**
	 * Creates the sink with a caller-supplied executor (tests pass {@code Runnable::run}).
	 *
	 * @param auditManagerService the client audit service
	 * @param executor            executor that writes the events, in submission order
	 */
	public DesktopAuditSink(AuditManagerService auditManagerService, Executor executor) {
		this.auditManagerService = Objects.requireNonNull(auditManagerService, "auditManagerService");
		this.executor = Objects.requireNonNull(executor, "executor");
		this.ownedExecutor = null;
	}

	/**
	 * Maps a core audit event ID to the client's audit event.
	 *
	 * @param id core event ID
	 * @return the client event with the same name and the same {@code REG-EVT-LVnn} ID
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
			LOGGER.error("Liveness audit {} dropped: audit queue full or closed", event.eventId().id());
		}
	}

	private void store(LivenessAuditEvent event) {
		try {
			auditManagerService.audit(toAuditEvent(event.eventId()), Components.REG_LIVENESS,
					RegistrationConstants.APPLICATION_ID, AuditReferenceIdTypes.APPLICATION_ID.getReferenceTypeId(),
					event.descriptionJson());
		} catch (RuntimeException e) {
			LOGGER.error("Liveness audit {} could not be stored: {}", event.eventId().id(), e.getClass().getSimpleName());
		}
	}

	/** Writes the queued events (waiting at most 5 seconds) and stops the writer thread. */
	@PreDestroy
	public void shutdown() {
		if (ownedExecutor == null) {
			return;
		}
		ownedExecutor.shutdown();
		try {
			ownedExecutor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
