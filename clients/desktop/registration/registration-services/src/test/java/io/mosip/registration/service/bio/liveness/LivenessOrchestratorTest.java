package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingAudit;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingListener;
import io.mosip.registration.service.bio.liveness.LivenessFakes.Script;
import io.mosip.registration.service.bio.liveness.LivenessFakes.ScriptedEngine;
import io.mosip.registration.service.bio.liveness.LivenessFakes.SyntheticDevice;
import io.mosip.registration.service.bio.liveness.LivenessFakes.TestClock;

/**
 * Tests {@link LivenessOrchestrator} and {@link LivenessRun} with a scripted engine and a synthetic stream: the
 * threading contract, capture ready, token registration, binding, retries, exhaustion and cancel.
 */
public class LivenessOrchestratorTest {

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
		engine = new ScriptedEngine(clock, Script.PASS);
		orchestrator = orchestrator(engine);
	}

	@After
	public void tearDown() {
		if (run != null) {
			run.close();
		}
	}

	private LivenessOrchestrator orchestrator(ScriptedEngine e) {
		LivenessConfig config = LivenessConfig.of(values);
		LivenessEngineRegistry registry = new LivenessEngineRegistry(config, new DesktopImageDecoder(),
				new DesktopAuditSink(Mockito.mock(AuditManagerService.class), Runnable::run),
				new DesktopDiagnosticSink()) {
			@Override
			public io.mosip.liveness.core.api.LivenessEngine engine() throws EngineException {
				if (e == null) {
					throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "bundle signature invalid");
				}
				return e;
			}
		};
		return new LivenessOrchestrator(config, registry, tokens, audit, record -> {
		}, null, clock);
	}

	private LivenessRequest request(Workflow workflow) {
		return LivenessRequest.builder(workflow, "10001100010002").device(device).build();
	}

	private static BindingRequest bindingFor(LivenessPassToken token) {
		ImageBuffer image = ImageBuffer.ofBytes(4, 4, PixelFormat.BGR888, new byte[48]);
		return BindingRequest.builder(image, token.issuedAt(), token.deviceSerial()).token(token).build();
	}

	@Test
	public void residentPassRegistersTokenAndBindsCapture() throws Exception {
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue("capture ready", listener.awaitReady());

		LivenessContext context = listener.captureContext;
		assertNotNull(context);
		assertEquals(Workflow.REGISTRATION, context.getWorkflow());
		assertTrue(context.getToken().isPresent());
		assertEquals(context.getToken(),
				orchestrator.pendingContext(Workflow.REGISTRATION, "10001100010002").get().getToken());
		assertFalse(orchestrator.pendingContext(Workflow.REGISTRATION, "someone-else").isPresent());
		assertFalse(orchestrator.contextFor(Workflow.LOGIN_AUTH, "officer1").getToken().isPresent());
		assertTrue(listener.previews.get() > 0);
		assertFalse(listener.states.isEmpty());
		assertEquals("resident stream stays open during capture", 0, device.streamsClosed.get());

		LivenessPassToken token = context.getToken().get();
		LivenessTokenStore.PassHandle handle = tokens.consume(token, clock.now());
		LivenessTokenStore.BindOutcome outcome = handle.bind(bindingFor(token), 2_000);
		assertTrue(outcome.verdict().isPassed());
		assertTrue(outcome.result().isPresent());

		assertTrue("session ended", listener.awaitEnded());
		LivenessOutcome end = listener.last();
		assertTrue(end.isPassed());
		assertEquals(LivenessTransaction.Status.PASSED, end.status());
		assertEquals(1, device.streamsClosed.get());
		assertFalse(run.isSessionActive());
	}

	@Test
	public void framesReachSessionInOrderOnOneWorkerThread() throws Exception {
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitReady());
		java.util.List<Long> seq = engine.sessions.get(0).sequences;
		for (int i = 1; i < seq.size(); i++) {
			assertTrue("frames must never go backwards", seq.get(i) > seq.get(i - 1));
		}
	}

	@Test
	public void authPassClosesStreamAndWaitsForReadyBeforeCapture() throws Exception {
		run = orchestrator.start(request(Workflow.LOGIN_AUTH), listener);
		assertTrue(listener.awaitReady());
		assertEquals("auth stream closed before capture", 1, device.streamsClosed.get());
		assertEquals(Workflow.LOGIN_AUTH, listener.captureContext.getWorkflow());
	}

	@Test
	public void authCaptureFailsWhenDeviceNeverReportsReady() throws Exception {
		device.status = io.mosip.liveness.core.device.DeviceStatus.NOT_READY;
		run = orchestrator.start(request(Workflow.PACKET_AUTH), listener);
		assertTrue(listener.awaitEnded());
		assertEquals(Optional.of(FailureReason.DEVICE), listener.last().failureReason());
		assertEquals(null, listener.captureContext);
	}

	@Test
	public void maxRetriesIsAuditedOnceWhenTheEngineReportsIt() throws Exception {
		engine.scripts.clear();
		engine.scripts.add(Script.FAIL_TIMEOUT);
		engine.auditsMaxRetries = true;
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitEnded());
		listener.reset();
		run.retry();
		assertTrue(listener.awaitEnded());

		assertEquals(LivenessTransaction.Status.EXHAUSTED, listener.last().status());
		long lv11 = audit.events.stream().filter(e -> e.eventId() == AuditEventId.LIVENESS_MAX_RETRIES).count();
		assertEquals("REG-EVT-LV11 exactly once", 1, lv11);
	}

	@Test
	public void failureAllowsRetryUntilAttemptsAreUsed() throws Exception {
		engine.scripts.clear();
		engine.scripts.add(Script.FAIL_TIMEOUT);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitEnded());
		LivenessOutcome first = listener.last();
		assertFalse(first.isPassed());
		assertEquals(1, first.attemptsUsed());
		assertTrue(first.isRetryAllowed());

		listener.reset();
		run.retry();
		assertTrue(listener.awaitEnded());
		LivenessOutcome second = listener.last();
		assertEquals(2, second.attemptsUsed());
		assertFalse(second.isRetryAllowed());
		assertEquals(LivenessTransaction.Status.EXHAUSTED, second.status());
		assertTrue(audit.has(AuditEventId.LIVENESS_MAX_RETRIES.id()));
		try {
			run.retry();
			fail("no retry after the last attempt");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	@Test
	public void exhaustedTransactionBlocksANewRunForTheSamePerson() throws Exception {
		engine.scripts.clear();
		engine.scripts.add(Script.FAIL_TIMEOUT);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitEnded());
		listener.reset();
		run.retry();
		assertTrue(listener.awaitEnded());
		assertEquals(LivenessTransaction.Status.EXHAUSTED, listener.last().status());
		run.close();

		assertTrue(orchestrator.isBlocked(Workflow.REGISTRATION, "10001100010002"));
		try {
			orchestrator.start(request(Workflow.REGISTRATION), new RecordingListener());
			fail("closing and reopening the window must not give new attempts");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_MAX_ATTEMPTS.getErrorCode(), e.getErrorCode());
		}
		assertFalse("another workflow is not blocked", orchestrator.isBlocked(Workflow.PACKET_AUTH, "10001100010002"));
		assertFalse("another person is not blocked", orchestrator.isBlocked(Workflow.REGISTRATION, "10001100010003"));

		clock.advance((LivenessConfig.RESIDENT_BLOCK_MINUTES + 1) * 60_000L);
		assertFalse("the block expires", orchestrator.isBlocked(Workflow.REGISTRATION, "10001100010002"));
	}

	@Test
	public void authBlockFollowsTheInvalidLoginTime() {
		values.put(LivenessConfig.INVALID_LOGIN_TIME_KEY, "5");
		LivenessConfig config = LivenessConfig.of(values);
		assertEquals(5, config.exhaustedBlockMinutes(Workflow.LOGIN_AUTH));
		values.put(LivenessConfig.INVALID_LOGIN_TIME_KEY, "not-a-number");
		assertEquals(LivenessConfig.DEFAULT_AUTH_BLOCK_MINUTES, config.exhaustedBlockMinutes(Workflow.EOD_AUTH));
		assertEquals(LivenessConfig.RESIDENT_BLOCK_MINUTES, config.exhaustedBlockMinutes(Workflow.REGISTRATION));
	}

	@Test
	public void secondStartWhileSessionRunsIsRefused() throws Exception {
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitReady());
		try {
			orchestrator.start(request(Workflow.REGISTRATION), new RecordingListener());
			fail("busy");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_BUSY.getErrorCode(), e.getErrorCode());
		}
	}

	@Test
	public void cancelEndsSessionAndRevokesToken() throws Exception {
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitReady());
		LivenessPassToken token = listener.captureContext.getToken().get();
		run.cancel();
		assertTrue(listener.awaitEnded());
		assertEquals(Optional.of(FailureReason.CANCELLED), listener.last().failureReason());
		try {
			tokens.consume(token, clock.now());
			fail("token must be withdrawn after cancel");
		} catch (LivenessTokenStore.TokenRefusedException e) {
			assertEquals(LivenessTokenStore.Refusal.REUSED, e.refusal());
		}
	}

	@Test
	public void deviceStreamFailureEndsWithDeviceError() throws Exception {
		device.failOpen = true;
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitEnded());
		assertEquals(Optional.of(FailureReason.DEVICE), listener.last().failureReason());
		assertTrue("device failures do not use up an attempt", listener.last().isRetryAllowed());
	}

	@Test
	public void engineFailureEndsBeforeASessionExists() throws Exception {
		orchestrator = orchestrator(null);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitEnded());
		LivenessOutcome o = listener.last();
		assertEquals(Optional.of(FailureReason.ENGINE), o.failureReason());
		assertEquals(Optional.of(RegistrationExceptionConstants.LIVENESS_ENGINE_ERROR.getErrorCode()), o.errorCode());
		assertFalse("a model integrity failure is final", o.isRetryAllowed());
		assertTrue(audit.has(AuditEventId.LIVENESS_ENGINE_ERROR.id()));
	}

	@Test(expected = IllegalStateException.class)
	public void startIsRefusedWhenPolicyIsOff() throws Exception {
		orchestrator.start(request(Workflow.EXCEPTION_PHOTO), listener);
	}

	@Test
	public void configChangeIsAuditedAtNextStart() throws Exception {
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		run.close();
		values.put("mosip.registration.liveness.passive.threshold", "85");
		run = orchestrator.start(request(Workflow.REGISTRATION), new RecordingListener());
		assertTrue(audit.has(AuditEventId.LIVENESS_CONFIG_APPLIED.id()));
	}

	@Test
	public void overrideIsGrantedOnlyAfterLastResidentFailure() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "1");
		values.put("mosip.registration.liveness.on_max_failure", "SUPERVISOR_OVERRIDE");
		engine.scripts.clear();
		engine.scripts.add(Script.FAIL_TIMEOUT);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitEnded());
		assertTrue(listener.last().isOverrideAllowed());
		LivenessContext override = run.grantOverride("supervisor1");
		LivenessTokenStore.PassHandle handle = tokens.consume(override.getToken().get(), clock.now());
		assertEquals(Optional.of("supervisor1"), handle.overrideSupervisor());
	}

	@Test(expected = IllegalStateException.class)
	public void overrideIsRefusedAfterSuspectedAttack() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "1");
		values.put("mosip.registration.liveness.on_max_failure", "SUPERVISOR_OVERRIDE");
		engine.scripts.clear();
		engine.scripts.add(Script.FAIL_PAD);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitEnded());
		run.grantOverride("supervisor1");
	}

	@Test
	public void closeStopsStreamAndWorker() throws Exception {
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertTrue(listener.awaitReady());
		run.close();
		assertTrue(run.awaitTermination(2_000));
		assertEquals(1, device.streamsClosed.get());
		assertTrue(run.isClosed());
		assertFalse(orchestrator.current().isPresent());
	}
}
