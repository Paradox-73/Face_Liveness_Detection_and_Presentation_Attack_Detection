/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

/**
 * Outcome of one challenge.
 */
public enum ChallengeOutcome {

    /** The prompted action was detected, started after the minimum onset and held long enough. */
    PASSED("OK"),
    /** A different action was detected. */
    WRONG_ACTION("WRONG_ACTION"),
    /** The action started faster than {@code active.min_onset_ms}. */
    ONSET_TOO_FAST("ONSET_TOO_FAST"),
    /** The action was not held for {@code active.hold_ms}. */
    NOT_HELD("NOT_HELD"),
    /** No action before {@code active.challenge_timeout_ms}. */
    TIMEOUT("TIMEOUT"),
    /** The median PAD score during the challenge fell below {@code pad.reject_threshold}. */
    PAD_FAILED("PAD_FAILED"),
    /** The session was cancelled during the challenge. */
    CANCELLED("CANCELLED");

    private final String evidenceCode;

    ChallengeOutcome(String evidenceCode) {
        this.evidenceCode = evidenceCode;
    }

    /**
     * Returns the code used in packet evidence, for example {@code BLINK:OK}.
     *
     * @return {@code "OK"} for a pass, otherwise the constant name
     */
    public String evidenceCode() {
        return evidenceCode;
    }
}
