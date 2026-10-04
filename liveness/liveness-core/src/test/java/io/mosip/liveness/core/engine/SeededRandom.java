/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.RandomSource;

import java.util.Random;

/** Seeded random source; exists only in test code (docs/design/challenges.md section 2). */
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
