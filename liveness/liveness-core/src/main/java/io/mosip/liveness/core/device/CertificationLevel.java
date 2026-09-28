package io.mosip.liveness.core.device;

import java.util.Locale;
import java.util.Optional;

/**
 * SBI device trust level, as reported in the signed device information ({@code certification}).
 */
public enum CertificationLevel {

    /** Keys held in software; trusted only in controlled environments. */
    L0,
    /** Keys held in a secure chip (Foundational Trust Module). */
    L1;

    /**
     * Returns the value used on the wire and in packet evidence ({@code LIVENESS_DEVICE_LEVEL}).
     *
     * @return {@code "L0"} or {@code "L1"}
     */
    public String code() {
        return name();
    }

    /**
     * Parses a wire value, ignoring case and surrounding spaces.
     *
     * @param code value such as {@code "L0"}; may be {@code null}
     * @return the level, or empty if unknown
     */
    public static Optional<CertificationLevel> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String wanted = code.trim().toUpperCase(Locale.ROOT);
        for (CertificationLevel l : values()) {
            if (l.name().equals(wanted)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }
}
