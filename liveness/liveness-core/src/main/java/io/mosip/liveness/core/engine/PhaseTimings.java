/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

/**
 * The phase timestamps of one session (monotonic nanoseconds) and the passive, active and total durations its
 * result reports. A phase not reached yet is {@code -1}. Session thread only.
 */
final class PhaseTimings {

    private long start;
    private long passiveEnd = -1;
    private long activeStart = -1;
    private long activeEnd = -1;

    void started(long now) {
        start = now;
    }

    long start() {
        return start;
    }

    void passiveEnded(long now) {
        passiveEnd = now;
    }

    void activeStarted(long now) {
        activeStart = now;
    }

    void activeEnded(long now) {
        activeEnd = now;
    }

    /** Milliseconds since the session started. */
    long sinceStartMs(long now) {
        return (now - start) / 1_000_000L;
    }

    /**
     * Returns the durations of a session that ends at {@code end}: a phase still running ends there, and the
     * passive and active parts never add up to more than the total.
     *
     * @return {@code {passiveMs, activeMs, totalMs}}
     */
    long[] durationsMs(long end) {
        long total = Math.max(0, (end - start) / 1_000_000L);
        long passiveMs = Math.max(0, ((passiveEnd >= 0 ? passiveEnd : end) - start) / 1_000_000L);
        long activeMs = activeStart < 0 ? 0
                : Math.max(0, ((activeEnd >= 0 ? activeEnd : end) - activeStart) / 1_000_000L);
        long passive = Math.min(passiveMs, total);
        return new long[] {passive, Math.min(activeMs, total - passive), total};
    }
}
