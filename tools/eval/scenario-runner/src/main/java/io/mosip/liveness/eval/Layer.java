package io.mosip.liveness.eval;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessResult;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The defence layer that ended an attempt, for the stage attribution of design section 10.6 ("share of attacks
 * stopped at the passive vs the active stage").
 */
enum Layer {

    /** The attempt passed. */
    NONE,
    /** Passive PAD score below the reject threshold, or below the passive threshold with passive-only policy. */
    PASSIVE_PAD,
    /** Rolling PAD score during the challenges below the reject threshold. */
    ACTIVE_PAD,
    /** Frozen, looped, reordered, too slow or switched stream. */
    INTEGRITY,
    /** Head-turn landmarks fit a plane (flat photo or screen). */
    PLANARITY,
    /** Wrong action, too-fast onset, not held, or no action in time. */
    CHALLENGE,
    /** The signed capture did not match the live session (including PAD on the captured image). */
    BINDING,
    /** No usable face in time (no face, several faces, too dark, blurred, off pose). */
    QUALITY,
    /** Device, engine or runner failure. */
    ERROR;

    private static final Set<ReasonCode> INTEGRITY_CODES = EnumSet.of(ReasonCode.FROZEN, ReasonCode.LOOP,
            ReasonCode.TIMESTAMP_REGRESSION, ReasonCode.INVALID_FRAMES, ReasonCode.TRACKING_LOST,
            ReasonCode.FACE_SWITCH);
    private static final Set<ReasonCode> CHALLENGE_CODES = EnumSet.of(ReasonCode.WRONG_ACTION,
            ReasonCode.ONSET_TOO_FAST, ReasonCode.NOT_HELD, ReasonCode.CHALLENGE_TIMEOUT, ReasonCode.NEUTRAL_TIMEOUT);

    /**
     * Finds the layer that ended an attempt.
     *
     * @param result the session result
     * @return the layer
     */
    static Layer of(LivenessResult result) {
        if (result.isPass()) {
            return NONE;
        }
        FailureReason reason = result.failureReason().orElse(FailureReason.ENGINE);
        Set<ReasonCode> codes = result.reasonCodes();
        switch (reason) {
            case BINDING:
                return BINDING;
            case QUALITY:
                return QUALITY;
            case DEVICE:
            case ENGINE:
            case CANCELLED:
                return ERROR;
            case BELOW_THRESHOLD:
                return PASSIVE_PAD;
            case ACTIVE:
            case TIMEOUT:
                return CHALLENGE;
            default:
                break;
        }
        if (codes.contains(ReasonCode.PLANAR)) {
            return PLANARITY;
        }
        for (ReasonCode c : codes) {
            if (INTEGRITY_CODES.contains(c)) {
                return INTEGRITY;
            }
        }
        if (codes.contains(ReasonCode.CAPTURE_PAD_SCORE) || codes.contains(ReasonCode.DEVICE_PAD)) {
            return BINDING;
        }
        for (ReasonCode c : codes) {
            if (CHALLENGE_CODES.contains(c)) {
                return CHALLENGE;
            }
        }
        return isActive(result) ? ACTIVE_PAD : PASSIVE_PAD;
    }

    private static boolean isActive(LivenessResult result) {
        LivenessMethod m = result.method();
        return m == LivenessMethod.PASSIVE_ACTIVE || m == LivenessMethod.ACTIVE || !result.challenges().isEmpty();
    }

    /**
     * Returns the lower-case name used in scenario files and JSON output.
     *
     * @return for example {@code "passive_pad"}
     */
    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a lower- or upper-case name.
     *
     * @param value for example {@code "planarity"}
     * @return the layer
     * @throws IllegalArgumentException if the name is unknown
     */
    static Layer fromId(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
