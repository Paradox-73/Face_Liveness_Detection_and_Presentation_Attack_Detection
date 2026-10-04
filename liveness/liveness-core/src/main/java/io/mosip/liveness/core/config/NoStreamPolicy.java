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
 * What to do when the face device cannot stream ({@code mosip.registration.liveness.no_stream_policy}).
 */
public enum NoStreamPolicy {

    /** Refuse the capture; the user sees "This face camera does not support live verification". The default. */
    BLOCK,

    /**
     * Allow the signed capture without liveness; it is recorded with method {@code CAPTURE_ONLY} so reviewers
     * can see that no liveness check ran.
     */
    CAPTURE_ONLY;

    /**
     * Parses a configuration value, ignoring case and surrounding spaces.
     *
     * @param value value such as {@code "BLOCK"}; may be {@code null}
     * @return the policy, or empty if unknown
     */
    public static Optional<NoStreamPolicy> fromName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String wanted = value.trim().toUpperCase(Locale.ROOT);
        for (NoStreamPolicy p : values()) {
            if (p.name().equals(wanted)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
