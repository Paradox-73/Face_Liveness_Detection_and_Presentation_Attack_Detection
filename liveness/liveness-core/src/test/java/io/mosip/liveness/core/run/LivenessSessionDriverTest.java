package io.mosip.liveness.core.run;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DeviceStatus;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.run.DriverFakes.FakeDevice;
import io.mosip.liveness.core.run.DriverFakes.Recorder;
import io.mosip.liveness.core.run.DriverFakes.RecordingAudit;
import io.mosip.liveness.core.run.DriverFakes.Script;
import io.mosip.liveness.core.run.DriverFakes.ScriptedEngine;
import io.mosip.liveness.core.run.DriverFakes.ScriptedSession;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.liveness.core.ui.UiPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-CORE-RUN: the shared attempt loop of both clients. A fake device streams frames on its own thread and a scripted
 * engine decides each session, so the tests check only what the driver owns: attempt accounting (prior failures and
 * the suspected-attack mark), opening and closing sessions, frame pumping, device and engine errors that never count,
 * the capture hand-off, retry, cancel and close.
 */
class LivenessSessionDriverTest {

    private static final LivenessPolicy RESIDENT = LivenessPolicy.defaults(Workflow.REGISTRATION).toBuilder()
            .maxRetries(3).onMaxFailure(OnMaxFailure.SUPERVISOR_OVERRIDE).build();
    private static final LivenessPolicy AUTH_LOCK = LivenessPolicy.defaults(Workflow.LOGIN_AUTH).toBuilder()
            .maxRetries(3).onMaxFailure(OnMaxFailure.BLOCK_AND_LOCK).build();

    private final ScriptedEngine engine = new ScriptedEngine();
    private final FakeDevice device = new FakeDevice();
    private final Recorder recorder = new Recorder();
    private final RecordingAudit audit = new RecordingAudit();
    private final List<Throwable> reported = new CopyOnWriteArrayList<>();
    private LivenessSessionDriver driver;

    @AfterEach
    void closeDriver() throws InterruptedException {
        if (driver != null) {
            driver.close();
            assertTrue(driver.awaitTermination(5_000), "worker stopped");
        }
    }

    private LivenessSessionDriver.Builder builder(LivenessPolicy policy, LivenessSessionDriver.CaptureHandler handler) {
        return LivenessSessionDriver.builder(policy, "10001100010001").engine(() -> engine).device(() -> device)
                .audit(audit).listener(recorder).captureHandler(handler)
                .errorReporter((what, error) -> reported.add(error));
    }

    /** Takes the capture and binds it inside the hand-off, as the Android built-in camera does. */
    private static LivenessSessionDriver.CaptureHandler bindAtOnce() {
        return h -> {
            FaceFrame frame = h.captureFrame().orElseThrow(IllegalStateException::new);
            frame.wipe();
            SignedFaceCapture capture = DriverFakes.capture();
            h.attachCapture(capture);
            try {
                h.bind(BindingRequest.from(capture, DriverFakes.image(), h.passToken()), 1_000);
            } catch (TimeoutException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private LivenessSessionDriver started(LivenessSessionDriver d) {
        driver = d;
        d.start();
        return d;
    }

    // ------------------------------------------------------------------ pass

    @Test
    void passBindsTheCaptureInsideTheHandOff() throws Exception {
        engine.then(Script.pass(3));
        started(builder(RESIDENT, bindAtOnce()).build());

        AttemptOutcome o = recorder.next();
        assertTrue(o.isPass(), o.toString());
        assertEquals(Decision.PASS, o.decision());
        assertFalse(o.failureReason().isPresent());
        assertEquals(1, o.attempt());
        assertEquals(0, o.attemptsUsed());
        assertEquals(3, o.maxRetries());
        assertEquals(LivenessTransaction.Status.PASSED, o.transactionStatus());
        assertFalse(o.isRetryAllowed());
        assertFalse(o.isOverrideAllowed());
        assertTrue(o.capture().isPresent(), "the attached capture is part of a pass");
        assertEquals(engine.session(0).passTokenNonce(), o.tokenNonce().orElse(null));
        assertEquals("session-1", o.sessionId().orElse(null));
        assertEquals("liveness.success", o.messageKey().orElse(null));
        assertEquals(1, engine.requests.get(0).attempt());
        assertEquals(SessionRequest.LOW_LIGHT_NOT_TRACKED, engine.requests.get(0).lowLightRetries());
        assertTrue(engine.session(0).closed, "session closed");
        assertTrue(device.source(0).closed, "stream closed");
        assertEquals(UiPhase.STARTING, recorder.statesSnapshot().get(0).phase(), "the attempt announces itself");
        assertFalse(driver.isSessionActive());
        assertFalse(driver.retry(), "no retry after a pass");
        assertEquals(0, audit.count(AuditEventId.LIVENESS_MAX_RETRIES));
        assertTrue(reported.isEmpty(), reported.toString());
    }

    @Test
    void passBindsLaterFromAnotherThreadWhileTheSessionKeepsTicking() throws Exception {
        engine.then(Script.pass(3));
        BlockingQueue<CaptureHandOff> handOffs = new LinkedBlockingQueue<>();
        started(builder(RESIDENT, handOffs::add).build());

        CaptureHandOff h = handOffs.poll(DriverFakes.WAIT_S, TimeUnit.SECONDS);
        assertNotNull(h);
        assertEquals(1, h.attempt());
        assertEquals(Workflow.REGISTRATION, h.workflow());
        assertThrows(IllegalStateException.class, h::captureFrame, "the session frame belongs to the worker");
        ScriptedSession session = engine.session(0);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DriverFakes.WAIT_S);
        while (session.ticksAfterCaptureReady.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(session.ticksAfterCaptureReady.get() >= 2, "the worker ticks while the capture is taken");
        assertTrue(driver.isSessionActive());
        assertFalse(device.source(0).closed, "the preview stream stays open during the capture");

        SignedFaceCapture capture = DriverFakes.capture();
        CaptureHandOff.BindOutcome bound = h.bind(BindingRequest.from(capture, DriverFakes.image(), h.passToken()),
                5_000);
        assertTrue(bound.verdict().isPassed());
        assertTrue(bound.result().get().isPass());
        assertTrue(session.ownerThread.getName().startsWith("liveness-worker"), "bind ran on the worker");

        AttemptOutcome o = recorder.next();
        assertTrue(o.isPass());
        assertFalse(o.capture().isPresent(), "nothing attached");
        assertTrue(h.isDone());
        CaptureHandOff.BindOutcome again = h.bind(BindingRequest.from(capture, DriverFakes.image(), h.passToken()),
                5_000);
        assertFalse(again.verdict().isPassed(), "a token binds once");
    }

    // ------------------------------------------------------------------ retry

    @Test
    void retryAfterACountedFailureRunsTheNextAttempt() throws Exception {
        engine.then(Script.failAt(3, FailureReason.ACTIVE)).then(Script.pass(3));
        started(builder(RESIDENT, bindAtOnce()).build());

        AttemptOutcome first = recorder.next();
        assertEquals(FailureReason.ACTIVE, first.failureReason().get());
        assertEquals(Decision.FAIL_LIVENESS, first.decision());
        assertEquals(1, first.attemptsUsed());
        assertTrue(first.isRetryAllowed());
        assertTrue(first.result().isPresent());
        assertEquals("liveness.fail.active", first.messageKey().orElse(null));

        assertTrue(driver.retry());
        assertFalse(driver.retry(), "one attempt at a time");
        AttemptOutcome second = recorder.next();
        assertTrue(second.isPass());
        assertEquals(2, second.attempt());
        assertEquals(1, second.attemptsUsed());
        assertEquals(2, engine.requests.get(1).attempt());
        assertEquals(2, device.sources.size(), "each attempt opens its own stream");
        assertTrue(device.source(0).closed);
    }

    @Test
    void retryIsRefusedWhileTheFirstAttemptRuns() throws Exception {
        engine.then(Script.hold());
        started(builder(RESIDENT, bindAtOnce()).build());
        assertFalse(driver.retry(), "the first attempt is still running");
        assertTrue(engine.session(0).receivingFrames.await(DriverFakes.WAIT_S, TimeUnit.SECONDS));
        assertTrue(driver.isSessionActive());
        assertFalse(driver.retry(), "the first attempt is still running");
        driver.cancel();
        assertTrue(recorder.next().isRetryAllowed());
        assertThrows(IllegalStateException.class, driver::start, "start runs once");
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancelEndsTheAttemptWithoutUsingIt() throws Exception {
        engine.then(Script.hold()).then(Script.pass(2));
        started(builder(RESIDENT, bindAtOnce()).build());
        assertTrue(engine.session(0).receivingFrames.await(DriverFakes.WAIT_S, TimeUnit.SECONDS));

        driver.cancel();
        AttemptOutcome o = recorder.next();
        assertEquals(Decision.CANCELLED, o.decision());
        assertEquals(FailureReason.CANCELLED, o.failureReason().get());
        assertEquals(0, o.attemptsUsed());
        assertTrue(o.isRetryAllowed());
        assertTrue(device.source(0).closed);
        assertTrue(engine.session(0).closed);

        assertTrue(driver.retry());
        AttemptOutcome next = recorder.next();
        assertTrue(next.isPass());
        assertEquals(1, engine.requests.get(1).attempt(), "a cancelled attempt keeps its number");
    }

    @Test
    void cancelWhileTheEngineLoadsEndsWithoutASession() throws Exception {
        CountDownLatch engineAsked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        engine.then(Script.pass(2));
        driver = LivenessSessionDriver.builder(RESIDENT, "u").device(() -> device).audit(audit).listener(recorder)
                .captureHandler(bindAtOnce()).engine(() -> {
                    engineAsked.countDown();
                    try {
                        release.await(DriverFakes.WAIT_S, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return engine;
                }).build();
        driver.start();
        assertTrue(engineAsked.await(DriverFakes.WAIT_S, TimeUnit.SECONDS));
        assertTrue(driver.isSessionActive());
        driver.cancel();
        release.countDown();

        AttemptOutcome o = recorder.next();
        assertEquals(FailureReason.CANCELLED, o.failureReason().get());
        assertFalse(o.sessionId().isPresent());
        assertEquals(0, o.attemptsUsed());
        assertTrue(o.isRetryAllowed());
        assertTrue(engine.sessions.isEmpty(), "no session was opened");
        assertTrue(device.sources.isEmpty(), "the camera was not opened");
    }

    // ------------------------------------------------------------------ device and engine errors

    @Test
    void deviceErrorsNeverUseAnAttempt() throws Exception {
        engine.then(Script.hold()).then(Script.hold()).then(Script.pass(2));
        AtomicInteger deviceCalls = new AtomicInteger();
        driver = builder(RESIDENT, bindAtOnce()).device(() -> {
            if (deviceCalls.incrementAndGet() == 1) {
                throw new DeviceException(DeviceErrorCode.NOT_FOUND, "no device");
            }
            return device;
        }).build();
        driver.start();

        AttemptOutcome noDevice = recorder.next();
        assertEquals(FailureReason.DEVICE, noDevice.failureReason().get());
        assertEquals(Decision.ERROR, noDevice.decision());
        assertEquals(DeviceErrorCode.NOT_FOUND, noDevice.deviceError().get());
        assertFalse(noDevice.sessionId().isPresent());
        assertEquals(0, noDevice.attemptsUsed());
        assertTrue(noDevice.isRetryAllowed());
        assertEquals(1, audit.count(AuditEventId.LIVENESS_DEVICE_ERROR));
        LivenessUiState error = last(recorder.statesSnapshot());
        assertEquals(UiPhase.DEVICE_ERROR, error.phase());
        assertEquals(MessageKeys.ERROR_DEVICE_UNAVAILABLE, error.messageKey());
        assertEquals(PromptIcon.CAMERA_OFF, error.promptIcon());
        assertTrue(error.isRetryAvailable());
        assertTrue(error.guidanceKeys().contains(MessageKeys.RETRY_AVAILABLE));
        assertEquals(1, error.attempt());

        device.openFailure = new DeviceException(DeviceErrorCode.NO_STREAM, "no stream");
        assertTrue(driver.retry());
        AttemptOutcome noStream = recorder.next();
        assertEquals(FailureReason.DEVICE, noStream.failureReason().get());
        assertEquals(DeviceErrorCode.NO_STREAM, noStream.deviceError().get());
        assertTrue(noStream.sessionId().isPresent(), "the session opened and failed on the stream");
        assertEquals(0, noStream.attemptsUsed());

        device.streamError = DeviceErrorCode.DISCONNECTED;
        device.streamErrorAfterFrames = 2;
        assertTrue(driver.retry());
        AttemptOutcome lost = recorder.next();
        assertEquals(DeviceErrorCode.DISCONNECTED, lost.deviceError().get());
        assertEquals(0, lost.attemptsUsed());
        assertTrue(lost.isRetryAllowed());

        assertTrue(driver.retry());
        AttemptOutcome pass = recorder.next();
        assertTrue(pass.isPass());
        for (SessionRequest r : engine.requests) {
            assertEquals(1, r.attempt(), "device failures keep the attempt number");
        }
        assertEquals(2, deviceCalls.get(), "the device is looked up until one is found, then kept");
    }

    @Test
    void blockedDeviceOffersNoRetry() throws Exception {
        driver = builder(RESIDENT, bindAtOnce())
                .device(() -> {
                    throw new DeviceException(DeviceErrorCode.EMULATOR_BLOCKED, "emulator");
                }).build();
        driver.start();
        AttemptOutcome o = recorder.next();
        assertEquals(DeviceErrorCode.EMULATOR_BLOCKED, o.deviceError().get());
        assertFalse(o.isRetryAllowed());
        assertEquals(0, o.attemptsUsed());
        LivenessUiState state = last(recorder.statesSnapshot());
        assertEquals(MessageKeys.ERROR_EMULATOR, state.messageKey());
        assertEquals(PromptIcon.LOCKED, state.promptIcon());
        assertFalse(state.isRetryAvailable());
        assertFalse(driver.retry());
    }

    @Test
    void engineErrorsNeverUseAnAttemptAndModelIntegrityEndsTheRetries() throws Exception {
        AtomicReference<EngineErrorCode> failure = new AtomicReference<>(EngineErrorCode.NOT_READY);
        engine.then(Script.openFails(new EngineException(EngineErrorCode.INFERENCE, "open failed")))
                .then(Script.pass(2));
        driver = builder(RESIDENT, bindAtOnce()).engine(() -> {
            EngineErrorCode code = failure.getAndSet(null);
            if (code != null) {
                throw new EngineException(code, "scripted");
            }
            return engine;
        }).build();
        driver.start();

        AttemptOutcome notReady = recorder.next();
        assertEquals(FailureReason.ENGINE, notReady.failureReason().get());
        assertEquals(EngineErrorCode.NOT_READY, notReady.engineError().get());
        assertTrue(notReady.isRetryAllowed());
        assertEquals(0, notReady.attemptsUsed());
        assertEquals(1, audit.count(AuditEventId.LIVENESS_ENGINE_ERROR));

        assertTrue(driver.retry());
        AttemptOutcome openFailed = recorder.next();
        assertEquals(EngineErrorCode.INFERENCE, openFailed.engineError().get());
        assertTrue(openFailed.isRetryAllowed());
        assertEquals(1, reported.size(), "the failed open is reported to the client's log");

        failure.set(EngineErrorCode.MODEL_INTEGRITY);
        assertTrue(driver.retry());
        AttemptOutcome integrity = recorder.next();
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, integrity.engineError().get());
        assertFalse(integrity.isRetryAllowed(), "a tampered model bundle is not retried");
        assertEquals(0, integrity.attemptsUsed());
        assertFalse(driver.retry());
    }

    // ------------------------------------------------------------------ attempt accounting

    @Test
    void exhaustionContinuesThePriorFailures() throws Exception {
        engine.then(Script.failAt(2, FailureReason.TIMEOUT));
        started(builder(RESIDENT, bindAtOnce()).priorFailures(2, false).build());

        AttemptOutcome o = recorder.next();
        assertEquals(3, engine.requests.get(0).attempt(), "attempt numbering continues the earlier screens");
        assertEquals(3, o.attempt());
        assertEquals(3, o.attemptsUsed());
        assertEquals(LivenessTransaction.Status.EXHAUSTED, o.transactionStatus());
        assertFalse(o.isRetryAllowed());
        assertTrue(o.isOverrideAllowed(), "three timeouts in a resident workflow allow the override");
        assertFalse(o.isLockRequired());
        assertFalse(o.isSuspectedAttack());
        assertEquals(1, audit.count(AuditEventId.LIVENESS_MAX_RETRIES), "LV11 once, from the engine");
        assertFalse(driver.retry());
    }

    @Test
    void driverWritesTheMaxRetriesEventForAnEngineThatDoesNot() throws Exception {
        engine.auditsMaxRetries = false;
        engine.then(Script.failAt(2, FailureReason.ACTIVE));
        started(builder(RESIDENT, bindAtOnce()).priorFailures(2, false).build());
        AttemptOutcome o = recorder.next();
        assertEquals(LivenessTransaction.Status.EXHAUSTED, o.transactionStatus());
        assertEquals(1, audit.count(AuditEventId.LIVENESS_MAX_RETRIES));
        assertEquals("session-1", audit.events.stream()
                .filter(e -> e.eventId() == AuditEventId.LIVENESS_MAX_RETRIES).findFirst().get().sessionId());
    }

    @Test
    void anEarlierAttackForbidsTheOverride() throws Exception {
        engine.then(Script.failAt(2, FailureReason.TIMEOUT));
        started(builder(RESIDENT, bindAtOnce()).priorFailures(2, true).build());
        AttemptOutcome o = recorder.next();
        assertEquals(LivenessTransaction.Status.EXHAUSTED, o.transactionStatus());
        assertTrue(o.isSuspectedAttack(), "the mark of the earlier screen is kept");
        assertFalse(o.isOverrideAllowed(), "no override after a suspected attack on an earlier screen");
    }

    @Test
    void anAttackInThisTransactionForbidsTheOverride() throws Exception {
        engine.then(Script.bindFails(2, FailureReason.BINDING)).then(Script.failAt(2, FailureReason.TIMEOUT));
        started(builder(RESIDENT, bindAtOnce()).build());

        AttemptOutcome binding = recorder.next();
        assertEquals(FailureReason.BINDING, binding.failureReason().get());
        assertTrue(binding.isSuspectedAttack());
        assertFalse(binding.capture().isPresent(), "a failed capture is not handed out");
        assertTrue(driver.retry());
        AttemptOutcome timeout = recorder.next();
        assertTrue(timeout.isRetryAllowed());
        assertTrue(driver.retry());
        AttemptOutcome last = recorder.next();
        assertEquals(FailureReason.TIMEOUT, last.failureReason().get());
        assertEquals(3, last.attemptsUsed());
        assertTrue(last.isSuspectedAttack());
        assertFalse(last.isOverrideAllowed(), "a timeout on the last attempt does not clear the attack");
    }

    @Test
    void authenticationWorkflowRequiresTheLockAfterTheLastAttempt() throws Exception {
        engine.then(Script.failAt(2, FailureReason.ACTIVE));
        started(builder(AUTH_LOCK, bindAtOnce()).priorFailures(1, false).build());
        assertTrue(recorder.next().isRetryAllowed());
        assertTrue(driver.retry());
        AttemptOutcome o = recorder.next();
        assertEquals(3, o.attemptsUsed());
        assertTrue(o.isLockRequired());
        assertFalse(o.isOverrideAllowed());
        assertEquals(OnMaxFailure.BLOCK_AND_LOCK, o.onMaxFailure());
    }

    @Test
    void priorFailuresMustLeaveAnAttempt() {
        LivenessSessionDriver.Builder b = builder(RESIDENT, bindAtOnce());
        assertThrows(IllegalArgumentException.class, () -> b.priorFailures(3, false));
        assertThrows(IllegalArgumentException.class, () -> b.priorFailures(-1, false));
        assertThrows(NullPointerException.class,
                () -> LivenessSessionDriver.builder(RESIDENT, "u").engine(() -> engine).build(),
                "engine, device, audit, listener and capture handler are required");
    }

    // ------------------------------------------------------------------ low-light count across windows (fix 17)

    /** Allowance of the scripted dim face, as the shipped passive.low_light_max_uncounted. */
    private static final int LOW_LIGHT_ALLOWANCE = 3;

    @Test
    void priorLowLightCountReachesTheFirstSessionAndEachOutcome() throws Exception {
        engine.then(Script.dimFace(2, LOW_LIGHT_ALLOWANCE));
        started(builder(RESIDENT, bindAtOnce()).priorFailures(0, false, 2).trackLowLight(true).build());

        AttemptOutcome third = recorder.next();
        assertEquals(2, engine.requests.get(0).lowLightRetries(), "the earlier window's count reaches the engine");
        assertEquals(FailureReason.QUALITY, third.failureReason().get());
        assertTrue(third.result().get().isLowLightRetry());
        assertEquals(3, third.lowLightRetries());
        assertEquals(0, third.attemptsUsed());
        assertTrue(third.isRetryAllowed());
        assertFalse(third.isSuspectedAttack());

        assertTrue(driver.retry());
        AttemptOutcome counted = recorder.next();
        assertEquals(3, engine.requests.get(1).lowLightRetries());
        assertEquals(FailureReason.PAD, counted.failureReason().get(), "the allowance is used up: the PAD counts");
        assertEquals(1, counted.attempt());
        assertEquals(1, counted.attemptsUsed());
        assertEquals(0, counted.lowLightRetries(), "a counted failure resets the carried count");
        assertTrue(counted.isSuspectedAttack());

        assertTrue(driver.retry());
        AttemptOutcome again = recorder.next();
        assertEquals(0, engine.requests.get(2).lowLightRetries());
        assertEquals(FailureReason.QUALITY, again.failureReason().get());
        assertEquals(2, again.attempt());
        assertEquals(1, again.lowLightRetries());
        assertTrue(again.toString().contains("lowLight=1"), again.toString());
        assertTrue(reported.isEmpty(), reported.toString());
    }

    @Test
    void reopenedWindowContinuesTheLowLightCount() throws Exception {
        engine.then(Script.dimFace(2, LOW_LIGHT_ALLOWANCE));
        LivenessSessionDriver first = started(builder(RESIDENT, bindAtOnce()).trackLowLight(true).build());
        assertEquals(1, recorder.next().lowLightRetries());
        assertTrue(first.retry());
        AttemptOutcome closing = recorder.next();
        assertEquals(2, closing.lowLightRetries());
        first.close();
        assertTrue(first.awaitTermination(5_000), "first window stopped");

        started(builder(RESIDENT, bindAtOnce()).trackLowLight(true)
                .priorFailures(closing.attemptsUsed(), closing.isSuspectedAttack(), closing.lowLightRetries())
                .build());
        AttemptOutcome third = recorder.next();
        assertEquals(2, engine.requests.get(2).lowLightRetries());
        assertEquals(FailureReason.QUALITY, third.failureReason().get(), "still within the allowance of 3");
        assertEquals(0, third.attemptsUsed());
        assertEquals(3, third.lowLightRetries());
        assertTrue(driver.retry());
        AttemptOutcome fourth = recorder.next();
        assertEquals(FailureReason.PAD, fourth.failureReason().get(), "a new window gives no new allowance");
        assertEquals(1, fourth.attemptsUsed());
        assertEquals(0, fourth.lowLightRetries());
    }

    @Test
    void priorLowLightCountWithoutTrackingKeepsEveryPadCounted() throws Exception {
        engine.then(Script.dimFace(2, LOW_LIGHT_ALLOWANCE));
        started(builder(RESIDENT, bindAtOnce()).priorFailures(1, false, 2).build());
        AttemptOutcome o = recorder.next();
        assertEquals(SessionRequest.LOW_LIGHT_NOT_TRACKED, engine.requests.get(0).lowLightRetries());
        assertEquals(FailureReason.PAD, o.failureReason().get());
        assertEquals(2, o.attemptsUsed());
        assertEquals(0, o.lowLightRetries());
    }

    @Test
    void attemptWithoutASessionReportsTheCarriedLowLightCount() throws Exception {
        engine.then(Script.openFails(new EngineException(EngineErrorCode.INFERENCE, "open failed")))
                .then(Script.pass(2));
        started(builder(RESIDENT, bindAtOnce()).priorFailures(0, false, 2).trackLowLight(true).build());
        AttemptOutcome failed = recorder.next();
        assertFalse(failed.sessionId().isPresent());
        assertEquals(2, failed.lowLightRetries());
        assertTrue(driver.retry());
        AttemptOutcome pass = recorder.next();
        assertTrue(pass.isPass(), pass.toString());
        assertEquals(2, engine.requests.get(1).lowLightRetries());
        assertEquals(2, pass.lowLightRetries(), "a pass ends the transaction; the client drops the count");
    }

    @Test
    void priorLowLightCountOutsideItsRangeIsRefused() throws Exception {
        LivenessSessionDriver.Builder b = builder(RESIDENT, bindAtOnce());
        int max = LivenessTransaction.maxPriorLowLightRetries();
        assertThrows(IllegalArgumentException.class, () -> b.priorFailures(0, false, -1));
        assertThrows(IllegalArgumentException.class, () -> b.priorFailures(0, false, max + 1));
        assertThrows(IllegalArgumentException.class, () -> b.priorFailures(3, false, 0));

        engine.then(Script.pass(2));
        started(b.priorFailures(0, false, max).priorFailures(1, true).trackLowLight(true).build());
        AttemptOutcome o = recorder.next();
        assertEquals(0, engine.requests.get(0).lowLightRetries(), "the two-argument overload carries no count");
        assertEquals(0, o.lowLightRetries());
        assertEquals(2, o.attempt());
    }

    // ------------------------------------------------------------------ close

    @Test
    void closeEndsTheRunningAttemptAndIsIdempotent() throws Exception {
        engine.then(Script.hold());
        started(builder(RESIDENT, bindAtOnce()).build());
        assertTrue(engine.session(0).receivingFrames.await(DriverFakes.WAIT_S, TimeUnit.SECONDS));

        driver.close();
        AttemptOutcome o = recorder.next();
        assertEquals(FailureReason.CANCELLED, o.failureReason().get());
        assertFalse(o.isRetryAllowed(), "no retry once closed");
        assertEquals(0, o.attemptsUsed());
        assertTrue(driver.awaitTermination(5_000));
        assertTrue(driver.isClosed());
        assertTrue(engine.session(0).closed);
        assertTrue(device.source(0).closed);
        driver.close();
        assertFalse(driver.retry());
        driver.cancel();
        assertTrue(recorder.outcomes.isEmpty(), "close reports the attempt once");
    }

    @Test
    void closeDuringTheCaptureStepRefusesALateBind() throws Exception {
        engine.then(Script.pass(2));
        BlockingQueue<CaptureHandOff> handOffs = new LinkedBlockingQueue<>();
        started(builder(RESIDENT, handOffs::add).build());
        CaptureHandOff h = handOffs.poll(DriverFakes.WAIT_S, TimeUnit.SECONDS);
        assertNotNull(h);

        driver.close();
        AttemptOutcome o = recorder.next();
        assertEquals(FailureReason.CANCELLED, o.failureReason().get());
        assertTrue(driver.awaitTermination(5_000));
        SignedFaceCapture capture = DriverFakes.capture();
        assertThrows(TimeoutException.class,
                () -> h.bind(BindingRequest.from(capture, DriverFakes.image(), h.passToken()), 1_000));
    }

    @Test
    void closeBeforeStartRunsNothing() throws Exception {
        driver = builder(RESIDENT, bindAtOnce()).build();
        driver.close();
        driver.start();
        assertTrue(driver.awaitTermination(5_000));
        assertTrue(engine.requests.isEmpty());
        assertFalse(driver.lastOutcome().isPresent());
    }

    // ------------------------------------------------------------------ capture step options and hooks

    @Test
    void releasedDeviceMustReportReadyBeforeTheCapture() throws Exception {
        engine.then(Script.pass(2)).then(Script.pass(2));
        device.status = DeviceStatus.BUSY;
        AtomicBoolean handlerCalled = new AtomicBoolean();
        started(builder(AUTH_LOCK, h -> handlerCalled.set(true)).releaseDeviceBeforeCapture(200).build());

        AttemptOutcome busy = recorder.next();
        assertFalse(handlerCalled.get(), "no capture while the device is Busy");
        assertEquals(FailureReason.DEVICE, busy.failureReason().get());
        assertEquals(DeviceErrorCode.NOT_READY, busy.deviceError().get());
        assertEquals(0, busy.attemptsUsed());
        assertTrue(device.statusCalls.get() >= 2, "Ready is polled");

        device.status = DeviceStatus.READY;
        AtomicBoolean streamClosedAtCapture = new AtomicBoolean();
        driver.close();
        assertTrue(driver.awaitTermination(5_000));
        int sourcesBefore = device.sources.size();
        driver = builder(AUTH_LOCK, h -> {
            streamClosedAtCapture.set(device.source(device.sources.size() - 1).closed);
            bindAtOnce().onCaptureReady(h);
        }).releaseDeviceBeforeCapture(200).build();
        driver.start();
        AttemptOutcome pass = recorder.next();
        assertTrue(pass.isPass(), pass.toString());
        assertTrue(device.sources.size() > sourcesBefore);
        assertTrue(streamClosedAtCapture.get(), "the stream is closed before the capture");
    }

    @Test
    void hooksCarryListenerDispatchFramesPreviewAndLowLight() throws Exception {
        engine.then(Script.pass(3));
        AtomicInteger dispatched = new AtomicInteger();
        Executor counting = r -> {
            dispatched.incrementAndGet();
            r.run();
        };
        AtomicInteger decorated = new AtomicInteger();
        List<FaceFrame> previews = new CopyOnWriteArrayList<>();
        List<FaceFrame> delivered = new CopyOnWriteArrayList<>();
        started(builder(RESIDENT, bindAtOnce()).listenerExecutor(counting).trackLowLight(true)
                .previewListener(previews::add)
                .frameSinkDecorator(session -> {
                    decorated.incrementAndGet();
                    return new FrameSink() {
                        @Override
                        public void onFrame(FaceFrame frame) {
                            delivered.add(frame);
                            session.onFrame(frame);
                        }

                        @Override
                        public void onError(io.mosip.liveness.core.device.DeviceError error) {
                            session.onError(error);
                        }

                        @Override
                        public void onEnd(io.mosip.liveness.core.device.EndReason reason) {
                            session.onEnd(reason);
                        }
                    };
                }).build());

        assertTrue(recorder.next().isPass());
        assertEquals(recorder.statesSnapshot().size() + 1, dispatched.get(), "every callback goes through the hook");
        assertEquals(1, decorated.get());
        assertEquals(3, delivered.size(), "frames reach the session through the decorated sink");
        assertFalse(previews.isEmpty());
        for (FaceFrame p : previews) {
            for (FaceFrame d : delivered) {
                assertNotSame(d.data(), p.data(), "the preview gets its own copy");
            }
        }
        assertEquals(0, engine.requests.get(0).lowLightRetries(), "the low-light count is passed on");
    }

    @Test
    void aFailingListenerDoesNotStopTheDriver() throws Exception {
        engine.then(Script.failAt(2, FailureReason.ACTIVE)).then(Script.pass(2));
        List<AttemptOutcome> outcomes = new ArrayList<>();
        CountDownLatch ended = new CountDownLatch(2);
        driver = LivenessSessionDriver.builder(RESIDENT, "u").engine(() -> engine).device(() -> device).audit(audit)
                .captureHandler(bindAtOnce()).errorReporter((what, error) -> reported.add(error))
                .listener(new LivenessSessionDriver.Listener() {
                    @Override
                    public void onUiState(LivenessUiState state) {
                        throw new IllegalStateException("screen gone");
                    }

                    @Override
                    public void onAttemptEnded(AttemptOutcome outcome) {
                        outcomes.add(outcome);
                        ended.countDown();
                        if (outcome.isRetryAllowed()) {
                            driver.retry();
                        }
                    }
                }).build();
        driver.start();
        assertTrue(ended.await(DriverFakes.WAIT_S, TimeUnit.SECONDS));
        assertTrue(outcomes.get(1).isPass());
        assertFalse(reported.isEmpty(), "listener failures are reported");
    }

    @Test
    void aThrowingCaptureHandlerFailsTheCaptureWithoutUsingAnAttempt() throws Exception {
        engine.then(Script.pass(2));
        started(builder(RESIDENT, h -> {
            throw new IllegalStateException("camera gone");
        }).build());
        AttemptOutcome o = recorder.next();
        assertEquals(FailureReason.DEVICE, o.failureReason().get());
        assertEquals(DeviceErrorCode.CAPTURE_FAILED, o.deviceError().get());
        assertEquals(0, o.attemptsUsed());
        assertTrue(o.isRetryAllowed());
        assertEquals(1, reported.size());
    }

    @Test
    void captureFailedFromAnotherThreadEndsTheAttempt() throws Exception {
        engine.then(Script.pass(2));
        BlockingQueue<CaptureHandOff> handOffs = new LinkedBlockingQueue<>();
        started(builder(RESIDENT, handOffs::add).build());
        CaptureHandOff h = handOffs.poll(DriverFakes.WAIT_S, TimeUnit.SECONDS);
        assertNotNull(h);
        h.captureStarted();
        h.captureFailed(io.mosip.liveness.core.device.DeviceError.of(DeviceErrorCode.CAPTURE_TIMEOUT, "slow"));
        AttemptOutcome o = recorder.next();
        assertEquals(DeviceErrorCode.CAPTURE_TIMEOUT, o.deviceError().get());
        assertEquals(0, o.attemptsUsed());
        assertTrue(o.isRetryAllowed());
    }

    private static LivenessUiState last(List<LivenessUiState> states) {
        return states.get(states.size() - 1);
    }
}
