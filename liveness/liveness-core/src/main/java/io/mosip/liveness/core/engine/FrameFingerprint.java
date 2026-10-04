/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Cheap image fingerprints for the frame integrity checks: SHA-256 of the frame bytes, grey thumbnails and a
 * 64-bit difference hash (dHash). Works on every raw {@link PixelFormat}; no platform image classes.
 */
final class FrameFingerprint {

    /** Side of the grey thumbnail compared between consecutive frames. */
    static final int THUMB_SIZE = 64;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FrameFingerprint() {
    }

    /**
     * Computes the SHA-256 of some bytes.
     *
     * @param data the bytes
     * @return the 32-byte digest
     */
    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // Every Java and Android platform provides SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns the first 8 hex digits of the SHA-256 of a text (used to audit a nonce without revealing it).
     *
     * @param text the text
     * @return 8 lower-case hex digits
     */
    static String sha256Prefix(String text) {
        byte[] d = sha256(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 4; i++) {
            sb.append(HEX[(d[i] >> 4) & 0xF]).append(HEX[d[i] & 0xF]);
        }
        return sb.toString();
    }

    /**
     * Returns a grey thumbnail; each cell is the mean of {@code samples x samples} points spread over the cell.
     *
     * @param img     source image
     * @param width   thumbnail width
     * @param height  thumbnail height
     * @param samples points per cell side (1 = point sampling, which keeps sensor noise)
     * @return grey levels 0-255, row by row
     */
    static int[] thumbnail(ImageBuffer img, int width, int height, int samples) {
        int[] out = new int[width * height];
        int w = img.width();
        int h = img.height();
        int per = samples * samples;
        for (int ty = 0; ty < height; ty++) {
            for (int tx = 0; tx < width; tx++) {
                int sum = 0;
                for (int sy = 0; sy < samples; sy++) {
                    int y = (int) (((long) (ty * samples + sy) * 2 + 1) * h / (2L * height * samples));
                    for (int sx = 0; sx < samples; sx++) {
                        int x = (int) (((long) (tx * samples + sx) * 2 + 1) * w / (2L * width * samples));
                        sum += luma(img, Math.min(x, w - 1), Math.min(y, h - 1));
                    }
                }
                out[ty * width + tx] = sum / per;
            }
        }
        return out;
    }

    /**
     * Returns the mean absolute difference of two thumbnails of the same size.
     *
     * @param a first thumbnail
     * @param b second thumbnail
     * @return mean difference in grey levels
     */
    static double meanAbsDiff(int[] a, int[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return (double) sum / a.length;
    }

    /**
     * Returns the 64-bit difference hash: bit set where a cell of a 9x8 averaged thumbnail is brighter than its
     * right neighbour. Similar images have hashes a few bits apart.
     *
     * @param img the image
     * @return the hash
     */
    static long dHash(ImageBuffer img) {
        int[] t = thumbnail(img, 9, 8, 4);
        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash <<= 1;
                if (t[y * 9 + x] > t[y * 9 + x + 1]) {
                    hash |= 1;
                }
            }
        }
        return hash;
    }

    /**
     * Returns the grey level of one pixel: {@code (77 R + 150 G + 29 B) / 256}; for NV21 the Y sample.
     *
     * @param img the image
     * @param x   column
     * @param y   row
     * @return grey level 0-255
     */
    static int luma(ImageBuffer img, int x, int y) {
        int i = y * img.width() + x;
        PixelFormat f = img.format();
        if (f == PixelFormat.ARGB8888_INT) {
            int p = img.ints()[i];
            return grey((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
        }
        byte[] b = img.bytes();
        switch (f) {
            case RGB888:
                return grey(b[i * 3] & 0xFF, b[i * 3 + 1] & 0xFF, b[i * 3 + 2] & 0xFF);
            case BGR888:
                return grey(b[i * 3 + 2] & 0xFF, b[i * 3 + 1] & 0xFF, b[i * 3] & 0xFF);
            case RGBA8888:
                return grey(b[i * 4] & 0xFF, b[i * 4 + 1] & 0xFF, b[i * 4 + 2] & 0xFF);
            default:
                // GRAY8 and the Y plane of NV21 both hold one grey byte per pixel at this index.
                return b[i] & 0xFF;
        }
    }

    private static int grey(int r, int g, int b) {
        return (77 * r + 150 * g + 29 * b) >> 8;
    }
}
