package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.AuditEvent;
import io.mosip.registration.constants.Components;

/** Tests {@link DesktopAuditSink}: event mapping, component, JSON description and failure isolation. */
public class DesktopAuditSinkTest {

	@Test
	public void everyCoreEventMapsToAClientEventWithTheSameId() {
		for (AuditEventId id : AuditEventId.values()) {
			AuditEvent event = DesktopAuditSink.toAuditEvent(id);
			assertEquals(id.id(), event.getId());
			assertTrue(event.getId().startsWith("REG-EVT-LV"));
			assertTrue(event.getId().length() <= 64);
		}
		assertEquals(17, AuditEventId.values().length);
		assertEquals("REG-MOD-153", Components.REG_LIVENESS.getId());
	}

	@Test
	public void eventIsStoredWithJsonDescriptionAndLivenessComponent() {
		AuditManagerService service = Mockito.mock(AuditManagerService.class);
		DesktopAuditSink sink = new DesktopAuditSink(service, Runnable::run);
		LivenessAuditEvent event = new AuditPayloadBuilder(Workflow.PACKET_AUTH, "s-1", Clock.system())
				.passivePassed(87, 9, 1200);
		sink.emit(event);

		ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
		Mockito.verify(service).audit(Mockito.eq(AuditEvent.LIVENESS_PASSIVE_PASSED),
				Mockito.eq(Components.REG_LIVENESS), Mockito.anyString(), Mockito.anyString(), description.capture());
		String json = description.getValue();
		assertTrue(json, json.startsWith("{"));
		assertTrue(json, json.contains("packet_auth"));
		assertTrue(json, json.contains("s-1"));
		assertFalse("raw score must not be audited: " + json, json.contains("87"));
		assertTrue(json.length() <= 2048);
	}

	@Test
	public void storeFailureNeverReachesTheSession() {
		AuditManagerService service = Mockito.mock(AuditManagerService.class);
		Mockito.doThrow(new IllegalStateException("db locked")).when(service).audit(Mockito.any(), Mockito.any(),
				Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
		DesktopAuditSink sink = new DesktopAuditSink(service, Runnable::run);
		sink.emit(LivenessAuditEvent.builder(AuditEventId.LIVENESS_PASSED, Workflow.REGISTRATION, "s", Instant.now())
				.build());
		sink.emit(null);
	}

	@Test
	public void ownWriterThreadWritesInOrderAndStops() throws Exception {
		AuditManagerService service = Mockito.mock(AuditManagerService.class);
		DesktopAuditSink sink = new DesktopAuditSink(service);
		for (int i = 0; i < 5; i++) {
			sink.emit(LivenessAuditEvent.builder(AuditEventId.LIVENESS_SESSION_STARTED, Workflow.REGISTRATION,
					"s" + i, Instant.now()).build());
		}
		sink.shutdown();
		Mockito.verify(service, Mockito.times(5)).audit(Mockito.eq(AuditEvent.LIVENESS_SESSION_STARTED),
				Mockito.eq(Components.REG_LIVENESS), Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
	}
}
