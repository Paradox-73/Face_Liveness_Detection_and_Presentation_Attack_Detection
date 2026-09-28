package io.mosip.liveness.core;

import java.security.SecureRandom;

/** Production {@link RandomSource} backed by {@link SecureRandom}. */
final class SecureRandomSource implements RandomSource {

    private final SecureRandom random;

    SecureRandomSource(SecureRandom random) {
        this.random = random;
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
