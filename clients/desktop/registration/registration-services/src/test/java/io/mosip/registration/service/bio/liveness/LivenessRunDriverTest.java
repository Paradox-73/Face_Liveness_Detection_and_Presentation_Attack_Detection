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

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.audit.AuditManagerService;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.LivenessContext;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingAudit;
import io.mosip.registration.service.bio.liveness.LivenessFakes.RecordingListener;
import io.mosip.registration.service.bio.liveness.LivenessFakes.Script;
import io.mosip.registration.service.bio.liveness.LivenessFakes.ScriptedEngine;
import io.mosip.registration.service.bio.liveness.LivenessFakes.SyntheticDevice;
import io.mosip.registration.service.bio.liveness.LivenessFakes.TestClock;

/**
 * Tests what {@link LivenessRun} adds to the shared session driver of {@code liveness-core}: the starting state of
 * each attempt, the attempt number and the {@code REG-LIV-*} error codes on the outcome, a screen that fails in
 * capture ready, the refused retry while a session runs, and the low-light ending.
 */
public class LivenessRunDriverTest {

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
		values.put("mosip.registration.liveness.max_retries", "3");
		engine = new ScriptedEngine(clock, Script.PASS);
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
		orchestrator.setUserIdSupplier(() -> "officer1");
	}

	@After
	public void tearDown() {
		if (run != null) {
			run.close();
		}
	}

	private static BindingRequest bindingFor(LivenessPassToken token) {
		ImageBuffer image = ImageBuffer.ofBytes(4, 4, PixelFormat.BGR888, new byte[48]);
		return BindingRequest.builder(image, token.issuedAt(), token.deviceSerial()).token(token).build();
	}

	private static LivenessOutcome ended(RecordingListener l) throws InterruptedException {
		assertTrue("session ended", l.awaitEnded());
		LivenessOutcome o = l.last();
		l.reset();
		return o;
	}

	@Test
	public void everyAttemptStartsWithTheStartingState() throws Exception {
		engine.scripts.clear();
		engine.scripts.add(Script.FAIL_TIMEOUT);
		run = orchestrator.start(LivenessRequest.builder(Workflow.REGISTRATION, RID).device(device).build(), listener);
		ended(listener);
		assertEquals(UiPhase.STARTING, listener.states.get(0).phase());
		int before = listener.states.size();
		run.retry();
		ended(listener);
		LivenessUiState retryFirst = listener.states.get(before);
		assertEquals("the retry starts with the starting state", UiPhase.STARTING, retryFirst.phase());
		assertEquals(2, retryFirst.attempt());
	}

	@Test
	public void outcomeCarriesTheAttemptNumberAcrossWindows() throws Exception {
		engine.scripts.clear();
		engine.scripts.add(Script.FAIL_TIMEOUT);
		run = orchestrator.start(LivenessRequest.builder(Workflow.REGISTRATION, RID).device(device).build(), listener);
		LivenessOutcome first = ended(listener);
		assertEquals(1, first.attempt());
		run.close();

		RecordingListener second = new RecordingListener();
		run = orchestrator.start(LivenessRequest.builder(Workflow.REGISTRATION, RID).device(device).build(), second);
		LivenessOutcome next = ended(second);
		assertEquals("the second window continues at attempt 2", 2, next.attempt());
		assertEquals(2, next.attemptsUsed());
		assertEquals(2, engine.sessions.get(1).request.attempt());
	}

	@Test
	public void missingDeviceEndsWithTheDeviceErrorCodeAndAllowsRetry() throws Exception {
		run = orchestrator.start(LivenessRequest.builder(Workflow.REGISTRATION, RID).build(), listener);
		LivenessOutcome o = ended(listener);
		assertEquals(Optional.of(FailureReason.DEVICE), o.failureReason());
		assertEquals(Optional.of(RegistrationExceptionConstants.LIVENESS_DEVICE_ERROR.getErrorCode()), o.errorCode());
		assertFalse(o.result().isPresent());
		assertEquals("a device failure does not use up an attempt", 0, o.attemptsUsed());
		assertTrue(o.isRetryAllowed());
		assertTrue(audit.has(AuditEventId.LIVENESS_DEVICE_ERROR.id()));
		assertEquals("no session was opened", 0, engine.sessions.size());
		assertEquals(UiPhase.DEVICE_ERROR, listener.states.get(listener.states.size() - 1).phase());
	}

	@Test
	public void failureInsideASessionHasNoClientErrorCode() throws Exception {
		device.failOpen = true;
		run = orchestrator.start(LivenessRequest.builder(Workflow.REGISTRATION, RID).device(device).build(), listener);
		LivenessOutcome o = ended(listener);
		assertEquals(Optional.of(FailureReason.DEVICE), o.failureReason());
		assertFalse(o.errorCode().isPresent());
		assertTrue(o.result().isPresent());
	}

	@Test
	public void screenFailingAtCaptureReadyDoesNotEndThePass() throws Exception {
		LivenessRunListener failing = new LivenessRunListener() {
			@Override
			public void onUiState(LivenessUiState state) {
				listener.onUiState(state);
			}

			@Override
			public void onPreviewFrame(io.mosip.liveness.core.device.FaceFrame frame) {
				listener.onPreviewFrame(frame);
			}

			@Override
			public void onCaptureReady(LivenessContext context) {
				listener.onCaptureReady(context);
				throw new IllegalStateException("screen failed");
			}

			@Override
			public void onSessionEnded(LivenessOutcome outcome) {
				listener.onSessionEnded(outcome);
			}
		};
		run = orchestrator.start(LivenessRequest.builder(Workflow.REGISTRATION, RID).device(device).build(), failing);
		assertTrue(listener.awaitReady());
		LivenessPassToken token = listener.captureContext.getToken().get();
		assertTrue(run.isSessionActive());
		LivenessTokenStore.BindOutcome bound = tokens.consume(token, clock.now()).bind(bindingFor(token), 2_000);
		assertTrue(bound.verdict().isPassed());
		assertTrue(ended(listener).isPassed());
	}

	@Test
	public void runTracksLowLightAndTheFourthDimTryCounts() throws Exception {
		engine.scripts.clear();
		engine.scripts.add(Script.DIM_FACE);
		run = orchestrator.start(LivenessRequest.builder(Workflow.REGISTRATION, RID).device(device).build(), listener);
		for (int i = 0; i < 3; i++) {
			LivenessOutcome dim = ended(listener);
			assertEquals("session " + i + " gets the low-light count", i,
					engine.sessions.get(i).request.lowLightRetries());
			assertTrue(dim.isLowLightRetry());
			assertEquals(i + 1, dim.lowLightRetries());
			assertEquals(0, dim.attemptsUsed());
			assertEquals(1, dim.attempt());
			LivenessUiState shown = listener.states.get(listener.states.size() - 1);
			assertEquals(MessageKeys.FAIL_LIVENESS, shown.messageKey());
			assertTrue(shown.guidanceKeys().contains(MessageKeys.GUIDE_LIGHTING));
			assertTrue(dim.toString().contains("lowLight=" + (i + 1)));
			run.retry();
		}
		LivenessOutcome counted = ended(listener);
		assertEquals(3, engine.sessions.get(3).request.lowLightRetries());
		assertEquals(Optional.of(FailureReason.PAD), counted.failureReason());
		assertEquals(1, counted.attempt());
		assertEquals(1, counted.attemptsUsed());
		assertEquals(0, counted.lowLightRetries());
		assertEquals(MessageKeys.FAIL_PAD, listener.states.get(listener.states.size() - 1).messageKey());
	}

	@Test
	public void retryWhileTheSessionRunsIsRefused() throws Exception {
		run = orchestrator.start(LivenessRequest.builder(Workflow.REGISTRATION, RID).device(device).build(), listener);
		assertTrue(listener.awaitReady());
		try {
			run.retry();
			fail("no retry while a session runs");
		} catch (IllegalStateException expected) {
			// expected
		}
		assertNotNull(run.captureContext().orElse(null));
		run.cancel();
		assertEquals(Optional.of(FailureReason.CANCELLED), ended(listener).failureReason());
		assertFalse("the capture context ends with the session", run.captureContext().isPresent());
	}
}
