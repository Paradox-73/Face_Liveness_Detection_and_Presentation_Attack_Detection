/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.run;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.run.LivenessSessionDriver.CaptureHandler;
import io.mosip.liveness.core.run.LivenessSessionDriver.DeviceSupplier;
import io.mosip.liveness.core.run.LivenessSessionDriver.EngineSupplier;
import io.mosip.liveness.core.run.LivenessSessionDriver.ErrorReporter;
import io.mosip.liveness.core.run.LivenessSessionDriver.Listener;
import io.mosip.liveness.core.run.LivenessSessionDriver.PreviewListener;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.UnaryOperator;

/**
 * Configures a {@link LivenessSessionDriver}: the hooks the client plugs in (engine, device, listener, capture
 * handler), the threads, and the counts carried over from an earlier screen. Start one with
 * {@link LivenessSessionDriver#builder(LivenessPolicy, String)}.
 */
public final class LivenessSessionDriverBuilder {

    final LivenessPolicy policy;
    final String subjectRef;
    EngineSupplier engines;
    DeviceSupplier devices;
    AuditSink audit;
    Listener listener;
    CaptureHandler captureHandler;
    DiagnosticSink diagnostics = DiagnosticSink.noop();
    Clock clock = Clock.system();
    PreviewListener preview;
    UnaryOperator<FrameSink> sinkDecorator = UnaryOperator.identity();
    Executor listenerExecutor = Runnable::run;
    ScheduledExecutorService executor;
    ErrorReporter errors = (what, error) -> { };
    long deviceReadyTimeoutMs;
    boolean trackLowLight;
    int priorCountedFailures;
    boolean priorSuspectedAttack;
    int priorLowLightRetries;

    LivenessSessionDriverBuilder(LivenessPolicy policy, String subjectRef) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.subjectRef = subjectRef == null ? "" : subjectRef;
    }

    /**
     * Sets the engine supplier (required).
     *
     * @param engines supplies the engine before each session
     * @return this builder
     */
    public LivenessSessionDriverBuilder engine(EngineSupplier engines) {
        this.engines = Objects.requireNonNull(engines, "engines");
        return this;
    }

    /**
     * Sets the device supplier (required).
     *
     * @param devices supplies the face device once
     * @return this builder
     */
    public LivenessSessionDriverBuilder device(DeviceSupplier devices) {
        this.devices = Objects.requireNonNull(devices, "devices");
        return this;
    }

    /**
     * Sets the audit sink (required); it receives the sessions' events and the driver's own LV11, LV12 and LV13
     * events.
     *
     * @param audit the sink
     * @return this builder
     */
    public LivenessSessionDriverBuilder audit(AuditSink audit) {
        this.audit = Objects.requireNonNull(audit, "audit");
        return this;
    }

    /**
     * Sets the listener (required).
     *
     * @param listener receiver of UI states and attempt outcomes
     * @return this builder
     */
    public LivenessSessionDriverBuilder listener(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /**
     * Sets the capture handler (required).
     *
     * @param captureHandler takes and binds the signed capture
     * @return this builder
     */
    public LivenessSessionDriverBuilder captureHandler(CaptureHandler captureHandler) {
        this.captureHandler = Objects.requireNonNull(captureHandler, "captureHandler");
        return this;
    }

    /**
     * Continues the counted failures of earlier screens or windows for the same workflow and subject; default
     * none. The low-light count starts at 0; {@link #priorFailures(int, boolean, int)} also continues it.
     *
     * @param countedFailures counted failures so far, 0 to {@code max_retries - 1}
     * @param suspectedAttack whether one of them was a PAD, integrity or binding failure
     * @return this builder
     * @throws IllegalArgumentException if {@code countedFailures} is negative or leaves no attempt
     */
    public LivenessSessionDriverBuilder priorFailures(int countedFailures, boolean suspectedAttack) {
        return priorFailures(countedFailures, suspectedAttack, 0);
    }

    /**
     * Continues the counted failures, the suspected-attack mark and the uncounted low-light results of earlier
     * screens or windows for the same workflow and subject; default none. With {@link #trackLowLight(boolean)}
     * the first session gets {@code lowLightRetries} ({@link SessionRequest.Builder#lowLightRetries(int)}), so
     * closing and reopening the window does not restart the low-light allowance. The client takes the three
     * values from the last {@link AttemptOutcome} of the earlier screen ({@link AttemptOutcome#attemptsUsed()},
     * {@link AttemptOutcome#isSuspectedAttack()}, {@link AttemptOutcome#lowLightRetries()}).
     *
     * @param countedFailures counted failures so far, 0 to {@code max_retries - 1}
     * @param suspectedAttack whether one of them was a PAD, integrity or binding failure
     * @param lowLightRetries uncounted low-light results since the last counted failure, 0 to
     *                        {@link LivenessTransaction#maxPriorLowLightRetries()}
     * @return this builder
     * @throws IllegalArgumentException if {@code countedFailures} is negative or leaves no attempt, or if
     *                                  {@code lowLightRetries} is outside its range
     */
    public LivenessSessionDriverBuilder priorFailures(int countedFailures, boolean suspectedAttack, int lowLightRetries) {
        if (countedFailures < 0 || countedFailures >= policy.maxRetries()) {
            throw new IllegalArgumentException("prior counted failures must be between 0 and max_retries - 1 ("
                    + (policy.maxRetries() - 1) + "): " + countedFailures);
        }
        int maxLowLight = LivenessTransaction.maxPriorLowLightRetries();
        if (lowLightRetries < 0 || lowLightRetries > maxLowLight) {
            throw new IllegalArgumentException("prior low-light retries must be between 0 and " + maxLowLight
                    + ": " + lowLightRetries);
        }
        this.priorCountedFailures = countedFailures;
        this.priorSuspectedAttack = suspectedAttack;
        this.priorLowLightRetries = lowLightRetries;
        return this;
    }

    /**
     * Sets the clock of the sessions and of the driver's audit events; default {@link Clock#system()}.
     *
     * @param clock the clock
     * @return this builder
     */
    public LivenessSessionDriverBuilder clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Sets the diagnostic sink of the sessions; default discards records.
     *
     * @param diagnostics the sink
     * @return this builder
     */
    public LivenessSessionDriverBuilder diagnosticSink(DiagnosticSink diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        return this;
    }

    /**
     * Sets the worker. It must run one task at a time on one thread; the driver owns it and shuts it down at
     * {@link LivenessSessionDriver#close()}. Default: a single daemon thread named {@code liveness-worker}.
     *
     * @param executor the worker
     * @return this builder
     */
    public LivenessSessionDriverBuilder executor(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        return this;
    }

    /**
     * Sets how listener callbacks are dispatched, for example to the UI thread. It must keep their order.
     * Default: on the worker thread, in the session call that caused them.
     *
     * @param listenerExecutor the dispatcher
     * @return this builder
     */
    public LivenessSessionDriverBuilder listenerExecutor(Executor listenerExecutor) {
        this.listenerExecutor = Objects.requireNonNull(listenerExecutor, "listenerExecutor");
        return this;
    }

    /**
     * Sends a private copy of every stream frame to the preview; default no preview copies.
     *
     * @param preview receiver of the copies
     * @return this builder
     */
    public LivenessSessionDriverBuilder previewListener(PreviewListener preview) {
        this.preview = Objects.requireNonNull(preview, "preview");
        return this;
    }

    /**
     * Wraps the session as the receiver of the stream, for example to time the frames. Called on the worker
     * once per session; the returned sink must pass every call on to the session.
     *
     * @param sinkDecorator returns the sink frames are delivered to
     * @return this builder
     */
    public LivenessSessionDriverBuilder frameSinkDecorator(UnaryOperator<FrameSink> sinkDecorator) {
        this.sinkDecorator = Objects.requireNonNull(sinkDecorator, "sinkDecorator");
        return this;
    }

    /**
     * Closes the stream before the capture step and waits until the device reports Ready, and also waits for
     * Ready before every later session. For SBI devices that are Busy while they stream (desktop authentication
     * workflows). Default 0: the stream stays open until the attempt ends.
     *
     * @param readyTimeoutMs longest wait for Ready; after it the capture fails with {@code NOT_READY}
     * @return this builder
     * @throws IllegalArgumentException if negative
     */
    public LivenessSessionDriverBuilder releaseDeviceBeforeCapture(long readyTimeoutMs) {
        if (readyTimeoutMs < 0) {
            throw new IllegalArgumentException("readyTimeoutMs must not be negative");
        }
        this.deviceReadyTimeoutMs = readyTimeoutMs;
        return this;
    }

    /**
     * Passes the transaction's uncounted low-light results to each session
     * ({@link SessionRequest.Builder#lowLightRetries(int)}), which turns on the engine's low-light rule. Default
     * off: every PAD failure counts. A client that reopens the window for the same subject continues the count
     * with {@link #priorFailures(int, boolean, int)}; without it each window would start a new allowance.
     *
     * @param trackLowLight {@code true} to pass the number
     * @return this builder
     */
    public LivenessSessionDriverBuilder trackLowLight(boolean trackLowLight) {
        this.trackLowLight = trackLowLight;
        return this;
    }

    /**
     * Sets the receiver of contained failures, for the client's log; default discards them.
     *
     * @param errors the receiver
     * @return this builder
     */
    public LivenessSessionDriverBuilder errorReporter(ErrorReporter errors) {
        this.errors = Objects.requireNonNull(errors, "errors");
        return this;
    }

    /**
     * Creates the driver; nothing runs until {@link LivenessSessionDriver#start()}.
     *
     * @return the driver
     * @throws NullPointerException if the engine, device, audit sink, listener or capture handler is missing
     */
    public LivenessSessionDriver build() {
        return new LivenessSessionDriver(this);
    }
}
