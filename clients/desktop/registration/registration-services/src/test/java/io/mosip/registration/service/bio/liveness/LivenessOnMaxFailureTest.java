package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.ArrayList;
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
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.enums.Role;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.mdm.dto.MDMRequestDto;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingAudit;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingListener;
import io.mosip.registration.service.bio.liveness.LivenessFakes.Script;
import io.mosip.registration.service.bio.liveness.LivenessFakes.ScriptedEngine;
import io.mosip.registration.service.bio.liveness.LivenessFakes.SyntheticDevice;
import io.mosip.registration.service.bio.liveness.LivenessFakes.TestClock;

/**
 * Tests the behaviour after the last failed attempt ({@code <workflow>.on_max_failure}, PS04 8.9) end to end on
 * the desktop services: {@code BLOCK}, {@code SUPERVISOR_OVERRIDE} with the override recorded in the packet
 * evidence, {@code BLOCK_AND_LOCK} for authentication, and the diagnostic mode audit ({@code REG-EVT-LV17}).
 */
public class LivenessOnMaxFailureTest {

	private static final String RID = "10001100010002";

	private final Map<String, Object> values = new HashMap<>();
	private final TestClock clock = new TestClock();
	private final RecordingAudit audit = new RecordingAudit();
	private final LivenessTokenStore tokens = new LivenessTokenStore();
	private final SyntheticDevice device = new SyntheticDevice();
	private final RecordingListener listener = new RecordingListener();
	private ScriptedEngine engine;
	private LivenessOrchestrator orchestrator;
	private LivenessRun run;

	@Before
	public void setUp() {
		values.put("mosip.registration.liveness.max_retries", "2");
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
		orchestrator.setUserIdSupplier(() -> "supervisor1");
		orchestrator.setOverridePhotoModels(new LivenessFakes.PhotoModels());
	}

	@After
	public void tearDown() {
		if (run != null) {
			run.close();
		}
	}

	@Test
	public void blockEndsTheTransactionWithoutOverrideOrLock() throws Exception {
		LivenessOutcome last = failAllAttempts(Workflow.REGISTRATION, RID);
		assertEquals(LivenessTransaction.Status.EXHAUSTED, last.status());
		assertFalse(last.isRetryAllowed());
		assertFalse("BLOCK offers no override", last.isOverrideAllowed());
		assertFalse("BLOCK never locks", last.isLockRequired());
		try {
			run.grantOverride(SupervisorApproval.of("supervisor2",
					Collections.singletonList(Role.REGISTRATION_SUPERVISOR.name()), clock.now()));
			fail("no override under BLOCK");
		} catch (IllegalStateException expected) {
			// expected
		}
		assertTrue(orchestrator.isBlocked(Workflow.REGISTRATION, RID));
	}

	@Test
	public void supervisorOverrideIsFlaggedInThePacketEvidenceAndAudited() throws Exception {
		values.put("mosip.registration.liveness.on_max_failure", "SUPERVISOR_OVERRIDE");
		LivenessOutcome first = failOnce(Workflow.REGISTRATION, RID);
		assertFalse("no override before the last attempt", first.isOverrideAllowed());
		listener.reset();
		run.retry();
		assertTrue(listener.awaitEnded());
		assertTrue(listener.last().isOverrideAllowed());

		// a second supervisor (the operator here is supervisor1) approves; the photo shows one face, PAD 92
		LivenessContext context = run.grantOverride(SupervisorApproval.of("supervisor2",
				Collections.singletonList(Role.REGISTRATION_SUPERVISOR.name()), clock.now()));
		LivenessGate gate = new LivenessGate(LivenessConfig.of(values), tokens, new DesktopImageDecoder(), audit,
				clock);
		MDMRequestDto request = new MDMRequestDto(RegistrationConstants.FACE_FULLFACE, null, "Registration", "test",
				10000, 1, 80);
		request.setLivenessContext(context);
		LivenessGate.Admission admission = gate.admit(request, mdmDevice());
		BiometricsDto face = new BiometricsDto("face", LivenessGateTest.isoFace(), 90);
		face.setPayLoad("{\"timestamp\":\"" + Instant.now().toString() + "\",\"transactionId\":\"t1\"}");
		List<BiometricsDto> captures = new ArrayList<>();
		captures.add(face);
		gate.complete(admission, captures);

		Map<String, String> evidence = face.getLivenessEvidence();
		assertEquals(PacketEvidenceKeys.DECISION_OVERRIDE, evidence.get(PacketEvidenceKeys.DECISION));
		assertTrue(audit.has(AuditEventId.LIVENESS_OVERRIDE_USED.id()));
		assertTrue("LV11 records on_max_failure", eventWith(AuditEventId.LIVENESS_MAX_RETRIES, "SUPERVISOR_OVERRIDE"));
	}

	@Test
	public void supervisorOverrideIsNotOfferedInAuthenticationWorkflows() throws Exception {
		values.put("mosip.registration.liveness.on_max_failure", "SUPERVISOR_OVERRIDE");
		LivenessOutcome last = failAllAttempts(Workflow.PACKET_AUTH, "officer1");
		assertFalse(last.isOverrideAllowed());
		assertFalse(last.isLockRequired());
	}

	@Test
	public void blockAndLockAsksForTheAccountLockAfterTheLastAttemptOnly() throws Exception {
		values.put("mosip.registration.liveness.login_auth.on_max_failure", "BLOCK_AND_LOCK");
		LivenessOutcome first = failOnce(Workflow.LOGIN_AUTH, "officer1");
		assertFalse("no lock while attempts remain", first.isLockRequired());
		listener.reset();
		run.retry();
		assertTrue(listener.awaitEnded());
		LivenessOutcome last = listener.last();
		assertTrue(last.isLockRequired());
		assertFalse(last.isOverrideAllowed());
		assertTrue(orchestrator.isBlocked(Workflow.LOGIN_AUTH, "officer1"));
	}

	@Test
	public void blockAndLockIsIgnoredForResidents() throws Exception {
		values.put("mosip.registration.liveness.registration.on_max_failure", "BLOCK_AND_LOCK");
		LivenessOutcome last = failAllAttempts(Workflow.REGISTRATION, RID);
		assertFalse("residents have no account to lock", last.isLockRequired());
	}

	@Test
	public void diagnosticModeSwitchIsAuditedOncePerChange() throws Exception {
		run = orchestrator.start(request(Workflow.REGISTRATION, RID), listener);
		run.close();
		assertEquals("diagnostics off by default: no LV17", 0, count(AuditEventId.LIVENESS_DIAGNOSTIC_MODE));

		values.put("mosip.registration.liveness.diagnostic.enable", "Y");
		run = orchestrator.start(request(Workflow.REGISTRATION, RID + "1"), new RecordingListener());
		run.close();
		run = orchestrator.start(request(Workflow.REGISTRATION, RID + "2"), new RecordingListener());
		run.close();
		assertEquals(1, count(AuditEventId.LIVENESS_DIAGNOSTIC_MODE));
		assertTrue(eventWith(AuditEventId.LIVENESS_DIAGNOSTIC_MODE, "supervisor1"));
		assertTrue(run.policy().isDiagnosticEnabled());

		values.put("mosip.registration.liveness.diagnostic.enable", "N");
		run = orchestrator.start(request(Workflow.REGISTRATION, RID + "3"), new RecordingListener());
		assertEquals(2, count(AuditEventId.LIVENESS_DIAGNOSTIC_MODE));
	}

	@Test
	public void diagnosticTapKeepsTheLatestRecord() {
		List<io.mosip.liveness.core.audit.DiagnosticRecord> logged = new ArrayList<>();
		DiagnosticTap tap = new DiagnosticTap(logged::add);
		assertFalse(tap.last().isPresent());
		io.mosip.liveness.core.audit.DiagnosticRecord record = io.mosip.liveness.core.audit.DiagnosticRecord
				.builder("s1", io.mosip.liveness.core.SessionState.PASSIVE, 120).padScore(84).build();
		tap.log(record);
		tap.log(null);
		assertEquals(record, tap.last().get());
		assertEquals(1, logged.size());
		assertFalse("no image field in the log line", record.toLogLine().contains("image"));
	}

	private LivenessOutcome failOnce(Workflow workflow, String subject) throws Exception {
		run = orchestrator.start(request(workflow, subject), listener);
		assertTrue(listener.awaitEnded());
		return listener.last();
	}

	private LivenessOutcome failAllAttempts(Workflow workflow, String subject) throws Exception {
		failOnce(workflow, subject);
		listener.reset();
		run.retry();
		assertTrue(listener.awaitEnded());
		return listener.last();
	}

	private LivenessRequest request(Workflow workflow, String subject) {
		return LivenessRequest.builder(workflow, subject).device(device).build();
	}

	private static MdmBioDevice mdmDevice() {
		MdmBioDevice d = new MdmBioDevice();
		d.setSerialNumber(LivenessFakes.SERIAL);
		d.setDeviceId("dev-1");
		d.setCertification("L0");
		d.setSpecVersion("0.9.5");
		return d;
	}

	private long count(AuditEventId id) {
		return audit.events.stream().filter(e -> e.eventId() == id).count();
	}

	private boolean eventWith(AuditEventId id, String text) {
		for (LivenessAuditEvent e : audit.events) {
			if (e.eventId() == id && e.descriptionJson().contains(text)) {
				return true;
			}
		}
		return false;
	}
}
