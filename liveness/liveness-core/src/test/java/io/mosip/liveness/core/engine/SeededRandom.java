package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.RandomSource;

import java.util.Random;

/** Seeded random source; exists only in test code (design section 4.20). */
final class SeededRandom implements RandomSource {

    private final Random random;

    SeededRandom(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    @Override
    public void nextBytes(byte[] bytes) {
        random.nextBytes(bytes);
    }
}
