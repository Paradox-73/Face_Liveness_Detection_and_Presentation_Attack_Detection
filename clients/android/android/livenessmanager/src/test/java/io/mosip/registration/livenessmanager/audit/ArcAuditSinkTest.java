/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

import java.time.Instant;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.spi.AuditManagerService;

@RunWith(RobolectricTestRunner.class)
public class ArcAuditSinkTest {

    @Test
    public void everyCoreEventHasTheSameIdInArc() {
        for (AuditEventId id : AuditEventId.values()) {
            AuditEvent event = ArcAuditSink.toAuditEvent(id);
            assertEquals(id.id(), event.getId());
            assertEquals(id.name(), event.getName());
            assertEquals("%s", event.getDescription());
        }
        assertEquals("REG-MOD-111", Components.LIVENESS.getId());
    }

    @Test
    public void storesTheJsonDescriptionWithTheLivenessComponent() {
        AuditManagerService service = mock(AuditManagerService.class);
        ArcAuditSink sink = new ArcAuditSink(service, Runnable::run);
        LivenessAuditEvent event = LivenessAuditEvent.builder(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED,
                Workflow.REGISTRATION, "s-1", Instant.parse("2026-09-29T10:00:00Z")).put("reason", "FROZEN").build();

        sink.emit(event);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(service).auditWithArguments(eq(AuditEvent.LIVENESS_PAD_ATTACK_SUSPECTED), eq("REG-MOD-111"),
                eq("Liveness"), description.capture());
        assertEquals(event.descriptionJson(), description.getValue());
        assertTrue(description.getValue().contains("\"sessionId\":\"s-1\""));
    }

    @Test
    public void failingAuditStoreNeverReachesTheSession() {
        AuditManagerService service = mock(AuditManagerService.class);
        doThrow(new IllegalStateException("db locked")).when(service)
                .auditWithArguments(any(AuditEvent.class), anyString(), anyString(), any(String[].class));
        ArcAuditSink sink = new ArcAuditSink(service, Runnable::run);
        sink.emit(LivenessAuditEvent.builder(AuditEventId.LIVENESS_PASSED, Workflow.PACKET_AUTH, "s-2",
                Instant.now()).build());
        sink.emit(null);
    }
}
