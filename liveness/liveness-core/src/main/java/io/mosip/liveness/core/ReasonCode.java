package io.mosip.liveness.core;

/**
 * Fine-grained reasons recorded in results, audit payloads and the diagnostic log.
 *
 * <p>The constant name is the wire value (for example the {@code reason} of audit event
 * {@code REG-EVT-LV08} is {@code PAD_SCORE}, {@code FROZEN}, {@code LOOP}, {@code PLANAR},
 * {@code FACE_SWITCH} or {@code DEVICE_PAD}). Reason codes never reach the screen: the user sees the message
 * chosen for the {@link Decision}, plus guidance for quality problems.
 */
public enum ReasonCode {

    // ---- Acquisition and image quality (FailureReason.QUALITY, and guidance while acquiring) ----

    /** No face found in the frame. */
    NO_FACE,
    /** More than one face found in the frame. */
    MULTI_FACE,
    /** Face smaller than {@code quality.min_ied_px}. */
    FACE_TOO_SMALL,
    /** Face partly outside the frame or the capture oval. */
    FACE_OUT_OF_AREA,
    /** Head turned or tilted beyond {@code quality.max_yaw_deg} while acquiring. */
    OFF_POSE,
    /** Face region too dark. */
    TOO_DARK,
    /** Face region too bright or washed out. */
    TOO_BRIGHT,
    /** Face region not sharp enough. */
    BLURRY,
    /** Generic poor quality when no more specific code applies. */
    POOR_QUALITY,
    /**
     * Passive PAD failed on a face lit below {@code quality.low_light_brightness} (but above the TOO_DARK floor):
     * the session ends as {@code FailureReason.QUALITY}, not counted, with "Improve lighting", at most
     * {@code passive.low_light_max_uncounted} times in a row; the passive score stays in the result.
     */
    LOW_LIGHT,

    // ---- Passive decision ----

    /** Passive score at or above {@code pad.reject_threshold} but below the workflow's passive threshold. */
    BELOW_THRESHOLD,
    /** Active checks were started because the policy is {@code ALWAYS_ACTIVE} (audit LV03 reason), not a failure. */
    POLICY,

    // ---- Presentation attack and frame integrity (FailureReason.PAD / INTEGRITY) ----

    /** Median passive PAD score below {@code pad.reject_threshold}. */
    PAD_SCORE,
    /** Identical or near-identical frames for more than {@code integrity.max_frozen_frames} frames. */
    FROZEN,
    /** A repeating sequence of frame fingerprints (replayed loop). */
    LOOP,
    /** Landmark motion during a head turn fits a flat plane (photo or screen). */
    PLANAR,
    /** The face identity changed during the session. */
    FACE_SWITCH,
    /** Frame timestamps or sequence numbers went backwards. */
    TIMESTAMP_REGRESSION,
    /** Measured frame rate below {@code integrity.min_fps}, or too many corrupt frames. */
    INVALID_FRAMES,
    /** The tracked face was lost (low overlap between frames or a gap longer than 500 ms). */
    TRACKING_LOST,
    /** A device-side PAD block (SBI issue #1117 shape) reported an attack. */
    DEVICE_PAD,

    // ---- Active challenges (FailureReason.ACTIVE / TIMEOUT) ----

    /** A different action than the prompted one was detected. */
    WRONG_ACTION,
    /** The action started faster than {@code active.min_onset_ms} after the prompt (anticipation or script). */
    ONSET_TOO_FAST,
    /** The action was not held for {@code active.hold_ms}. */
    NOT_HELD,
    /** No action before {@code active.challenge_timeout_ms}. */
    CHALLENGE_TIMEOUT,
    /** No neutral face before the prompt could be shown. */
    NEUTRAL_TIMEOUT,
    /** Acquisition and passive scoring did not finish within {@code passive.timeout_ms}. */
    PASSIVE_TIMEOUT,

    // ---- Stream-to-capture binding (FailureReason.BINDING) ----

    /** No pass token was presented with the capture. */
    TOKEN_MISSING,
    /** The pass token was already used. */
    TOKEN_REUSED,
    /** The pass token expired before the capture arrived. */
    TOKEN_EXPIRED,
    /** The pass token belongs to another session, workflow or subject. */
    TOKEN_MISMATCH,
    /** The capture was signed by a different device serial than the one that streamed. */
    DEVICE_MISMATCH,
    /** The capture timestamp lies outside the allowed window after the pass. */
    OUTSIDE_TIME_WINDOW,
    /** The device did not echo the PAD challenge nonce it was sent. */
    CHALLENGE_ECHO_MISMATCH,
    /** No face in the captured image. */
    CAPTURE_NO_FACE,
    /** More than one face in the captured image. */
    CAPTURE_MULTI_FACE,
    /** Captured face not similar enough to the session reference ({@code binding.min_similarity}). */
    SIMILARITY_LOW,
    /** PAD score of the captured image below {@code pad.reject_threshold}. */
    CAPTURE_PAD_SCORE,

    // ---- Device and engine (FailureReason.DEVICE / ENGINE); detail is in the error code ----

    /** A device error ended the session; see the result's device error code. */
    DEVICE_ERROR,
    /** An engine error ended the session; see the result's engine error code. */
    ENGINE_ERROR,

    // ---- Cancellation ----

    /** The caller or the user cancelled the session. */
    CANCELLED_BY_USER
}
