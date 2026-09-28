package io.mosip.liveness.core.ui;

/**
 * The screen state a client shows (design section 11.1). Both clients render the same phases with the same
 * layout: oval guide in the centre, prompt banner on top, step indicator and attempt counter at the bottom.
 */
public enum UiPhase {

    /** The camera stream is opening. */
    STARTING,
    /** The person is being positioned; guidance messages are shown. */
    POSITIONING,
    /** "Checking face liveness..." while the passive check runs; no scores and no buttons. */
    CHECKING,
    /** A challenge prompt is shown (step i of k). */
    CHALLENGE,
    /** "Action detected" after a challenge. */
    ACTION_DETECTED,
    /** "Hold still" while the signed capture is taken. */
    HOLD_STILL,
    /** The capture is being checked against the session. */
    VERIFYING,
    /** Success; the face was captured. */
    SUCCESS,
    /** The attempt failed; a retry may be available. */
    FAILED,
    /** No attempts are left; the recovery message (and an override, if allowed) is shown. */
    MAX_ATTEMPTS,
    /** The face device is unavailable, disconnected or cannot stream. */
    DEVICE_ERROR,
    /** The session was cancelled. */
    CANCELLED;

    /**
     * Tells whether the attempt has ended in this phase.
     *
     * @return {@code true} for SUCCESS, FAILED, MAX_ATTEMPTS, DEVICE_ERROR and CANCELLED
     */
    public boolean isTerminal() {
        return ordinal() >= SUCCESS.ordinal();
    }
}
