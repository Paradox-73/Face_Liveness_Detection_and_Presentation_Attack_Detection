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
