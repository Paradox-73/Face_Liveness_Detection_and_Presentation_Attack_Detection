/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.Clock;

import java.time.Instant;

/** Manual clock for deterministic tests: time moves only when a test advances it. */
final class FakeClock implements Clock {

    static final Instant EPOCH = Instant.parse("2026-09-28T10:00:00Z");

    private long nanos = 1_000_000_000L;

    @Override
    public long monotonicNanos() {
        return nanos;
    }

    @Override
    public Instant now() {
        return EPOCH.plusNanos(nanos);
    }

    void advanceMs(long ms) {
        nanos += ms * 1_000_000L;
    }
}
