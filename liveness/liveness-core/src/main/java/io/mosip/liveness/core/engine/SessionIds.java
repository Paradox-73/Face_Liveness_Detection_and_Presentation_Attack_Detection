/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.RandomSource;

import java.util.Base64;

/**
 * Random identifiers of a session: the session identifier (not derived from the subject) and nonces for pass
 * tokens and challenge prompts.
 */
final class SessionIds {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SessionIds() {
    }

    /**
     * Returns a new session identifier.
     *
     * @param random random source
     * @return 32 lower-case hex digits (128 random bits)
     */
    static String sessionId(RandomSource random) {
        byte[] b = new byte[16];
        random.nextBytes(b);
        char[] out = new char[32];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = HEX[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }

    /**
     * Returns a new nonce.
     *
     * @param random random source
     * @return 32 URL-safe base64 characters (192 random bits)
     */
    static String nonce(RandomSource random) {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
