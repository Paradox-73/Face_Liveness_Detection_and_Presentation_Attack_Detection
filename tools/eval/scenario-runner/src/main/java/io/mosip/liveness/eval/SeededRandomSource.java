package io.mosip.liveness.eval;

import io.mosip.liveness.core.RandomSource;

import java.util.Random;

/**
 * Reproducible random source for the headless runner only.
 *
 * <p>It fixes the challenge order, prompt delays and nonces of a scenario so that a run can be repeated. The core
 * accepts it only through {@code SessionRequest.Builder.random}; no configuration key of either client can
 * select it, and production code always uses {@link RandomSource#secure()} (docs/design/challenges.md).
 */
final class SeededRandomSource implements RandomSource {

    private final Random random;

    /**
     * Creates a source.
     *
     * @param seed scenario seed
     */
    SeededRandomSource(long seed) {
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
