/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core;

/**
 * States of one liveness session (docs/design/engine.md section 3).
 *
 * <p>A session moves forward only. {@link #PASSED}, {@link #FAILED} and {@link #CANCELLED} are terminal; the
 * reason for a failure is carried separately as a {@link FailureReason}. Retrying means opening a new session.
 */
public enum SessionState {

    /** Created, not started. */
    IDLE,

    /** Opening the face stream and waiting for the first valid frame (deadline 3 s). */
    OPENING,

    /** Quality gate: exactly one face, large enough, frontal, lit and sharp; guidance is shown. */
    ACQUIRING,

    /** Collecting good frames and scoring them with passive presentation attack detection (PAD). */
    PASSIVE,

    /** A challenge has been drawn; waiting a random delay and for a neutral face before the prompt. */
    CHALLENGE_WAIT,

    /** The prompt is shown and the requested action is being observed; PAD keeps running. */
    CHALLENGE_OBSERVE,

    /**
     * The action was detected; "Action detected" is shown briefly before the next step. After the last challenge the
     * session stays here while "Look directly at the camera and hold still" waits for a neutral, frontal face that
     * gives the stored frame.
     */
    CHALLENGE_PASSED,

    /** Liveness is satisfied; a single-use pass token exists and the caller may start the signed capture. */
    CAPTURE_READY,

    /** The caller has started the signed capture (rCapture) and the session waits for it. */
    CAPTURING,

    /** The signed capture is being compared with the live session (stream-to-capture binding). */
    BINDING,

    /** Terminal: liveness passed and the capture is bound to the session. */
    PASSED,

    /** Terminal: liveness failed; see the result's {@link FailureReason}. */
    FAILED,

    /** Terminal: the caller or the user cancelled the session. */
    CANCELLED;

    /**
     * Tells whether the session has ended in this state.
     *
     * @return {@code true} for {@link #PASSED}, {@link #FAILED} and {@link #CANCELLED}
     */
    public boolean isTerminal() {
        return this == PASSED || this == FAILED || this == CANCELLED;
    }

    /**
     * Tells whether frames from the stream are still being analysed in this state.
     *
     * @return {@code true} from {@link #OPENING} to {@link #CAPTURE_READY} inclusive
     */
    public boolean isObservingStream() {
        return ordinal() >= OPENING.ordinal() && ordinal() <= CAPTURE_READY.ordinal();
    }

    /**
     * Tells whether this state belongs to the active (challenge) phase.
     *
     * @return {@code true} for the three {@code CHALLENGE_*} states
     */
    public boolean isChallengePhase() {
        return this == CHALLENGE_WAIT || this == CHALLENGE_OBSERVE || this == CHALLENGE_PASSED;
    }
}
