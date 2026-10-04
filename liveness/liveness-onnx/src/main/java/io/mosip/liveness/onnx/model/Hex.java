/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.model;

/**
 * Lower-case hexadecimal encoding.
 */
public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    /**
     * Encodes bytes as lower-case hex.
     *
     * @param data bytes
     * @return two hex digits per byte
     */
    public static String encode(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[2 * i] = DIGITS[(data[i] >> 4) & 0xf];
            out[2 * i + 1] = DIGITS[data[i] & 0xf];
        }
        return new String(out);
    }

    /**
     * Returns the SHA-256 of some bytes as hex.
     *
     * @param data bytes
     * @return 64 lower-case hex digits
     */
    public static String sha256(byte[] data) {
        return encode(ManifestSignature.sha256(data));
    }
}
