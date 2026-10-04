/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageBufferTest {

    @Test
    void requiredLengthPerFormat() {
        assertEquals(12, PixelFormat.RGB888.requiredLength(2, 2));
        assertEquals(16, PixelFormat.RGBA8888.requiredLength(2, 2));
        assertEquals(4, PixelFormat.GRAY8.requiredLength(2, 2));
        assertEquals(6, PixelFormat.NV21.requiredLength(2, 2));
        assertEquals(4, PixelFormat.ARGB8888_INT.requiredLength(2, 2));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.NV21.requiredLength(3, 2));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.JPEG.requiredLength(2, 2));
        assertThrows(IllegalArgumentException.class, () -> PixelFormat.RGB888.requiredLength(0, 2));
        assertTrue(PixelFormat.JPEG2000.isEncoded());
        assertFalse(PixelFormat.BGR888.isEncoded());
    }

    @Test
    void byteBufferSharesItsArray() {
        byte[] px = new byte[2 * 3 * 3];
        px[0] = 7;
        ImageBuffer img = ImageBuffer.ofBytes(2, 3, PixelFormat.BGR888, px);
        assertSame(px, img.bytes());
        assertFalse(img.isIntBacked());
        assertThrows(IllegalStateException.class, img::ints);
        img.wipe();
        assertArrayEquals(new byte[18], px);
    }

    @Test
    void intBufferHoldsArgbPixels() {
        int[] px = {0xFF102030, 0xFF405060};
        ImageBuffer img = ImageBuffer.ofArgbInts(2, 1, px);
        assertTrue(img.isIntBacked());
        assertEquals(PixelFormat.ARGB8888_INT, img.format());
        assertThrows(IllegalStateException.class, img::bytes);
        img.wipe();
        assertArrayEquals(new int[2], px);
    }

    @Test
    void wrongLengthOrFormatIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ImageBuffer.ofBytes(2, 2, PixelFormat.RGB888, new byte[11]));
        assertThrows(IllegalArgumentException.class, () -> ImageBuffer.ofBytes(2, 2, PixelFormat.JPEG, new byte[12]));
        assertThrows(IllegalArgumentException.class, () -> ImageBuffer.ofBytes(1, 1, PixelFormat.ARGB8888_INT, new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> ImageBuffer.ofArgbInts(2, 2, new int[3]));
    }

    @Test
    void toStringNeverPrintsPixels() {
        byte[] px = new byte[4];
        px[0] = 42;
        assertEquals("ImageBuffer[2x2 GRAY8]", ImageBuffer.ofBytes(2, 2, PixelFormat.GRAY8, px).toString());
    }
}
