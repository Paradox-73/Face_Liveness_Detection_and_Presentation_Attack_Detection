package io.mosip.liveness.core.api;

/**
 * Outcome of the PAD challenge echo check during binding.
 */
public enum ChallengeEchoStatus {

    /** The device has no {@code padCapability}, so no challenge was sent. */
    NOT_REQUESTED,
    /** The device echoed the nonce of the pass token. */
    MATCHED,
    /** The device echoed a different value. */
    MISMATCHED,
    /** A challenge was sent but the signed capture carries no echo. */
    MISSING;

    /**
     * Tells whether the echo check allows binding to pass.
     *
     * @return {@code true} for {@link #NOT_REQUESTED} and {@link #MATCHED}
     */
    public boolean isAcceptable() {
        return this == NOT_REQUESTED || this == MATCHED;
    }
}
