package io.mosip.liveness.core.api;

/**
 * Which checks decided a session, recorded as packet evidence {@code LIVENESS_METHOD}.
 */
public enum LivenessMethod {

    /** Policy {@code OFF}: no liveness check ran. */
    NONE,
    /** The passive check alone accepted the face. */
    PASSIVE,
    /** The passive score was not enough and the challenges decided ({@code PASSIVE_THEN_ACTIVE} escalation). */
    PASSIVE_ACTIVE,
    /** Policy {@code ALWAYS_ACTIVE}: the passive score was recorded, the challenges decided. */
    ACTIVE,
    /** The device could not stream and {@code no_stream_policy=CAPTURE_ONLY}: signed capture without liveness. */
    CAPTURE_ONLY
}
