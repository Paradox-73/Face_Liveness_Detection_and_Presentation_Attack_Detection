package io.mosip.registration.service.bio.liveness;

import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.registration.config.AppConfig;

/**
 * Writes liveness diagnostic records to the client log ({@code logs/registration.log}).
 *
 * <p>Sessions call it only when {@code mosip.registration.liveness.diagnostic.enable=Y}, one line per state
 * change: session ID, state, PAD score, integrity flags, planarity residual, binding similarity, frame rate and
 * model versions. The record type cannot hold images, embeddings or user IDs. Lines are written at INFO level so
 * the switch works without changing the logging configuration.
 */
@Component
public class DesktopDiagnosticSink implements DiagnosticSink {

	private static final Logger LOGGER = AppConfig.getLogger(DesktopDiagnosticSink.class);

	/** Prefix of every diagnostic line, for filtering the log. */
	public static final String PREFIX = "LIVENESS-DIAG ";

	@Override
	public void log(DiagnosticRecord record) {
		if (record == null) {
			return;
		}
		try {
			LOGGER.info(PREFIX + record.toLogLine());
		} catch (RuntimeException e) {
			// A logging failure must never reach the session.
		}
	}
}
