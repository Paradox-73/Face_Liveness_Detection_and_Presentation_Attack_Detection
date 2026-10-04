/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;

import java.util.Optional;

/**
 * One liveness attempt for one person: the state machine of docs/design/engine.md section 3.
 *
 * <p><b>Threading.</b> A session is confined to one owning thread (the client's liveness worker). Every method
 * must be called from that thread, except {@link #sessionId()}, {@link #state()} and {@link #cancel()}, which
 * are safe from any thread; a cancel takes effect at the next call on the owning thread. Stream frames reach
 * the session through the client's single-slot hand-off, never straight from the reader thread.
 *
 * <p><b>Lifecycle.</b>
 * <ol>
 *   <li>{@link #start()}: {@code IDLE -> OPENING}; the caller then opens the device stream.</li>
 *   <li>{@link #onFrame(FaceFrame)} and {@link #tick()} drive acquisition, passive PAD and challenges.</li>
 *   <li>At {@code CAPTURE_READY} a single-use {@link LivenessPassToken} exists; the caller starts the signed
 *       capture and calls {@link #captureStarted()} ({@code -> CAPTURING}).</li>
 *   <li>{@link #bind(BindingRequest)} checks the signed capture against the session
 *       ({@code -> BINDING -> PASSED} or {@code FAILED}); {@link #captureFailed(DeviceError)} ends it with a
 *       device failure.</li>
 *   <li>{@link #close()} wipes frame buffers. A retry opens a new session.</li>
 * </ol>
 * Frame sink callbacks: {@code onEnd(CLOSED)} after {@code CAPTURE_READY} is normal (authentication flows close
 * the stream before capture); any other end or error before {@code CAPTURE_READY} fails the session with
 * {@code FAILED(DEVICE)}. Calls that do not fit the current state are ignored, never thrown.
 */
public interface LivenessSession extends FrameSink, AutoCloseable {

    /**
     * Returns the session identifier (random, not derived from the subject).
     *
     * @return the identifier
     */
    String sessionId();

    /**
     * Returns the current state; safe from any thread.
     *
     * @return the state
     */
    SessionState state();

    /** Starts the session: {@code IDLE -> OPENING}; emits audit {@code REG-EVT-LV01} and the first UI state. */
    void start();

    /**
     * Evaluates timers (first-frame deadline, passive and challenge timeouts, no-frame watchdog) using the
     * session clock. The owner calls it at least every 100 ms, also when no frames arrive.
     */
    void tick();

    /**
     * Returns the pass token.
     *
     * @return the token in {@code CAPTURE_READY} and {@code CAPTURING}; empty otherwise
     */
    Optional<LivenessPassToken> passToken();

    /**
     * Returns the frontal, neutral frame the session selected for storage. An in-process device (the Android
     * built-in L0 camera) signs this frame instead of taking a new picture.
     *
     * @return the frame in {@code CAPTURE_READY} and {@code CAPTURING}; empty otherwise
     */
    Optional<FaceFrame> captureFrame();

    /** Tells the session that the signed capture has started: {@code CAPTURE_READY -> CAPTURING}. */
    void captureStarted();

    /**
     * Tells the session that the signed capture failed: {@code CAPTURING -> FAILED(DEVICE)}.
     *
     * @param error the device error
     */
    void captureFailed(DeviceError error);

    /**
     * Checks the signed capture against the live session (stream-to-capture binding, docs/design/engine.md section 7):
     * token valid, unused and bound to this session, workflow, subject and device; challenge echoed when
     * requested; exactly one face; similarity to the session face; time window; PAD on the captured image; no
     * device PAD failure. Moves {@code CAPTURING -> BINDING -> PASSED} or {@code FAILED(BINDING | PAD)}. The
     * token is consumed whatever the verdict.
     *
     * @param request the capture and the presented token
     * @return the verdict; a failed verdict if the session is not in {@code CAPTURING}
     */
    BindingVerdict bind(BindingRequest request);

    /**
     * Returns the result.
     *
     * @return the result in {@code PASSED}, {@code FAILED} and {@code CANCELLED}; empty otherwise
     */
    Optional<LivenessResult> result();

    /** Cancels the session ({@code -> CANCELLED}); safe from any thread; no effect once terminal. */
    void cancel();

    /** Cancels the session if it is still running and overwrites all frame buffers it holds. Idempotent. */
    @Override
    void close();
}
