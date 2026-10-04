/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.session;

/**
 * What the capture screen drives: one capture transaction with its retries. {@link LivenessRunner} runs the
 * liveness check; {@link PhotoCaptureRunner} takes a signed photo when the workflow's liveness policy is off.
 */
public interface CaptureRunner extends AutoCloseable {

    /** Starts the first attempt. */
    void start();

    /**
     * Starts the next attempt after a failed one.
     *
     * @return {@code false} if no retry is available
     */
    boolean retry();

    /** Cancels the running attempt, if any. Safe from any thread. */
    void cancel();

    /**
     * Returns the processing time of the last attempt's frames, for the diagnostic view.
     *
     * @return for example {@code frames=42 p50=31.2ms p95=48.0ms max=61.5ms}, or an empty string
     */
    default String lastFrameTimes() {
        return "";
    }

    /** Cancels the running attempt, releases the camera and stops the worker. Idempotent. */
    @Override
    void close();
}
