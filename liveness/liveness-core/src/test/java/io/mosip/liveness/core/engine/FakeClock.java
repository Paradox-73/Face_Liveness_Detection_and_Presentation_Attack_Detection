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
