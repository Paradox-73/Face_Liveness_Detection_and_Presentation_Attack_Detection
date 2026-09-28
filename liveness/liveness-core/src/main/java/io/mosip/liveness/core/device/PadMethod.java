package io.mosip.liveness.core.device;

import java.util.Locale;
import java.util.Optional;

/**
 * Presentation attack detection method a device is asked to apply, in the shape proposed by MOSIP issue
 * {@code android-registration-client#1117} ({@code "pad": {"method": "any|active|passive"}}).
 */
public enum PadMethod {

    /** Any method the device supports. */
    ANY,
    /** Active (challenge-response) detection. */
    ACTIVE,
    /** Passive (single-image or stream) detection. */
    PASSIVE;

    /**
     * Returns the lower-case value used on the wire.
     *
     * @return {@code "any"}, {@code "active"} or {@code "passive"}
     */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a wire value, ignoring case.
     *
     * @param value value such as {@code "passive"}; may be {@code null}
     * @return the method, or empty if unknown
     */
    public static Optional<PadMethod> fromWireValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String wanted = value.trim().toUpperCase(Locale.ROOT);
        for (PadMethod m : values()) {
            if (m.name().equals(wanted)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
