package io.mosip.liveness.core;

/**
 * Why a session ended in {@link SessionState#FAILED} or {@link SessionState#CANCELLED} (design section 4.5).
 *
 * <p>Each reason fixes three things a client needs without further logic: the coarse {@link Decision},
 * whether the attempt counts towards {@code max_retries}, and whether a supervisor override may follow it.
 */
public enum FailureReason {

    /** The device was unavailable, disconnected, stalled or did not become ready. Not counted. */
    DEVICE(Decision.ERROR, false, true),

    /** No usable face (none, several, poor quality or off pose) before the passive timeout. Not counted. */
    QUALITY(Decision.FAIL_QUALITY, false, true),

    /** Passive PAD score below {@code pad.reject_threshold}, or a device PAD block reporting an attack. Counted. */
    PAD(Decision.FAIL_PAD, true, false),

    /** Frame integrity or planarity violation: frozen, looping, planar, face switch, invalid frames. Counted. */
    INTEGRITY(Decision.FAIL_PAD, true, false),

    /** A challenge was answered with the wrong action, too fast, or not held. Counted. */
    ACTIVE(Decision.FAIL_LIVENESS, true, true),

    /** The person did not act before the challenge or neutral-face deadline. Counted. */
    TIMEOUT(Decision.TIMEOUT, true, true),

    /** Passive score below the workflow threshold while active checks are disabled by policy. Counted. */
    BELOW_THRESHOLD(Decision.FAIL_LIVENESS, true, true),

    /**
     * The signed capture does not match the live session (token, device, time window, similarity or challenge
     * echo). Counted. Treated as a suspected attack, so no override may follow; the design names only PAD and
     * integrity failures, and this is the stricter reading.
     */
    BINDING(Decision.FAIL_PAD, true, false),

    /** The engine could not load its models or run inference (fails closed). Not counted. */
    ENGINE(Decision.ERROR, false, true),

    /** Cancelled by the caller or the user. Not counted. */
    CANCELLED(Decision.CANCELLED, false, true);

    private final Decision decision;
    private final boolean countsAsAttempt;
    private final boolean allowsOverride;

    FailureReason(Decision decision, boolean countsAsAttempt, boolean allowsOverride) {
        this.decision = decision;
        this.countsAsAttempt = countsAsAttempt;
        this.allowsOverride = allowsOverride;
    }

    /**
     * Returns the coarse outcome shown to the client for this reason.
     *
     * @return the decision
     */
    public Decision decision() {
        return decision;
    }

    /**
     * Tells whether a failure for this reason uses up one of the workflow's {@code max_retries} attempts.
     *
     * @return {@code true} for PAD, INTEGRITY, ACTIVE, TIMEOUT, BELOW_THRESHOLD and BINDING
     */
    public boolean countsAsAttempt() {
        return countsAsAttempt;
    }

    /**
     * Tells whether a supervisor override may be offered after this failure (when the workflow allows
     * {@code SUPERVISOR_OVERRIDE}). An override is never allowed after a suspected attack.
     *
     * @return {@code false} for PAD, INTEGRITY and BINDING
     */
    public boolean allowsOverride() {
        return allowsOverride;
    }
}
