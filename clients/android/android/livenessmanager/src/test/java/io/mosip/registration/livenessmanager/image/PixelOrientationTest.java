/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.image;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;

/**
 * Checks rotation, mirroring and channel order on a 3x2 grey image whose pixel values are their index:
 * <pre>
 *   0 1 2
 *   3 4 5
 * </pre>
 */
public class PixelOrientationTest {

    private static final byte[] GREY = {0, 1, 2, 3, 4, 5};

    @Test
    public void rotation0KeepsImage() {
        assertGrey(PixelOrientation.toUprightRgb(GREY, PixelFormat.GRAY8, 3, 2, 0, false), 3, 2, 0, 1, 2, 3, 4, 5);
    }

    @Test
    public void rotation90TurnsClockwise() {
        // 3 0 / 4 1 / 5 2
        assertGrey(PixelOrientation.toUprightRgb(GREY, PixelFormat.GRAY8, 3, 2, 90, false), 2, 3, 3, 0, 4, 1, 5, 2);
    }

    @Test
    public void rotation180TurnsHalf() {
        assertGrey(PixelOrientation.toUprightRgb(GREY, PixelFormat.GRAY8, 3, 2, 180, false), 3, 2, 5, 4, 3, 2, 1, 0);
    }

    @Test
    public void rotation270TurnsAnticlockwise() {
        // 2 5 / 1 4 / 0 3
        assertGrey(PixelOrientation.toUprightRgb(GREY, PixelFormat.GRAY8, 3, 2, 270, false), 2, 3, 2, 5, 1, 4, 0, 3);
    }

    @Test
    public void mirroredFrameIsFlippedBackAfterRotation() {
        assertGrey(PixelOrientation.toUprightRgb(GREY, PixelFormat.GRAY8, 3, 2, 0, true), 3, 2, 2, 1, 0, 5, 4, 3);
        // Rotated 90 (3 0 / 4 1 / 5 2), then flipped: 0 3 / 1 4 / 2 5.
        assertGrey(PixelOrientation.toUprightRgb(GREY, PixelFormat.GRAY8, 3, 2, 90, true), 2, 3, 0, 3, 1, 4, 2, 5);
    }

    @Test
    public void rgbaAndBgrBecomeRgb() {
        byte[] rgba = {10, 20, 30, (byte) 255};
        assertArrayEquals(new byte[] {10, 20, 30},
                PixelOrientation.toUprightRgb(rgba, PixelFormat.RGBA8888, 1, 1, 0, false).bytes());
        byte[] bgr = {30, 20, 10};
        assertArrayEquals(new byte[] {10, 20, 30},
                PixelOrientation.toUprightRgb(bgr, PixelFormat.BGR888, 1, 1, 0, false).bytes());
    }

    @Test
    public void argbIntsRoundTrip() {
        int[] argb = {0xFF102030, 0xFF405060};
        ImageBuffer rgb = PixelOrientation.toUprightRgb(argb, 2, 1, 0, false);
        assertArrayEquals(new byte[] {0x10, 0x20, 0x30, 0x40, 0x50, 0x60}, rgb.bytes());
        assertArrayEquals(argb, PixelOrientation.toArgb(rgb));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOddRotation() {
        PixelOrientation.toUprightRgb(GREY, PixelFormat.GRAY8, 3, 2, 45, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortArray() {
        PixelOrientation.toUprightRgb(new byte[5], PixelFormat.GRAY8, 3, 2, 0, false);
    }

    private static void assertGrey(ImageBuffer image, int width, int height, int... values) {
        assertEquals(PixelFormat.RGB888, image.format());
        assertEquals(width, image.width());
        assertEquals(height, image.height());
        byte[] b = image.bytes();
        for (int i = 0; i < values.length; i++) {
            assertEquals("pixel " + i, values[i], b[i * 3]);
            assertEquals(b[i * 3], b[i * 3 + 1]);
            assertEquals(b[i * 3], b[i * 3 + 2]);
        }
    }
}
