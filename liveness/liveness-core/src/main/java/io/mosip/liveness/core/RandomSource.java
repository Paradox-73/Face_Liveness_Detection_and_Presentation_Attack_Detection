/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core;

import java.security.SecureRandom;

/**
 * Source of randomness for challenge draws, prompt delays, session identifiers and nonces.
 *
 * <p>Production code must use {@link #secure()}. A seeded implementation exists only in test code and in the
 * headless scenario runner; no configuration key can select it (docs/design/challenges.md section 2).
 */
public interface RandomSource {

    /**
     * Returns a uniformly distributed integer in {@code [0, bound)}.
     *
     * @param bound exclusive upper limit; must be positive
     * @return the random integer
     */
    int nextInt(int bound);

    /**
     * Fills the array with random bytes.
     *
     * @param bytes array to fill
     */
    void nextBytes(byte[] bytes);

    /**
     * Returns a source backed by a new {@link SecureRandom}.
     *
     * @return a cryptographically strong random source
     */
    static RandomSource secure() {
        return new SecureRandomSource(new SecureRandom());
    }
}
