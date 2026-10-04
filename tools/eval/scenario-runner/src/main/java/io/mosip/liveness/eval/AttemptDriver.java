/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.RandomSource;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.ui.LivenessUiState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Runs one liveness attempt (one session) the way a client does: streams frames from a {@link Playlist} on a
 * stream-time clock, calls {@code tick()} after each frame, answers prompts through the playlist, and at
 * {@code CAPTURE_READY} simulates the MockMDS signed capture of the last streamed frame and binds it.
 *
 * <p>Wall-clock time of every {@code onFrame} call is measured, split by stage through the {@link StageTimer}.
 */
final class AttemptDriver {

    /** Serial number of the simulated L0 face device. */
    static final String DEVICE_SERIAL = "EVAL-FACE-0001";
    /** Time between the start of the signed capture and its image (MockMDS answers in about this time). */
    static final long CAPTURE_DELAY_MS = 300;

    private final LivenessEngine engine;
    private final StageTimer timer;
    private final ImageDecoder captureDecoder;

    /**
     * Creates a driver.
     *
     * @param engine         the engine
     * @param timer          the timer the engine's decorators write to
     * @param captureDecoder decoder for the simulated signed capture
     */
    AttemptDriver(LivenessEngine engine, StageTimer timer, ImageDecoder captureDecoder) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.captureDecoder = Objects.requireNonNull(captureDecoder, "captureDecoder");
    }

    /**
     * Returns the simulated device: an L0 SBI face camera that streams and has no device-side PAD.
     *
     * @return the device descriptor
     */
    static DeviceDescriptor device() {
        return DeviceDescriptor.builder().serialNumber(DEVICE_SERIAL).certification(CertificationLevel.L0)
                .specVersion("0.9.5").transport(DeviceTransport.SBI_HTTP).padCapable(false).streamCapable(true)
                .build();
    }

    /**
     * Runs one attempt.
     *
     * @param policy      resolved policy of the scenario's workflow
     * @param attempt     attempt number (1-based)
     * @param random      the transaction's random source
     * @param playlist    frame source, positioned at its start
     * @param fps         stream frame rate
     * @param maxStreamMs stream time after which the runner cancels the attempt
     * @param subjectRef  subject reference bound into the pass token
     * @return what happened
     * @throws EngineException if the engine refuses to open a session
     */
    AttemptResult run(LivenessPolicy policy, int attempt, RandomSource random, Playlist playlist, int fps,
                      long maxStreamMs, String subjectRef) throws EngineException {
        return run(policy, attempt, random, playlist, fps, maxStreamMs, subjectRef,
                SessionRequest.LOW_LIGHT_NOT_TRACKED);
    }

    /**
     * Runs one attempt and tells the session how many low-light endings the transaction has had since its last
     * counted failure, the way the clients do.
     *
     * @param policy          resolved policy of the scenario's workflow
     * @param attempt         attempt number (1-based)
     * @param random          the transaction's random source
     * @param playlist        frame source, positioned at its start
     * @param fps             stream frame rate
     * @param maxStreamMs     stream time after which the runner cancels the attempt
     * @param subjectRef      subject reference bound into the pass token
     * @param lowLightRetries low-light endings so far ({@code LivenessTransaction.lowLightRetries()}), or
     *                        {@link SessionRequest#LOW_LIGHT_NOT_TRACKED} to not pass any
     * @return what happened
     * @throws EngineException if the engine refuses to open a session
     */
    AttemptResult run(LivenessPolicy policy, int attempt, RandomSource random, Playlist playlist, int fps,
                      long maxStreamMs, String subjectRef, int lowLightRetries) throws EngineException {
        ManualClock clock = new ManualClock();
        Listener listener = new Listener();
        List<DiagnosticRecord> diagnostics = new ArrayList<>();
        SessionRequest.Builder builder = SessionRequest.builder(policy, subjectRef, device()).attempt(attempt)
                .clock(clock).random(random).diagnosticSink(diagnostics::add);
        if (lowLightRetries != SessionRequest.LOW_LIGHT_NOT_TRACKED) {
            builder.lowLightRetries(lowLightRetries);
        }
        SessionRequest request = builder.build();
        AttemptResult.Builder out = new AttemptResult.Builder();
        long wall0 = System.nanoTime();
        LivenessSession session = engine.open(request, listener);
        try {
            session.start();
            stream(session, playlist, clock, listener, fps, maxStreamMs, out);
            LivenessResult result = session.result().orElseThrow(
                    () -> new IllegalStateException("session ended without a result in " + session.state()));
            out.result(result).streamMs(clock.millis()).wallMs((System.nanoTime() - wall0) / 1e6)
                    .prompts(playlist.prompts()).diagnostics(diagnostics).lastUi(listener.lastUi);
            return out.build();
        } finally {
            session.close();
        }
    }

    private void stream(LivenessSession session, Playlist playlist, ManualClock clock, Listener listener, int fps,
                        long maxStreamMs, AttemptResult.Builder out) {
        long frameNo = 0;
        Playlist.Frame last = null;
        while (!session.state().isTerminal()) {
            if (clock.millis() >= maxStreamMs) {
                out.runnerLimit(true);
                session.cancel();
                break;
            }
            frameNo++;
            clock.advanceNanos(frameNo * 1_000_000_000L / fps - (frameNo - 1) * 1_000_000_000L / fps);
            Playlist.Frame f = playlist.next(clock.monotonicNanos());
            if (f == null) {
                session.onEnd(EndReason.CLOSED);
                break;
            }
            last = f;
            feed(session, f, frameNo, clock, out);
            while (!listener.prompts.isEmpty()) {
                playlist.onPrompt(listener.prompts.poll(), clock.monotonicNanos());
            }
            if (session.state() == SessionState.CAPTURE_READY) {
                captureAndBind(session, last, clock, out);
            }
        }
    }

    private void feed(LivenessSession session, Playlist.Frame f, long sequence, ManualClock clock,
                      AttemptResult.Builder out) {
        // A copy: the session may wipe the frame it keeps for capture, and packs may share arrays.
        FaceFrame frame = FaceFrame.builder().data(f.bytes.clone()).format(PixelFormat.JPEG)
                .monotonicNanos(clock.monotonicNanos()).sequence(sequence).sourceId(DEVICE_SERIAL).build();
        timer.reset();
        long t0 = System.nanoTime();
        session.onFrame(frame);
        session.tick();
        double ms = (System.nanoTime() - t0) / 1e6;
        out.frameStreamed(f.packId);
        if (timer.calls(StageTimer.Stage.DECODE) > 0) {
            out.frameAnalysed(ms, timer);
        }
    }

    private void captureAndBind(LivenessSession session, Playlist.Frame last, ManualClock clock,
                                AttemptResult.Builder out) {
        LivenessPassToken token = session.passToken().orElse(null);
        session.captureStarted();
        clock.advanceMs(CAPTURE_DELAY_MS);
        ImageBuffer image;
        try {
            image = captureDecoder.decode(last.bytes);
        } catch (ImageDecodeException e) {
            session.captureFailed(null);
            return;
        }
        BindingRequest.Builder req = BindingRequest.builder(image, clock.now(), DEVICE_SERIAL);
        if (token != null) {
            req.token(token);
        }
        timer.reset();
        long t0 = System.nanoTime();
        session.bind(req.build());
        out.bindMs((System.nanoTime() - t0) / 1e6);
    }

    /** Collects prompts and the last UI message from the session callbacks. */
    private static final class Listener implements LivenessListener {
        final Deque<ChallengeType> prompts = new ArrayDeque<>();
        String lastUi = "";

        @Override
        public void onUi(LivenessUiState state) {
            lastUi = state.messageKey();
        }

        @Override
        public void onAudit(LivenessAuditEvent event) {
            if (event.eventId() == AuditEventId.LIVENESS_CHALLENGE_ISSUED) {
                String type = event.attributes().get(AuditAttributes.CHALLENGE_TYPE);
                if (type != null) {
                    prompts.add(ChallengeType.valueOf(type));
                }
            }
        }
    }
}
