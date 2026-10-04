/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.enums.Role;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.mdm.dto.MDMRequestDto;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.service.bio.liveness.LivenessFakes.PhotoModels;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingAudit;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingListener;
import io.mosip.registration.service.bio.liveness.LivenessFakes.Script;
import io.mosip.registration.service.bio.liveness.LivenessFakes.ScriptedEngine;
import io.mosip.registration.service.bio.liveness.LivenessFakes.SyntheticDevice;
import io.mosip.registration.service.bio.liveness.LivenessFakes.TestClock;

/**
 * Tests the desktop supervisor override after the last failed attempt ({@code on_max_failure=SUPERVISOR_OVERRIDE}):
 * it needs a supervisor other than the logged-in operator who authenticated within the override validity, and the
 * photo taken after the grant must show one face whose passive PAD score is not below {@code pad.reject_threshold}.
 * Grants and refusals are audited as {@code REG-EVT-LV16} with both user IDs and score buckets.
 */
public class LivenessRunOverrideTest {

	private static final String RID = "10001100010003";
	private static final String OPERATOR = "officer1";
	private static final String OTHER_SUPERVISOR = "supervisor2";
	private static final List<String> SUPERVISOR_ROLES = Arrays.asList(Role.REGISTRATION_SUPERVISOR.name(),
			Role.REGISTRATION_OFFICER.name());

	private final Map<String, Object> values = new HashMap<>();
	private final TestClock clock = new TestClock();
	private final RecordingAudit audit = new RecordingAudit();
	private final LivenessTokenStore tokens = new LivenessTokenStore();
	private final SyntheticDevice device = new SyntheticDevice();
	private final RecordingListener listener = new RecordingListener();
	private final PhotoModels photo = new PhotoModels();
	private ScriptedEngine engine;
	private LivenessOrchestrator orchestrator;
	private LivenessRun run;

	@Before
	public void setUp() {
		values.put("mosip.registration.liveness.max_retries", "2");
		values.put("mosip.registration.liveness.on_max_failure", "SUPERVISOR_OVERRIDE");
		engine = new ScriptedEngine(clock, Script.FAIL_TIMEOUT);
		LivenessConfig config = LivenessConfig.of(values);
		LivenessEngineRegistry registry = new LivenessEngineRegistry(config, new DesktopImageDecoder(),
				new DesktopAuditSink(Mockito.mock(AuditManagerService.class), Runnable::run),
				new DesktopDiagnosticSink()) {
			@Override
			public io.mosip.liveness.core.api.LivenessEngine engine() throws EngineException {
				return engine;
			}
		};
		orchestrator = new LivenessOrchestrator(config, registry, tokens, audit, record -> {
		}, null, clock);
		orchestrator.setUserIdSupplier(() -> OPERATOR);
		orchestrator.setOverridePhotoModels(photo);
	}

	@After
	public void tearDown() {
		if (run != null) {
			run.close();
		}
	}

	@Test
	public void sameUserRefused() throws Exception {
		failAllAttempts();
		// the logged-in operator is a supervisor and authenticated a moment ago: still one person only
		assertRefused(SupervisorApproval.of(OPERATOR, SUPERVISOR_ROLES, clock.now()),
				OverrideRefusedException.Reason.SAME_USER);
		// the user ID is compared without regard to case
		assertRefused(SupervisorApproval.of("OFFICER1", SUPERVISOR_ROLES, clock.now()),
				OverrideRefusedException.Reason.SAME_USER);
	}

	@Test
	public void nonSupervisorRefused() throws Exception {
		failAllAttempts();
		assertRefused(SupervisorApproval.of("officer2", Collections.singletonList(Role.REGISTRATION_OFFICER.name()),
				clock.now()), OverrideRefusedException.Reason.NOT_SUPERVISOR);
		assertRefused(SupervisorApproval.of("someone", null, clock.now()),
				OverrideRefusedException.Reason.NOT_SUPERVISOR);
	}

	@Test
	public void padFailingPhotoRefused() throws Exception {
		failAllAttempts();
		photo.padScore = 12;
		LivenessContext context = run.grantOverride(approvalNow());
		BiometricsDto face = new BiometricsDto("face", LivenessGateTest.isoFace(), 90);
		try {
			capture(context, face);
			fail("a photo below pad.reject_threshold must be refused");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_PAD_FAILED.getErrorCode(), e.getErrorCode());
		}
		assertNull("nothing stored for the packet", face.getLivenessEvidence());
		assertEquals(1, photo.analysed.get());
		LivenessAuditEvent event = singleOverrideEvent();
		assertEquals("REFUSED", event.attributes().get(LivenessRun.AUDIT_DECISION));
		assertEquals("CAPTURE_PAD_SCORE", event.attributes().get(AuditAttributes.REASON));
		assertEquals("10-19", event.attributes().get(AuditAttributes.SCORE_BUCKET));
		assertBothUsers(event);
	}

	@Test
	public void twoFacePhotoRefused() throws Exception {
		failAllAttempts();
		photo.faces = 2;
		LivenessContext context = run.grantOverride(approvalNow());
		BiometricsDto face = new BiometricsDto("face", LivenessGateTest.isoFace(), 90);
		try {
			capture(context, face);
			fail("a photo with two faces must be refused");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_BINDING_FAILED.getErrorCode(), e.getErrorCode());
		}
		assertNull(face.getLivenessEvidence());
		LivenessAuditEvent event = singleOverrideEvent();
		assertEquals("REFUSED", event.attributes().get(LivenessRun.AUDIT_DECISION));
		assertEquals("CAPTURE_MULTI_FACE", event.attributes().get(AuditAttributes.REASON));
		assertEquals("2", event.attributes().get(LivenessRun.AUDIT_FACES));
		assertBothUsers(event);
	}

	@Test
	public void otherSupervisorWithCleanPhotoAllowed() throws Exception {
		failAllAttempts();
		LivenessContext context = run.grantOverride(approvalNow());
		BiometricsDto face = new BiometricsDto("face", LivenessGateTest.isoFace(), 90);
		capture(context, face);

		assertEquals(PacketEvidenceKeys.DECISION_OVERRIDE, face.getLivenessEvidence().get(PacketEvidenceKeys.DECISION));
		assertEquals(1, photo.analysed.get());
		LivenessAuditEvent event = singleOverrideEvent();
		assertEquals("GRANTED", event.attributes().get(LivenessRun.AUDIT_DECISION));
		assertEquals("90-100", event.attributes().get(AuditAttributes.SCORE_BUCKET));
		assertEquals("1", event.attributes().get(LivenessRun.AUDIT_FACES));
		assertBothUsers(event);
		assertFalse("only buckets in the audit trail", event.descriptionJson().contains("\"92\""));
	}

	@Test
	public void noFacePhotoRefused() throws Exception {
		failAllAttempts();
		photo.faces = 0;
		LivenessContext context = run.grantOverride(approvalNow());
		BiometricsDto face = new BiometricsDto("face", LivenessGateTest.isoFace(), 90);
		try {
			capture(context, face);
			fail("a photo without a face must be refused");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_BINDING_FAILED.getErrorCode(), e.getErrorCode());
		}
		assertNull(face.getLivenessEvidence());
		LivenessAuditEvent event = singleOverrideEvent();
		assertEquals("REFUSED", event.attributes().get(LivenessRun.AUDIT_DECISION));
		assertEquals("CAPTURE_NO_FACE", event.attributes().get(AuditAttributes.REASON));
		assertEquals("0", event.attributes().get(LivenessRun.AUDIT_FACES));
	}

	@Test
	public void engineWithoutSingleImageCheckRefusesThePhoto() throws Exception {
		// the scripted engine is not the ONNX engine, so the default lookup finds no models: fail closed
		orchestrator.setOverridePhotoModels(OverridePhotoCheck.ENGINE_MODELS);
		failAllAttempts();
		LivenessContext context = run.grantOverride(approvalNow());
		BiometricsDto face = new BiometricsDto("face", LivenessGateTest.isoFace(), 90);
		try {
			capture(context, face);
			fail("an override photo that cannot be checked must be refused");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_BINDING_FAILED.getErrorCode(), e.getErrorCode());
		}
		assertNull(face.getLivenessEvidence());
		LivenessAuditEvent event = singleOverrideEvent();
		assertEquals("REFUSED", event.attributes().get(LivenessRun.AUDIT_DECISION));
		assertEquals("ENGINE_ERROR", event.attributes().get(AuditAttributes.REASON));
		assertFalse("no score bucket without a score", event.attributes().containsKey(AuditAttributes.SCORE_BUCKET));
	}

	@Test
	public void authenticationOlderThanTheOverrideValidityRefused() throws Exception {
		failAllAttempts();
		Instant authenticated = clock.now().minusMillis(LivenessRun.OVERRIDE_VALIDITY_MS + 1_000L);
		assertRefused(SupervisorApproval.of(OTHER_SUPERVISOR, SUPERVISOR_ROLES, authenticated),
				OverrideRefusedException.Reason.AUTH_EXPIRED);
	}

	@Test
	public void requestSurvivesTheSupervisorsOwnLivenessRunAndGrantsOnce() throws Exception {
		failAllAttempts();
		LivenessRun.OverrideRequest request = run.requestOverride();
		// the supervisor's face authentication opens another liveness run, which closes the resident's run
		run.close();
		LivenessContext context = request.grant(approvalNow());
		assertTrue(context.getToken().isPresent());
		try {
			request.grant(approvalNow());
			fail("an override request grants once");
		} catch (IllegalStateException expected) {
			// expected
		}
		try {
			run.requestOverride();
			fail("a closed run starts no new override");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	@Test
	public void suspectedAttackKeepsTheOverrideClosedForAnotherSupervisor() throws Exception {
		engine.scripts.clear();
		Collections.addAll(engine.scripts, Script.FAIL_PAD, Script.FAIL_TIMEOUT);
		failAllAttempts();
		assertFalse(listener.last().isOverrideAllowed());
		try {
			run.grantOverride(approvalNow());
			fail("no override after a suspected attack, whoever approves");
		} catch (IllegalStateException expected) {
			assertFalse(expected instanceof OverrideRefusedException);
		}
		assertEquals(0, photo.analysed.get());
	}

	private SupervisorApproval approvalNow() {
		return SupervisorApproval.of(OTHER_SUPERVISOR, SUPERVISOR_ROLES, clock.now());
	}

	private void assertRefused(SupervisorApproval approval, OverrideRefusedException.Reason reason) {
		int before = overrideEvents().size();
		try {
			run.grantOverride(approval);
			fail("override must be refused: " + reason);
		} catch (OverrideRefusedException e) {
			assertEquals(reason, e.reason());
		}
		List<LivenessAuditEvent> events = overrideEvents();
		assertEquals("one audit event per refusal", before + 1, events.size());
		LivenessAuditEvent event = events.get(events.size() - 1);
		assertEquals("REFUSED", event.attributes().get(LivenessRun.AUDIT_DECISION));
		assertEquals(reason.name(), event.attributes().get(AuditAttributes.REASON));
		assertEquals(OPERATOR, event.attributes().get(AuditAttributes.USER_ID));
		assertEquals(approval.supervisorId(), event.attributes().get(AuditAttributes.SUPERVISOR_ID));
		assertEquals("no photo was checked", 0, photo.analysed.get());
	}

	private static void assertBothUsers(LivenessAuditEvent event) {
		assertEquals(OPERATOR, event.attributes().get(AuditAttributes.USER_ID));
		assertEquals(OTHER_SUPERVISOR, event.attributes().get(AuditAttributes.SUPERVISOR_ID));
	}

	private LivenessAuditEvent singleOverrideEvent() {
		List<LivenessAuditEvent> events = overrideEvents();
		assertEquals("exactly one REG-EVT-LV16", 1, events.size());
		return events.get(0);
	}

	private List<LivenessAuditEvent> overrideEvents() {
		List<LivenessAuditEvent> found = new ArrayList<>();
		for (LivenessAuditEvent e : audit.events) {
			if (e.eventId() == AuditEventId.LIVENESS_OVERRIDE_USED) {
				found.add(e);
			}
		}
		return found;
	}

	private void failAllAttempts() throws Exception {
		run = orchestrator.start(LivenessRequest.builder(Workflow.REGISTRATION, RID).device(device).build(), listener);
		assertTrue(listener.awaitEnded());
		listener.reset();
		run.retry();
		assertTrue(listener.awaitEnded());
	}

	private void capture(LivenessContext context, BiometricsDto face) throws RegBaseCheckedException {
		LivenessGate gate = new LivenessGate(LivenessConfig.of(values), tokens, new DesktopImageDecoder(), audit,
				clock);
		MDMRequestDto request = new MDMRequestDto(RegistrationConstants.FACE_FULLFACE, null, "Registration", "test",
				10000, 1, 80);
		request.setLivenessContext(context);
		LivenessGate.Admission admission = gate.admit(request, mdmDevice());
		face.setPayLoad("{\"timestamp\":\"" + Instant.now().toString() + "\",\"transactionId\":\"t1\"}");
		List<BiometricsDto> captures = new ArrayList<>();
		captures.add(face);
		gate.complete(admission, captures);
	}

	private static MdmBioDevice mdmDevice() {
		MdmBioDevice d = new MdmBioDevice();
		d.setSerialNumber(LivenessFakes.SERIAL);
		d.setDeviceId("dev-1");
		d.setCertification("L0");
		d.setSpecVersion("0.9.5");
		return d;
	}
}
