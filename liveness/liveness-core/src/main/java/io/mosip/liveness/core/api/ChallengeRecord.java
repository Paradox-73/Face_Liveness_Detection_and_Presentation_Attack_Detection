/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.ChallengeType;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * What happened in one challenge of a session.
 */
public final class ChallengeRecord {

    private final ChallengeType type;
    private final int index;
    private final ChallengeOutcome outcome;
    private final Long onsetMs;
    private final Long holdMs;
    private final long durationMs;

    private ChallengeRecord(ChallengeType type, int index, ChallengeOutcome outcome, Long onsetMs, Long holdMs,
            long durationMs) {
        this.type = type;
        this.index = index;
        this.outcome = outcome;
        this.onsetMs = onsetMs;
        this.holdMs = holdMs;
        this.durationMs = durationMs;
    }

    /**
     * Records a passed challenge.
     *
     * @param type       challenge type
     * @param index      0-based position in the session
     * @param onsetMs    time from prompt to action onset, in milliseconds
     * @param holdMs     time the action was held, in milliseconds
     * @param durationMs time from prompt to completion, in milliseconds
     * @return the record
     */
    public static ChallengeRecord passed(ChallengeType type, int index, long onsetMs, long holdMs, long durationMs) {
        check(type, index, durationMs);
        if (onsetMs < 0 || holdMs < 0) {
            throw new IllegalArgumentException("onsetMs and holdMs must not be negative");
        }
        return new ChallengeRecord(type, index, ChallengeOutcome.PASSED, onsetMs, holdMs, durationMs);
    }

    /**
     * Records a challenge that did not pass.
     *
     * @param type       challenge type
     * @param index      0-based position in the session
     * @param outcome    any outcome except {@link ChallengeOutcome#PASSED}
     * @param durationMs time from prompt to the end of the challenge, in milliseconds
     * @return the record
     */
    public static ChallengeRecord failed(ChallengeType type, int index, ChallengeOutcome outcome, long durationMs) {
        check(type, index, durationMs);
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == ChallengeOutcome.PASSED) {
            throw new IllegalArgumentException("use passed() for a passed challenge");
        }
        return new ChallengeRecord(type, index, outcome, null, null, durationMs);
    }

    private static void check(ChallengeType type, int index, long durationMs) {
        Objects.requireNonNull(type, "type");
        if (index < 0 || durationMs < 0) {
            throw new IllegalArgumentException("index and durationMs must not be negative");
        }
    }

    /**
     * Returns the challenge type.
     *
     * @return the type
     */
    public ChallengeType type() {
        return type;
    }

    /**
     * Returns the position in the session.
     *
     * @return 0-based index
     */
    public int index() {
        return index;
    }

    /**
     * Returns the outcome.
     *
     * @return the outcome
     */
    public ChallengeOutcome outcome() {
        return outcome;
    }

    /**
     * Returns the time from prompt to action onset.
     *
     * @return milliseconds, for a passed challenge
     */
    public OptionalLong onsetMs() {
        return onsetMs == null ? OptionalLong.empty() : OptionalLong.of(onsetMs);
    }

    /**
     * Returns the time the action was held.
     *
     * @return milliseconds, for a passed challenge
     */
    public OptionalLong holdMs() {
        return holdMs == null ? OptionalLong.empty() : OptionalLong.of(holdMs);
    }

    /**
     * Returns the time from prompt to the end of the challenge.
     *
     * @return milliseconds
     */
    public long durationMs() {
        return durationMs;
    }

    /**
     * Returns the packet evidence form, for example {@code BLINK:OK} or {@code SMILE:TIMEOUT}.
     *
     * @return {@code TYPE:CODE}
     */
    public String evidence() {
        return type.name() + ":" + outcome.evidenceCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChallengeRecord)) {
            return false;
        }
        ChallengeRecord r = (ChallengeRecord) o;
        return type == r.type && index == r.index && outcome == r.outcome && Objects.equals(onsetMs, r.onsetMs)
                && Objects.equals(holdMs, r.holdMs) && durationMs == r.durationMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, index, outcome, onsetMs, holdMs, durationMs);
    }

    @Override
    public String toString() {
        return "ChallengeRecord[" + index + " " + evidence() + ", onsetMs=" + onsetMs + ", holdMs=" + holdMs
                + ", durationMs=" + durationMs + "]";
    }
}
