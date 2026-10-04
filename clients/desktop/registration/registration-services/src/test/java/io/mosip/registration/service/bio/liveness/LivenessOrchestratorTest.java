/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
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
		LivenessOrchestrator o = new LivenessOrchestrator(config, registry, tokens, audit, record -> {
		}, null, clock);
		o.setUserIdSupplier(() -> "officer1");
		return o;
	}

	/** A supervisor other than the logged-in operator ({@code officer1}), authenticated now. */
	private SupervisorApproval secondSupervisor() {
		return SupervisorApproval.of("supervisor1",
				java.util.Collections.singletonList(io.mosip.registration.enums.Role.REGISTRATION_SUPERVISOR.name()),
				clock.now());
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
		LivenessContext override = run.grantOverride(secondSupervisor());
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
		run.grantOverride(secondSupervisor());
	}

	// ------------------------------------------------------------------ attempts across liveness windows

	private static final String RID = "10001100010002";

	private void scripts(Script... s) {
		engine.scripts.clear();
		java.util.Collections.addAll(engine.scripts, s);
	}

	/** Waits for the end of the current session of a window and returns its outcome. */
	private static LivenessOutcome ended(RecordingListener l) throws InterruptedException {
		assertTrue("session ended", l.awaitEnded());
		LivenessOutcome o = l.last();
		l.reset();
		return o;
	}

	private int attemptOfSession(int index) {
		return engine.sessions.get(index).request.attempt();
	}

	private void assertStartRefused() throws Exception {
		try {
			run = orchestrator.start(request(Workflow.REGISTRATION), new RecordingListener());
			fail("the attempts are used up");
		} catch (RegBaseCheckedException e) {
			assertEquals(RegistrationExceptionConstants.LIVENESS_MAX_ATTEMPTS.getErrorCode(), e.getErrorCode());
		}
	}

	@Test
	public void padPadCancelContinuesAtTheLastAttemptAndAThirdPadBlocks() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.FAIL_PAD, Script.FAIL_PAD, Script.PASS, Script.FAIL_PAD);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertEquals(1, ended(listener).attemptsUsed());
		run.retry();
		assertEquals(2, ended(listener).attemptsUsed());
		run.retry();
		assertTrue(listener.awaitReady());
		run.cancel();
		LivenessOutcome cancelled = ended(listener);
		assertEquals(Optional.of(FailureReason.CANCELLED), cancelled.failureReason());
		assertEquals("Cancel does not use up an attempt", 2, cancelled.attemptsUsed());
		run.close();

		assertEquals(2, orchestrator.countedFailures(Workflow.REGISTRATION, RID));
		assertFalse(orchestrator.isBlocked(Workflow.REGISTRATION, RID));
		assertEquals("another person starts afresh", 0, orchestrator.countedFailures(Workflow.REGISTRATION,
				"10001100010003"));
		assertEquals("another workflow starts afresh", 0, orchestrator.countedFailures(Workflow.LOGIN_AUTH, RID));

		RecordingListener second = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), second);
		LivenessOutcome third = ended(second);
		assertEquals("the new window starts at attempt 3 of 3", 3, attemptOfSession(3));
		assertEquals(Optional.of(FailureReason.PAD), third.failureReason());
		assertEquals(3, third.attemptsUsed());
		assertEquals(3, third.maxAttempts());
		assertEquals(LivenessTransaction.Status.EXHAUSTED, third.status());
		assertFalse(third.isRetryAllowed());
		assertTrue(audit.has(AuditEventId.LIVENESS_MAX_RETRIES.id()));
		run.close();

		assertTrue(orchestrator.isBlocked(Workflow.REGISTRATION, RID));
		assertStartRefused();
		assertEquals("no session was opened for the refused window", 4, engine.sessions.size());
	}

	@Test
	public void padPadCancelByClosingTheWindowKeepsTheCount() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.FAIL_PAD);
		for (int window = 1; window <= 3; window++) {
			RecordingListener l = new RecordingListener();
			run = orchestrator.start(request(Workflow.REGISTRATION), l);
			LivenessOutcome o = ended(l);
			assertEquals("window " + window, window, o.attemptsUsed());
			assertEquals(window, attemptOfSession(window - 1));
			run.close();
		}
		assertTrue(orchestrator.isBlocked(Workflow.REGISTRATION, RID));
		assertStartRefused();
	}

	@Test
	public void padPadQualityKeepsTheCountAtTwo() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.FAIL_PAD, Script.FAIL_PAD, Script.FAIL_QUALITY, Script.FAIL_QUALITY);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		ended(listener);
		run.retry();
		ended(listener);
		run.retry();
		LivenessOutcome quality = ended(listener);
		assertEquals(Optional.of(FailureReason.QUALITY), quality.failureReason());
		assertEquals("a quality failure does not use up an attempt", 2, quality.attemptsUsed());
		assertTrue(quality.isRetryAllowed());
		run.close();
		assertEquals(2, orchestrator.countedFailures(Workflow.REGISTRATION, RID));

		RecordingListener second = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), second);
		LivenessOutcome again = ended(second);
		assertEquals(3, attemptOfSession(3));
		assertEquals(Optional.of(FailureReason.QUALITY), again.failureReason());
		assertEquals(2, again.attemptsUsed());
		assertTrue(again.isRetryAllowed());
		run.close();
		assertEquals("quality failures never lower or raise the count", 2,
				orchestrator.countedFailures(Workflow.REGISTRATION, RID));
		assertFalse(orchestrator.isBlocked(Workflow.REGISTRATION, RID));
	}

	@Test
	public void padPadDeviceErrorKeepsTheCount() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.FAIL_PAD);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		ended(listener);
		run.retry();
		ended(listener);
		run.close();
		device.failOpen = true;
		RecordingListener second = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), second);
		LivenessOutcome deviceError = ended(second);
		assertEquals(Optional.of(FailureReason.DEVICE), deviceError.failureReason());
		assertEquals(2, deviceError.attemptsUsed());
		run.close();
		assertEquals(2, orchestrator.countedFailures(Workflow.REGISTRATION, RID));
	}

	@Test
	public void earlierAttackThenTimeoutsInANewWindowOfferNoOverride() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		values.put("mosip.registration.liveness.on_max_failure", "SUPERVISOR_OVERRIDE");
		scripts(Script.FAIL_PAD, Script.FAIL_TIMEOUT);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		LivenessOutcome pad = ended(listener);
		assertTrue(pad.isSuspectedAttack());
		run.close();

		RecordingListener second = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), second);
		LivenessOutcome firstTimeout = ended(second);
		assertEquals(2, firstTimeout.attemptsUsed());
		assertTrue("the earlier attack is remembered", firstTimeout.isSuspectedAttack());
		run.retry();
		LivenessOutcome last = ended(second);
		assertEquals(Optional.of(FailureReason.TIMEOUT), last.failureReason());
		assertEquals(LivenessTransaction.Status.EXHAUSTED, last.status());
		assertFalse("no override after an earlier suspected attack", last.isOverrideAllowed());
		try {
			run.grantOverride(secondSupervisor());
			fail("override must be refused");
		} catch (IllegalStateException expected) {
			// expected
		}
	}

	@Test
	public void earlierAttackInTheSameWindowOffersNoOverride() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		values.put("mosip.registration.liveness.on_max_failure", "SUPERVISOR_OVERRIDE");
		scripts(Script.FAIL_PAD, Script.FAIL_TIMEOUT);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		ended(listener);
		run.retry();
		ended(listener);
		run.retry();
		LivenessOutcome last = ended(listener);
		assertEquals(LivenessTransaction.Status.EXHAUSTED, last.status());
		assertFalse(last.isOverrideAllowed());
	}

	@Test
	public void timeoutsAcrossWindowsStillOfferTheOverride() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "2");
		values.put("mosip.registration.liveness.on_max_failure", "SUPERVISOR_OVERRIDE");
		scripts(Script.FAIL_TIMEOUT);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		ended(listener);
		run.close();
		RecordingListener second = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), second);
		LivenessOutcome last = ended(second);
		assertEquals(LivenessTransaction.Status.EXHAUSTED, last.status());
		assertFalse(last.isSuspectedAttack());
		assertTrue(last.isOverrideAllowed());
		assertNotNull(run.grantOverride(secondSupervisor()));
	}

	@Test
	public void passClearsTheCount() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.FAIL_TIMEOUT, Script.PASS);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		ended(listener);
		run.close();
		assertEquals(1, orchestrator.countedFailures(Workflow.REGISTRATION, RID));

		RecordingListener second = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), second);
		assertTrue(second.awaitReady());
		assertEquals(2, attemptOfSession(1));
		LivenessPassToken token = second.captureContext.getToken().get();
		assertTrue(tokens.consume(token, clock.now()).bind(bindingFor(token), 2_000).verdict().isPassed());
		assertTrue(ended(second).isPassed());
		run.close();
		assertEquals(0, orchestrator.countedFailures(Workflow.REGISTRATION, RID));

		RecordingListener third = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), third);
		assertTrue(third.awaitReady());
		assertEquals("after a pass the next window starts at attempt 1", 1, attemptOfSession(2));
	}

	@Test
	public void countedFailuresExpireWithTheBlockTime() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.FAIL_TIMEOUT);
		run = orchestrator.start(request(Workflow.LOGIN_AUTH), listener);
		ended(listener);
		run.close();
		assertEquals(1, orchestrator.countedFailures(Workflow.LOGIN_AUTH, RID));
		clock.advance((LivenessConfig.DEFAULT_AUTH_BLOCK_MINUTES + 1) * 60_000L);
		assertEquals(0, orchestrator.countedFailures(Workflow.LOGIN_AUTH, RID));
	}

	@Test
	public void loweredMaxRetriesRefusesTheNextWindow() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.FAIL_TIMEOUT);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		ended(listener);
		run.retry();
		ended(listener);
		run.close();
		values.put("mosip.registration.liveness.max_retries", "2");
		assertStartRefused();
		assertTrue(orchestrator.isBlocked(Workflow.REGISTRATION, RID));
	}

	// ------------------------------------------------------------------ low light across liveness windows

	private int lowLightOfSession(int index) {
		return engine.sessions.get(index).request.lowLightRetries();
	}

	private static LivenessUiState lastState(RecordingListener l) {
		return l.states.get(l.states.size() - 1);
	}

	private static void assertLowLight(LivenessOutcome o, int lowLight, RecordingListener l) {
		assertEquals(Optional.of(FailureReason.QUALITY), o.failureReason());
		assertTrue("ended for low light", o.isLowLightRetry());
		assertEquals("a low-light result does not use up an attempt", 0, o.attemptsUsed());
		assertEquals(1, o.attempt());
		assertEquals(lowLight, o.lowLightRetries());
		assertTrue(o.isRetryAllowed());
		assertFalse(o.isOverrideAllowed());
		assertFalse(o.isSuspectedAttack());
		LivenessUiState shown = lastState(l);
		assertEquals(MessageKeys.FAIL_LIVENESS, shown.messageKey());
		assertEquals("the lighting hint comes first", MessageKeys.GUIDE_LIGHTING, shown.guidanceKeys().get(0));
	}

	@Test
	public void dimDimCancelNewWindowDimUsesUpTheUncountedTriesAndTheNextDimCounts() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		engine.auditsPadAttack = true;
		scripts(Script.DIM_FACE, Script.DIM_FACE, Script.PASS, Script.DIM_FACE);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertLowLight(ended(listener), 1, listener);
		assertEquals("the run tracks low-light results from the first session", 0, lowLightOfSession(0));
		run.retry();
		assertLowLight(ended(listener), 2, listener);
		assertEquals(1, lowLightOfSession(1));
		run.retry();
		assertTrue(listener.awaitReady());
		run.cancel();
		LivenessOutcome cancelled = ended(listener);
		assertEquals(Optional.of(FailureReason.CANCELLED), cancelled.failureReason());
		assertEquals("Cancel keeps the low-light count", 2, cancelled.lowLightRetries());
		run.close();
		assertEquals(2, orchestrator.lowLightRetries(Workflow.REGISTRATION, RID));
		assertEquals(0, orchestrator.countedFailures(Workflow.REGISTRATION, RID));
		assertEquals("another person starts afresh", 0,
				orchestrator.lowLightRetries(Workflow.REGISTRATION, "10001100010003"));

		RecordingListener second = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), second);
		assertLowLight(ended(second), 3, second);
		assertEquals("the new window continues the low-light count", 2, lowLightOfSession(3));
		assertEquals(1, attemptOfSession(3));
		assertFalse("low-light results write no REG-EVT-LV08",
				audit.has(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED.id()));
		assertEquals(3, orchestrator.lowLightRetries(Workflow.REGISTRATION, RID));

		run.retry();
		LivenessOutcome counted = ended(second);
		assertEquals(3, lowLightOfSession(4));
		assertEquals("the fourth dim try counts", Optional.of(FailureReason.PAD), counted.failureReason());
		assertFalse(counted.isLowLightRetry());
		assertEquals(1, counted.attempt());
		assertEquals(1, counted.attemptsUsed());
		assertEquals(0, counted.lowLightRetries());
		assertTrue(counted.isSuspectedAttack());
		LivenessUiState shown = lastState(second);
		assertEquals("the generic message", MessageKeys.FAIL_PAD, shown.messageKey());
		assertFalse(shown.guidanceKeys().contains(MessageKeys.GUIDE_LIGHTING));
		assertTrue(audit.has(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED.id()));
		run.close();
		assertEquals(1, orchestrator.countedFailures(Workflow.REGISTRATION, RID));
		assertEquals(0, orchestrator.lowLightRetries(Workflow.REGISTRATION, RID));
	}

	@Test
	public void dimThenCountedPadResetsTheLowLightCount() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.DIM_FACE, Script.FAIL_PAD, Script.DIM_FACE);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		assertLowLight(ended(listener), 1, listener);
		run.close();
		assertEquals(1, orchestrator.lowLightRetries(Workflow.REGISTRATION, RID));

		RecordingListener second = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), second);
		LivenessOutcome pad = ended(second);
		assertEquals(1, lowLightOfSession(1));
		assertEquals(Optional.of(FailureReason.PAD), pad.failureReason());
		assertEquals(1, pad.attemptsUsed());
		assertEquals("a counted failure resets the low-light count", 0, pad.lowLightRetries());
		run.close();
		assertEquals(0, orchestrator.lowLightRetries(Workflow.REGISTRATION, RID));
		assertEquals(1, orchestrator.countedFailures(Workflow.REGISTRATION, RID));

		RecordingListener third = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), third);
		LivenessOutcome dim = ended(third);
		assertEquals(0, lowLightOfSession(2));
		assertEquals(2, attemptOfSession(2));
		assertTrue("the allowance starts again after the counted failure", dim.isLowLightRetry());
		assertEquals(1, dim.attemptsUsed());
		assertEquals(1, dim.lowLightRetries());
	}

	@Test
	public void normalLightPadCountsOnTheFirstTry() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		engine.auditsPadAttack = true;
		scripts(Script.FAIL_PAD);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		LivenessOutcome pad = ended(listener);
		assertEquals("the session tracks low light", 0, lowLightOfSession(0));
		assertEquals(Optional.of(FailureReason.PAD), pad.failureReason());
		assertFalse(pad.isLowLightRetry());
		assertEquals(1, pad.attemptsUsed());
		assertEquals(0, pad.lowLightRetries());
		assertTrue(pad.isSuspectedAttack());
		assertTrue(audit.has(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED.id()));
		run.close();
		assertEquals(1, orchestrator.countedFailures(Workflow.REGISTRATION, RID));
		assertEquals(0, orchestrator.lowLightRetries(Workflow.REGISTRATION, RID));
	}

	@Test
	public void lowLightResultsDoNotUnlockTheOverride() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "1");
		values.put("mosip.registration.liveness.on_max_failure", "SUPERVISOR_OVERRIDE");
		engine.auditsPadAttack = true;
		scripts(Script.DIM_FACE);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		for (int i = 1; i <= 3; i++) {
			LivenessOutcome dim = ended(listener);
			assertLowLight(dim, i, listener);
			assertEquals(LivenessTransaction.Status.IN_PROGRESS, dim.status());
			try {
				run.requestOverride();
				fail("no override after a low-light result");
			} catch (IllegalStateException expected) {
				// expected
			}
			run.retry();
		}
		assertFalse(audit.has(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED.id()));
		LivenessOutcome last = ended(listener);
		assertEquals(Optional.of(FailureReason.PAD), last.failureReason());
		assertEquals(LivenessTransaction.Status.EXHAUSTED, last.status());
		assertFalse("the counted dim failure is a PAD failure: no override", last.isOverrideAllowed());
		assertTrue(audit.has(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED.id()));
	}

	@Test
	public void passClearsTheLowLightCount() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.DIM_FACE, Script.PASS, Script.DIM_FACE);
		run = orchestrator.start(request(Workflow.REGISTRATION), listener);
		ended(listener);
		run.close();
		assertEquals(1, orchestrator.lowLightRetries(Workflow.REGISTRATION, RID));

		RecordingListener second = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), second);
		assertTrue(second.awaitReady());
		LivenessPassToken token = second.captureContext.getToken().get();
		assertTrue(tokens.consume(token, clock.now()).bind(bindingFor(token), 2_000).verdict().isPassed());
		assertTrue(ended(second).isPassed());
		run.close();
		assertEquals(0, orchestrator.lowLightRetries(Workflow.REGISTRATION, RID));

		RecordingListener third = new RecordingListener();
		run = orchestrator.start(request(Workflow.REGISTRATION), third);
		ended(third);
		assertEquals("after a pass the next window starts without low-light results", 0, lowLightOfSession(2));
	}

	@Test
	public void lowLightCountExpiresWithTheBlockTime() throws Exception {
		values.put("mosip.registration.liveness.max_retries", "3");
		scripts(Script.DIM_FACE);
		run = orchestrator.start(request(Workflow.LOGIN_AUTH), listener);
		assertTrue(ended(listener).isLowLightRetry());
		run.close();
		assertEquals(1, orchestrator.lowLightRetries(Workflow.LOGIN_AUTH, RID));
		clock.advance((LivenessConfig.DEFAULT_AUTH_BLOCK_MINUTES + 1) * 60_000L);
		assertEquals(0, orchestrator.lowLightRetries(Workflow.LOGIN_AUTH, RID));
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
