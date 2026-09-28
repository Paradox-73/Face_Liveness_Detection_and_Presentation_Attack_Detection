package io.mosip.liveness.core;

/**
 * The overall outcome of a liveness session, at the level a client acts on.
 *
 * <p>The finer {@link FailureReason} and {@link ReasonCode}s explain a failure. Screens never show which
 * signal failed for {@link #FAIL_PAD}: they show one generic message so an attacker cannot tune against it.
 */
public enum Decision {

    /** Liveness passed and the capture is bound to the live session. */
    PASS,

    /** The person did not satisfy liveness: passive score below threshold, or a challenge not performed. */
    FAIL_LIVENESS,

    /** A presentation or injection attack is suspected (PAD score, frame integrity, planarity, binding, device PAD). */
    FAIL_PAD,

    /** No usable face could be acquired in time (no face, several faces or poor image quality). */
    FAIL_QUALITY,

    /** The person ran out of time for a challenge or for the waiting phase. */
    TIMEOUT,

    /** The device or the engine failed; the attempt is not held against the person. */
    ERROR,

    /** The session was cancelled by the caller or the user. */
    CANCELLED;

    /**
     * Tells whether this outcome lets the capture be used.
     *
     * @return {@code true} only for {@link #PASS}
     */
    public boolean isPass() {
        return this == PASS;
    }
}
