/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.config;

import java.util.Locale;
import java.util.Optional;

/**
 * How liveness is enforced for a workflow ({@code mosip.registration.liveness.policy.<workflow>}).
 *
 * <p>Passive detection always runs first when liveness is on. The modes differ in what happens next
 * (docs/design/decision-flow.md section 5).
 */
public enum PolicyMode {

    /** No liveness check; the capture proceeds and is recorded with method {@code NONE}. */
    OFF,

    /** Passive only: pass at or above the passive threshold, otherwise fail ({@code BELOW_THRESHOLD}). */
    PASSIVE_ONLY,

    /**
     * Passive first; pass at or above the passive threshold, otherwise escalate to active challenges. The default
     * for every workflow except {@code exception_photo}.
     */
    PASSIVE_THEN_ACTIVE,

    /** Passive score is computed and audited, then active challenges always follow (strict profile). */
    ALWAYS_ACTIVE;

    /**
     * Parses a configuration value, ignoring case and surrounding spaces.
     *
     * @param value value such as {@code "PASSIVE_THEN_ACTIVE"}; may be {@code null}
     * @return the mode, or empty if unknown
     */
    public static Optional<PolicyMode> fromName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String wanted = value.trim().toUpperCase(Locale.ROOT);
        for (PolicyMode m : values()) {
            if (m.name().equals(wanted)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
