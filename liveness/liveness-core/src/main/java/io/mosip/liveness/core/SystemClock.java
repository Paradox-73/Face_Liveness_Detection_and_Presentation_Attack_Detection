/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core;

import java.time.Instant;

/** Production {@link Clock} backed by {@link System#nanoTime()} and {@link Instant#now()}. */
final class SystemClock implements Clock {

    static final SystemClock INSTANCE = new SystemClock();

    private SystemClock() {
    }

    @Override
    public long monotonicNanos() {
        return System.nanoTime();
    }

    @Override
    public Instant now() {
        return Instant.now();
    }
}
