/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.image;

import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOpsTest {

    /** 2x2 RGB image: red, green / blue, white. */
    private static Rgb8Image quad() {
        return Rgb8Image.wrap(2, 2, new byte[] {
            (byte) 255, 0, 0, 0, (byte) 255, 0,
            0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255}, ChannelOrder.RGB);
    }

    @Test
    void rgbAndBgrAreWrappedWithoutCopy() {
        byte[] data = new byte[12];
        ImageBuffer rgb = ImageBuffer.ofBytes(2, 2, PixelFormat.RGB888, data);
        assertSame(data, Rgb8Image.of(rgb).data());
        assertEquals(ChannelOrder.BGR, Rgb8Image.of(ImageBuffer.ofBytes(2, 2, PixelFormat.BGR888, data)).order());
    }

    @Test
    void conversionsFromEveryRawFormat() {
        Rgb8Image fromArgb = Rgb8Image.of(ImageBuffer.ofArgbInts(1, 1, new int[] {0xff102030}));
        assertArrayEquals(new byte[] {0x10, 0x20, 0x30}, fromArgb.data());
        Rgb8Image fromRgba = Rgb8Image.of(ImageBuffer.ofBytes(1, 1, PixelFormat.RGBA8888, new byte[] {1, 2, 3, 4}));
        assertArrayEquals(new byte[] {1, 2, 3}, fromRgba.data());
        Rgb8Image fromGray = Rgb8Image.of(ImageBuffer.ofBytes(1, 1, PixelFormat.GRAY8, new byte[] {77}));
        assertArrayEquals(new byte[] {77, 77, 77}, fromGray.data());
        assertThrows(IllegalArgumentException.class, () -> Rgb8Image.wrap(2, 2, new byte[11], ChannelOrder.RGB));
    }

    @Test
    void nv21UsesBt601LimitedRange() {
        // 2x2 frame: Y = 16 (black) and 235 (white); U = V = 128 (no colour).
        byte[] nv21 = {16, 16, (byte) 235, (byte) 235, (byte) 128, (byte) 128};
        Rgb8Image img = Rgb8Image.of(ImageBuffer.ofBytes(2, 2, PixelFormat.NV21, nv21));
        assertEquals(0, img.data()[0] & 0xff);
        assertEquals(255, img.data()[6] & 0xff);
        assertEquals(255, img.data()[8] & 0xff);
        // Pure red in BT.601 limited range: Y 81, U 90, V 240.
        byte[] red = {81, 81, 81, 81, (byte) 240, 90};
        Rgb8Image r = Rgb8Image.of(ImageBuffer.ofBytes(2, 2, PixelFormat.NV21, red));
        assertTrue((r.data()[0] & 0xff) > 250 && (r.data()[1] & 0xff) < 5 && (r.data()[2] & 0xff) < 5);
    }

    @Test
    void resizeKeepsConstantImagesAndInterpolatesLinearly() {
        byte[] flat = new byte[4 * 3 * 3];
        java.util.Arrays.fill(flat, (byte) 90);
        Rgb8Image out = ImageOps.resize(Rgb8Image.wrap(4, 3, flat, ChannelOrder.RGB), 7, 5);
        for (byte b : out.data()) {
            assertEquals(90, b & 0xff);
        }
        // 2x1 black-white upscaled to 4x1: OpenCV INTER_LINEAR gives 0, 64, 191, 255.
        Rgb8Image bw = Rgb8Image.wrap(2, 1, new byte[] {0, 0, 0, (byte) 255, (byte) 255, (byte) 255}, ChannelOrder.RGB);
        Rgb8Image up = ImageOps.resize(bw, 4, 1);
        assertEquals(0, up.data()[0] & 0xff);
        assertEquals(64, up.data()[3] & 0xff);
        assertEquals(191, up.data()[6] & 0xff);
        assertEquals(255, up.data()[9] & 0xff);
    }

    @Test
    void exactHalvingAveragesTwoByTwoBlocks() {
        Rgb8Image half = ImageOps.resize(quad(), 1, 1);
        // Mean of red, green, blue, white per channel: (255 + 0 + 0 + 255) / 4 = 127.5 -> 128.
        assertEquals(128, half.data()[0] & 0xff);
        assertEquals(128, half.data()[1] & 0xff);
        assertEquals(128, half.data()[2] & 0xff);
    }

    @Test
    void halvingFastPathMatchesBilinearSampling() {
        byte[] data = new byte[8 * 6 * 3];
        new java.util.Random(7).nextBytes(data);
        Rgb8Image img = Rgb8Image.wrap(8, 6, data, ChannelOrder.BGR);
        float[] halved = new float[3 * 4 * 3];
        ImageOps.resizeRegionToTensor(img, 0, 0, 7, 5, 4, 3, TensorFormat.RGB_RAW, halved, 4, 3);
        // Same output through the general bilinear path: a warp by a 0.5 scale about the pixel-centre grid.
        float[] warped = new float[3 * 4 * 3];
        ImageOps.warpAffineToTensor(img, AffineTransform.of(0.5, 0, -0.25, 0, 0.5, -0.25), 4, 3, TensorFormat.RGB_RAW,
                warped);
        assertArrayEquals(warped, halved, 1e-3f);
        // Plane 0 of an RGB tensor from a BGR image is red, byte 2 of each pixel.
        float expected = ((data[2] & 0xff) + (data[5] & 0xff) + (data[26] & 0xff) + (data[29] & 0xff)) / 4f;
        assertEquals(expected, halved[0], 1e-4f);
    }

    @Test
    void cropIsClampedToTheImage() {
        Rgb8Image c = ImageOps.crop(quad(), -5, 1, 10, 10);
        assertEquals(2, c.width());
        assertEquals(1, c.height());
        assertArrayEquals(new byte[] {0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255}, c.data());
        assertThrows(IllegalArgumentException.class, () -> ImageOps.crop(quad(), 5, 5, 2, 2));
    }

    @Test
    void identityWarpReturnsTheImageAndOutsideIsZero() {
        Rgb8Image same = ImageOps.warpAffine(quad(), AffineTransform.identity(), 2, 2);
        assertArrayEquals(quad().data(), same.data());
        Rgb8Image shifted = ImageOps.warpAffine(quad(), AffineTransform.of(1, 0, 1, 0, 1, 0), 2, 2);
        // Output column 0 samples source column -1: outside, so black.
        assertEquals(0, shifted.data()[0] & 0xff);
        assertEquals(255, shifted.data()[3] & 0xff);
    }

    @Test
    void tensorsAreChwInTheRequestedOrderAndNormalised() {
        Rgb8Image img = quad();
        float[] bgr = new float[12];
        ImageOps.resizeRegionToTensor(img, 0, 0, 1, 1, 2, 2, TensorFormat.BGR_RAW, bgr, 2, 2);
        // Plane 0 is blue: pixels red, green, blue, white -> 0, 0, 255, 255.
        assertArrayEquals(new float[] {0, 0, 255, 255}, java.util.Arrays.copyOfRange(bgr, 0, 4));
        assertArrayEquals(new float[] {255, 0, 0, 255}, java.util.Arrays.copyOfRange(bgr, 8, 12));
        float[] unit = toTensor(img, TensorFormat.RGB_UNIT);
        assertEquals(1f, unit[0], 1e-6);
        assertEquals(0f, unit[1], 1e-6);
        float[] warped = new float[12];
        ImageOps.warpAffineToTensor(img, AffineTransform.identity(), 2, 2, TensorFormat.RGB_RAW, warped);
        assertArrayEquals(toTensor(img, TensorFormat.RGB_RAW), warped, 1e-4f);
    }

    /** The whole image as a planar (CHW) tensor without resizing. */
    private static float[] toTensor(Rgb8Image src, TensorFormat format) {
        float[] tensor = new float[3 * src.width() * src.height()];
        ImageOps.resizeRegionToTensor(src, 0, 0, src.width() - 1, src.height() - 1, src.width(), src.height(), format,
                tensor, src.width(), src.height());
        return tensor;
    }

    @Test
    void resizeIntoPaddedTensorLeavesPaddingUntouched() {
        float[] tensor = new float[3 * 4 * 4];
        ImageOps.resizeRegionToTensor(quad(), 0, 0, 1, 1, 2, 2, TensorFormat.RGB_RAW, tensor, 4, 4);
        assertEquals(255f, tensor[0]);
        assertEquals(0f, tensor[2]);
        assertEquals(0f, tensor[15]);
        assertThrows(IllegalArgumentException.class,
                () -> ImageOps.resizeRegionToTensor(quad(), 0, 0, 2, 1, 2, 2, TensorFormat.RGB_RAW, tensor, 4, 4));
    }

    @Test
    void orderConversionAndGrey() {
        Rgb8Image bgr = ImageOps.withOrder(quad(), ChannelOrder.BGR);
        assertEquals(ChannelOrder.BGR, bgr.order());
        assertEquals(0, bgr.data()[0] & 0xff);
        assertEquals(255, bgr.data()[2] & 0xff);
        assertSame(bgr, ImageOps.withOrder(bgr, ChannelOrder.BGR));
        byte[] grey = ImageOps.toGray(quad());
        assertEquals(76, grey[0] & 0xff);
        assertEquals(150, grey[1] & 0xff);
        assertEquals(29, grey[2] & 0xff);
        assertEquals(255, grey[3] & 0xff);
        assertArrayEquals(grey, ImageOps.toGray(bgr));
        ImageBuffer asBgr = ImageOps.withOrder(quad(), ChannelOrder.BGR).toImageBuffer();
        assertEquals(PixelFormat.BGR888, asBgr.format());
        assertEquals(PixelFormat.RGB888, ImageOps.withOrder(Rgb8Image.of(asBgr), ChannelOrder.RGB).toImageBuffer()
                .format());
    }

    @Test
    void toStringHasNoPixels() {
        assertEquals("Rgb8Image[2x2 RGB]", quad().toString());
        assertTrue(TensorFormat.BGR_RAW.toString().contains("BGR"));
    }
}
