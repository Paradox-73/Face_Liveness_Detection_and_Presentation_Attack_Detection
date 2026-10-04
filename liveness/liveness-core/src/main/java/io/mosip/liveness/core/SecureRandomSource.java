/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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
