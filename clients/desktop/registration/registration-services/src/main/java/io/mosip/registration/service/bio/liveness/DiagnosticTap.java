package io.mosip.registration.service.bio.liveness;

import java.util.Objects;
import java.util.Optional;

import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.audit.DiagnosticSink;

/**
 * Passes diagnostic records on to the client log and keeps the latest one, so the liveness window can show it in
 * its diagnostic line (diagnostic mode, G5). A record holds only technical values (state, PAD score, flags,
 * planarity, binding similarity, frame rate, model versions): no image, template or identifier of a person.
 *
 * <p>Thread-safe: sessions write on the liveness worker thread, the screen reads on the UI thread.
 */
final class DiagnosticTap implements DiagnosticSink {

	private final DiagnosticSink target;
	private volatile DiagnosticRecord last;

	/**
	 * Creates a tap.
	 *
	 * @param target where records go after being kept (the client log)
	 */
	DiagnosticTap(DiagnosticSink target) {
		this.target = Objects.requireNonNull(target, "target");
	}

	@Override
	public void log(DiagnosticRecord record) {
		if (record == null) {
			return;
		}
		last = record;
		target.log(record);
	}

	/**
	 * Returns the latest record.
	 *
	 * @return the record; empty before the first one or when diagnostics are off
	 */
	Optional<DiagnosticRecord> last() {
		return Optional.ofNullable(last);
	}
}
