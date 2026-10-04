/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.util.Objects;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;

/**
 * Writes audit {@code REG-EVT-LV17} (diagnostic mode changed) when the diagnostic switch
 * ({@code mosip.registration.liveness.diagnostic.enable}) is found in a new position at the start of a liveness
 * run. The switch is a synced configuration value, so a change is noticed at the next run, as {@code LV15} is.
 *
 * <p>The first observation after client start writes an event only when diagnostics are on, so a client that runs
 * with diagnostics on always has an {@code LV17} row per session of the application, while the default (off)
 * writes nothing.
 *
 * <p>Thread-safe.
 */
final class DiagnosticModeAudit {

	private final AuditSink audit;
	private final Clock clock;
	private Boolean lastSeen;

	/**
	 * Creates the tracker.
	 *
	 * @param audit audit sink
	 * @param clock clock for the event time
	 */
	DiagnosticModeAudit(AuditSink audit, Clock clock) {
		this.audit = Objects.requireNonNull(audit, "audit");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * Records the switch position seen at the start of a run and audits a change.
	 *
	 * @param enabled  whether diagnostics are on for this run
	 * @param userId   logged-in user, or empty before login
	 * @param workflow workflow of the run
	 * @return {@code true} if an event was written
	 */
	synchronized boolean observe(boolean enabled, String userId, Workflow workflow) {
		boolean changed = lastSeen == null ? enabled : lastSeen != enabled;
		lastSeen = enabled;
		if (changed) {
			audit.emit(new AuditPayloadBuilder(workflow, "", clock).diagnosticMode(userId == null ? "" : userId,
					enabled));
		}
		return changed;
	}
}
