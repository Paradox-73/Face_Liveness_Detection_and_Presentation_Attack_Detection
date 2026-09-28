package io.mosip.liveness.core.device;

import java.util.Optional;

/**
 * Device status as reported by SBI discovery ({@code deviceStatus}).
 *
 * <p>In authentication flows the client closes the preview stream after the pass and polls the status every
 * 100 ms until {@link #READY}, because the device reports {@link #BUSY} while it streams and the capture call
 * for authentication requires {@code Ready}.
 */
public enum DeviceStatus {

    /** Ready for a stream or a capture. */
    READY("Ready"),
    /** Streaming or capturing for another request. */
    BUSY("Busy"),
    /** Connected but not ready. */
    NOT_READY("Not Ready"),
    /** Not registered with the MOSIP server. */
    NOT_REGISTERED("Not Registered"),
    /** No device answered. */
    NOT_FOUND("");

    private final String code;

    DeviceStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the SBI wire value.
     *
     * @return for example {@code "Ready"}; empty for {@link #NOT_FOUND}, which has no wire value
     */
    public String code() {
        return code;
    }

    /**
     * Parses an SBI wire value, ignoring case and surrounding spaces.
     *
     * @param code value such as {@code "Ready"}; may be {@code null}
     * @return the status, or empty if unknown
     */
    public static Optional<DeviceStatus> fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return Optional.empty();
        }
        String wanted = code.trim();
        for (DeviceStatus s : values()) {
            if (!s.code.isEmpty() && s.code.equalsIgnoreCase(wanted)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
