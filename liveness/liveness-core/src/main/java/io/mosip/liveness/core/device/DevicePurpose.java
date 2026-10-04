/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

import java.util.Optional;

/**
 * SBI device purpose ({@code purpose} in discovery and device information).
 */
public enum DevicePurpose {

    /** Registration device; streams preview frames. */
    REGISTRATION("Registration"),
    /** Authentication device; SBI does not allow streaming for this purpose. */
    AUTH("Auth");

    private final String code;

    DevicePurpose(String code) {
        this.code = code;
    }

    /**
     * Returns the value used on the wire.
     *
     * @return {@code "Registration"} or {@code "Auth"}
     */
    public String code() {
        return code;
    }

    /**
     * Parses a wire value, ignoring case and surrounding spaces.
     *
     * @param code value such as {@code "Registration"}; may be {@code null}
     * @return the purpose, or empty if unknown
     */
    public static Optional<DevicePurpose> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String wanted = code.trim();
        for (DevicePurpose p : values()) {
            if (p.code.equalsIgnoreCase(wanted)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
