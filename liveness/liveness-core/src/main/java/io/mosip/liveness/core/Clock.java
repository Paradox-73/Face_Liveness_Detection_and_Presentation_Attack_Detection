/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core;

import java.time.Instant;

/**
 * Time source for the liveness engine.
 *
 * <p>All timeouts and frame timings use {@link #monotonicNanos()}, which never jumps when the wall clock is
 * changed. {@link #now()} is used only for timestamps that leave the engine (audit events, pass tokens,
 * results). Tests supply a manual clock so that every timeout can be driven without sleeping.
 */
public interface Clock {

    /**
     * Returns a monotonic time reading, comparable only with other readings from the same clock.
     *
     * @return nanoseconds from an arbitrary origin
     */
    long monotonicNanos();

    /**
     * Returns the current wall-clock time.
     *
     * @return the current instant (UTC)
     */
    Instant now();

    /**
     * Returns the production clock backed by {@link System#nanoTime()} and {@link Instant#now()}.
     *
     * @return the system clock
     */
    static Clock system() {
        return SystemClock.INSTANCE;
    }
}
