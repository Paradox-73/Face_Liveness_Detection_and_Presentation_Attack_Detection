/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.Clock;

import java.time.Instant;

/**
 * Stream-time clock for replayed frame packs: time moves only when the runner advances it, one frame period at a
 * time, so every session timeout behaves as it would on a live stream at the pack's frame rate, however fast or
 * slow the models run on this machine.
 *
 * <p>Wall-clock processing time is measured separately with {@link System#nanoTime()}.
 */
final class ManualClock implements Clock {

    /** Fixed wall-clock origin, so that runs are reproducible. */
    static final Instant ORIGIN = Instant.parse("2026-01-01T00:00:00Z");

    private long nanos;

    @Override
    public long monotonicNanos() {
        return nanos;
    }

    @Override
    public Instant now() {
        return ORIGIN.plusNanos(nanos);
    }

    /**
     * Moves the clock forward.
     *
     * @param delta nanoseconds; must not be negative
     */
    void advanceNanos(long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("time cannot go backwards: " + delta);
        }
        nanos += delta;
    }

    /**
     * Moves the clock forward.
     *
     * @param ms milliseconds; must not be negative
     */
    void advanceMs(long ms) {
        advanceNanos(ms * 1_000_000L);
    }

    /**
     * Returns the stream time in milliseconds.
     *
     * @return milliseconds since the clock was created
     */
    long millis() {
        return nanos / 1_000_000L;
    }
}
