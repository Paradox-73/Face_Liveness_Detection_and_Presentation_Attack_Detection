/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.config;

import io.mosip.liveness.core.Workflow;

import java.util.Locale;
import java.util.Optional;

/**
 * What happens when a person has used all liveness attempts of a workflow
 * ({@code mosip.registration.liveness.on_max_failure} and {@code <workflow>.on_max_failure}).
 */
public enum OnMaxFailure {

    /** The flow stops; the recovery process for the workflow applies. Allowed for every workflow; the default. */
    BLOCK(true, true),

    /**
     * Resident workflows only: a supervisor may accept the capture. It is stored with
     * {@code LIVENESS_DECISION=OVERRIDE}, the packet is flagged for review and audit {@code REG-EVT-LV16} is
     * written. Never offered after a suspected attack.
     */
    SUPERVISOR_OVERRIDE(true, false),

    /**
     * Authentication workflows only: stop and also count a failed login
     * ({@code mosip.registration.invalid_login_count} / {@code invalid_login_time}).
     */
    BLOCK_AND_LOCK(false, true);

    private final boolean residentAllowed;
    private final boolean authAllowed;

    OnMaxFailure(boolean residentAllowed, boolean authAllowed) {
        this.residentAllowed = residentAllowed;
        this.authAllowed = authAllowed;
    }

    /**
     * Tells whether this behaviour may be configured for a workflow.
     *
     * @param workflow the workflow
     * @return {@code true} if allowed
     */
    public boolean isAllowedFor(Workflow workflow) {
        return workflow.isAuth() ? authAllowed : residentAllowed;
    }

    /**
     * Parses a configuration value, ignoring case and surrounding spaces.
     *
     * @param value value such as {@code "BLOCK"}; may be {@code null}
     * @return the behaviour, or empty if unknown
     */
    public static Optional<OnMaxFailure> fromName(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String wanted = value.trim().toUpperCase(Locale.ROOT);
        for (OnMaxFailure m : values()) {
            if (m.name().equals(wanted)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
