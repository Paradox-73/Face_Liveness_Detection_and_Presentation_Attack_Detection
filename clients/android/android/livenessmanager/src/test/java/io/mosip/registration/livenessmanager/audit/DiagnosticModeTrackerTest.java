/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;

/** Audit REG-EVT-LV17 when the effective diagnostic mode changes. */
public class DiagnosticModeTrackerTest {

    private final List<LivenessAuditEvent> events = new ArrayList<>();
    private String stored;
    private String user = "supervisor1";

    private final DiagnosticModeTracker tracker = new DiagnosticModeTracker(new DiagnosticModeTracker.Store() {
        @Override
        public String load() {
            return stored;
        }

        @Override
        public void save(String value) {
            stored = value;
        }
    }, events::add, new Clock() {
        @Override
        public long monotonicNanos() {
            return 0L;
        }

        @Override
        public Instant now() {
            return Instant.parse("2026-09-30T10:00:00Z");
        }
    }, () -> user);

    @Test
    public void shippedOffStateIsNotAChange() {
        assertFalse(tracker.observe(Workflow.REGISTRATION, false));
        assertEquals("N", stored);
        assertTrue(events.isEmpty());
    }

    @Test
    public void switchingOnAndOffIsAuditedOnceEach() {
        tracker.observe(Workflow.REGISTRATION, false);

        assertTrue(tracker.observe(Workflow.REGISTRATION, true));
        assertFalse("same value again", tracker.observe(Workflow.LOGIN_AUTH, true));
        user = "officer1";
        assertTrue(tracker.observe(Workflow.LOGIN_AUTH, false));

        assertEquals(2, events.size());
        assertEquals(AuditEventId.LIVENESS_DIAGNOSTIC_MODE, events.get(0).eventId());
        assertTrue(events.get(0).descriptionJson(), events.get(0).descriptionJson().contains("supervisor1"));
        assertTrue(events.get(0).descriptionJson(), events.get(0).descriptionJson().contains("\"Y\""));
        assertTrue(events.get(1).descriptionJson(), events.get(1).descriptionJson().contains("officer1"));
        assertEquals("N", stored);
    }

    @Test
    public void firstValueOnIsAudited() {
        user = null;
        assertTrue(tracker.observe(Workflow.REGISTRATION, true));
        assertEquals(1, events.size());
        assertEquals("Y", stored);
    }
}
