package io.mosip.registration.livenessmanager.auth;

/**
 * How an operator or supervisor face authentication ended. The name is also the error code the Flutter screens
 * receive (for example {@code FACE_MISMATCH}).
 */
public enum FaceAuthStatus {
    /** Liveness passed and the face matches the user's enrolled face. */
    SUCCESS,
    /** Liveness is switched off for the workflow; the caller uses its other authentication method. */
    NOT_ENFORCED,
    /** The person closed the capture screen before any attempt ended. */
    CANCELLED,
    /** The user has no enrolled face on this device (not onboarded with a face, or the rows were removed). */
    FACE_NOT_ENROLLED,
    /** The workflow needs a supervisor and the user does not hold the supervisor role. */
    NOT_SUPERVISOR,
    /** The user account is locked after repeated failures. */
    USER_LOCKED,
    /** Liveness failed and the person closed the screen while a retry was still allowed. */
    LIVENESS_FAILED,
    /** Liveness failed on every allowed attempt. */
    MAX_ATTEMPTS,
    /** Liveness passed but the face is not the user's enrolled face. */
    FACE_MISMATCH,
    /** The liveness or matching models are not available (loading failed or integrity check failed). */
    ENGINE_UNAVAILABLE,
    /** Another face capture is already open. */
    BUSY
}
