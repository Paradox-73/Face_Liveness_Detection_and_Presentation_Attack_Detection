/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.audit;

import java.util.Objects;
import java.util.function.Supplier;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.PolicyKey;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;

/**
 * Writes audit {@code REG-EVT-LV17} (diagnostic mode changed) when the effective value of
 * {@code mosip.registration.liveness.diagnostic.enable} differs from the one seen last on this tablet.
 *
 * <p>On Android the value arrives with the configuration sync or as the operator's local setting, so there is no
 * switch to hook: the tracker compares the value each policy resolution sees with the last value it stored
 * ({@link Store}, kept across app restarts). The first value seen counts as a change only when it is {@code Y}
 * (the shipped default is {@code N}). The event names the signed-in user at the time the change took effect.
 *
 * <p>Thread-safe.
 */
public final class DiagnosticModeTracker {

    /** Keeps the last value seen. */
    public interface Store {

        /**
         * Returns the stored value.
         *
         * @return {@code Y}, {@code N}, or {@code null} when nothing was stored yet
         */
        String load();

        /**
         * Stores a value.
         *
         * @param value {@code Y} or {@code N}
         */
        void save(String value);
    }

    private final Store store;
    private final AuditSink audit;
    private final Clock clock;
    private final Supplier<String> userId;

    /**
     * Creates the tracker.
     *
     * @param store  where the last value is kept
     * @param audit  audit sink
     * @param clock  clock
     * @param userId returns the signed-in user's ID, or {@code null} / blank when nobody is signed in
     */
    public DiagnosticModeTracker(Store store, AuditSink audit, Clock clock, Supplier<String> userId) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    /**
     * Records the value a policy resolution saw and audits a change.
     *
     * @param workflow the workflow being resolved (the audit event needs one)
     * @param enabled  whether diagnostic mode is on
     * @return {@code true} when {@code REG-EVT-LV17} was written
     */
    public synchronized boolean observe(Workflow workflow, boolean enabled) {
        String value = enabled ? LivenessConfigKeys.YES : LivenessConfigKeys.NO;
        String last = store.load();
        String previous = last == null ? PolicyKey.DIAGNOSTIC_ENABLE.defaultValue() : last;
        if (value.equalsIgnoreCase(previous)) {
            if (last == null) {
                store.save(value);
            }
            return false;
        }
        store.save(value);
        String user = userId.get();
        audit.emit(new AuditPayloadBuilder(Objects.requireNonNull(workflow, "workflow"), "", clock)
                .diagnosticMode(user == null ? "" : user.trim(), enabled));
        return true;
    }
}
