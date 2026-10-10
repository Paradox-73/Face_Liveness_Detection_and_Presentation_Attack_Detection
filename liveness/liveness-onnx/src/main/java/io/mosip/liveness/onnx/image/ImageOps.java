/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.image;


import java.util.Objects;

/**
 * Pure-Java image operations for model preprocessing: bilinear resize, crop with clamping, affine warp,
 * channel-order conversion, grey conversion and tensor normalisation. No {@code java.awt}, so the same code runs
 * on the desktop and on Android.
 *
 * <p>Sampling follows OpenCV {@code INTER_LINEAR}: pixel centres at integer coordinates, source position
 * {@code (d + 0.5) * scale - 0.5} for a resize, edge pixels repeated for a resize and zero outside the image for
 * a warp. Results match OpenCV to within rounding, so model outputs match the published Python pipelines.
 */
public final class ImageOps {

    private ImageOps() {
    }

    // ---------------------------------------------------------------- tensors

    /**
     * Resizes the inclusive region {@code [left, right] x [top, bottom]} of {@code src} to
     * {@code outWidth x outHeight} and writes it, normalised, into the top-left corner of a planar (CHW) tensor
     * whose planes are {@code planeWidth x planeHeight}. Tensor values outside the written corner are left as
     * they are (callers zero them once for padding).
     *
     * @param src         source image
     * @param left        first source column; within the image
     * @param top         first source row; within the image
     * @param right       last source column (inclusive); at least {@code left}, within the image
     * @param bottom      last source row (inclusive); at least {@code top}, within the image
     * @param outWidth    width of the resized region; at most {@code planeWidth}
     * @param outHeight   height of the resized region; at most {@code planeHeight}
     * @param format      plane order and normalisation
     * @param tensor      destination of at least {@code 3 * planeWidth * planeHeight} values
     * @param planeWidth  tensor width
     * @param planeHeight tensor height
     * @throws IllegalArgumentException if the region or sizes do not fit
     */
    public static void resizeRegionToTensor(Rgb8Image src, int left, int top, int right, int bottom, int outWidth,
                                            int outHeight, TensorFormat format, float[] tensor, int planeWidth,
                                            int planeHeight) {
        checkRegion(src, left, top, right, bottom);
        if (outWidth <= 0 || outHeight <= 0 || outWidth > planeWidth || outHeight > planeHeight
                || tensor.length < 3 * planeWidth * planeHeight) {
            throw new IllegalArgumentException("output does not fit the tensor");
        }
        if ((right - left + 1) == 2 * outWidth && (bottom - top + 1) == 2 * outHeight) {
            halveToTensor(src, left, top, outWidth, outHeight, format, tensor, planeWidth, planeHeight);
            return;
        }
        Axis xs = Axis.of(left, right - left + 1, outWidth, 3);
        Axis ys = Axis.of(top, bottom - top + 1, outHeight, src.width() * 3);
        int[] channel = channelMap(src.order(), format.order());
        int c0 = channel[0];
        int c1 = channel[1];
        int c2 = channel[2];
        byte[] data = src.data();
        int plane = planeWidth * planeHeight;
        float mean = format.mean();
        float scale = format.scale();
        for (int y = 0; y < outHeight; y++) {
            int row0 = ys.i0[y];
            int row1 = ys.i1[y];
            float wy = ys.w1[y];
            int o = y * planeWidth;
            for (int x = 0; x < outWidth; x++, o++) {
                int p00 = row0 + xs.i0[x];
                int p01 = row0 + xs.i1[x];
                int p10 = row1 + xs.i0[x];
                int p11 = row1 + xs.i1[x];
                float wx = xs.w1[x];
                tensor[o] = (lerp2(data, p00 + c0, p01 + c0, p10 + c0, p11 + c0, wx, wy) - mean) * scale;
                tensor[plane + o] = (lerp2(data, p00 + c1, p01 + c1, p10 + c1, p11 + c1, wx, wy) - mean) * scale;
                tensor[2 * plane + o] = (lerp2(data, p00 + c2, p01 + c2, p10 + c2, p11 + c2, wx, wy) - mean) * scale;
            }
        }
    }

    /**
     * Warps {@code src} into an {@code outWidth x outHeight} planar (CHW) tensor: each output pixel
     * {@code (x, y)} samples the source at {@code srcToDst.invert()} applied to {@code (x, y)}, bilinearly, with
     * zero outside the image (OpenCV {@code warpAffine} with {@code BORDER_CONSTANT}).
     *
     * @param src       source image
     * @param srcToDst  mapping from source to output coordinates
     * @param outWidth  output width
     * @param outHeight output height
     * @param format    plane order and normalisation
     * @param tensor    destination of at least {@code 3 * outWidth * outHeight} values
     */
    public static void warpAffineToTensor(Rgb8Image src, AffineTransform srcToDst, int outWidth, int outHeight,
                                          TensorFormat format, float[] tensor) {
        if (outWidth <= 0 || outHeight <= 0 || tensor.length < 3 * outWidth * outHeight) {
            throw new IllegalArgumentException("output does not fit the tensor");
        }
        double[] m = srcToDst.invert().coefficients();
        int[] channel = channelMap(src.order(), format.order());
        int width = src.width();
        int height = src.height();
        int stride = width * 3;
        byte[] data = src.data();
        int plane = outWidth * outHeight;
        float mean = format.mean();
        float scale = format.scale();
        float[] px = new float[3];
        for (int y = 0; y < outHeight; y++) {
            double sx = m[1] * y + m[2];
            double sy = m[4] * y + m[5];
            int o = y * outWidth;
            for (int x = 0; x < outWidth; x++, o++, sx += m[0], sy += m[3]) {
                int x0 = floor(sx);
                int y0 = floor(sy);
                float fx = (float) (sx - x0);
                float fy = (float) (sy - y0);
                if (x0 >= 0 && y0 >= 0 && x0 < width - 1 && y0 < height - 1) {
                    int p00 = y0 * stride + x0 * 3;
                    int p10 = p00 + stride;
                    for (int k = 0; k < 3; k++) {
                        int c = channel[k];
                        px[k] = lerp2(data, p00 + c, p00 + 3 + c, p10 + c, p10 + 3 + c, fx, fy);
                    }
                } else {
                    sampleWithBorder(data, width, height, x0, y0, fx, fy, channel, px);
                }
                tensor[o] = (px[0] - mean) * scale;
                tensor[plane + o] = (px[1] - mean) * scale;
                tensor[2 * plane + o] = (px[2] - mean) * scale;
            }
        }
    }

    // ---------------------------------------------------------------- images

    /**
     * Resizes an image with bilinear interpolation (OpenCV {@code INTER_LINEAR}).
     *
     * @param src    source image
     * @param width  target width; positive
     * @param height target height; positive
     * @return a new image in the source channel order
     */
    public static Rgb8Image resize(Rgb8Image src, int width, int height) {
        return resizeRegion(src, 0, 0, src.width() - 1, src.height() - 1, width, height);
    }

    /**
     * Crops a rectangle, clamped to the image: the part of the rectangle outside the image is dropped.
     *
     * @param src    source image
     * @param x      left edge; may be negative
     * @param y      top edge; may be negative
     * @param width  width; positive
     * @param height height; positive
     * @return a new image holding the part of the rectangle inside the image
     * @throws IllegalArgumentException if the rectangle does not overlap the image
     */
    public static Rgb8Image crop(Rgb8Image src, int x, int y, int width, int height) {
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(src.width() - 1, x + width - 1);
        int bottom = Math.min(src.height() - 1, y + height - 1);
        if (width <= 0 || height <= 0 || right < left || bottom < top) {
            throw new IllegalArgumentException("crop rectangle does not overlap the image");
        }
        int w = right - left + 1;
        int h = bottom - top + 1;
        byte[] out = new byte[w * h * 3];
        for (int r = 0; r < h; r++) {
            System.arraycopy(src.data(), ((top + r) * src.width() + left) * 3, out, r * w * 3, w * 3);
        }
        return Rgb8Image.wrap(w, h, out, src.order());
    }

    /**
     * Warps an image (OpenCV {@code warpAffine}, bilinear, zero outside the source).
     *
     * @param src      source image
     * @param srcToDst mapping from source to output coordinates
     * @param width    output width; positive
     * @param height   output height; positive
     * @return a new image in the source channel order
     */
    public static Rgb8Image warpAffine(Rgb8Image src, AffineTransform srcToDst, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        float[] tensor = new float[3 * width * height];
        warpAffineToTensor(src, srcToDst, width, height, TensorFormat.of(src.order(), 0f, 1f), tensor);
        return fromPlanes(tensor, width, height, src.order());
    }

    /**
     * Returns the image in the requested channel order, copying only if the order differs.
     *
     * @param src   source image
     * @param order wanted order
     * @return {@code src} itself or a converted copy
     */
    public static Rgb8Image withOrder(Rgb8Image src, ChannelOrder order) {
        if (src.order() == order) {
            return src;
        }
        return Rgb8Image.wrap(src.width(), src.height(), PixelConversions.swapRedBlue(src.data()), order);
    }

    /**
     * Converts to grey levels with the ITU-R BT.601 luma weights (the OpenCV {@code COLOR_BGR2GRAY} integer
     * coefficients).
     *
     * @param src source image
     * @return {@code width * height} grey values 0-255, top row first
     */
    public static byte[] toGray(Rgb8Image src) {
        byte[] data = src.data();
        int r = src.order().redIndex();
        int b = src.order().blueIndex();
        byte[] out = new byte[src.width() * src.height()];
        for (int i = 0, s = 0; i < out.length; i++, s += 3) {
            int sum = u8i(data, s + r) * 4899 + u8i(data, s + 1) * 9617 + u8i(data, s + b) * 1868;
            out[i] = (byte) ((sum + 8192) >> 14);
        }
        return out;
    }

    // ---------------------------------------------------------------- internals

    private static Rgb8Image resizeRegion(Rgb8Image src, int left, int top, int right, int bottom, int width,
                                          int height) {
        Objects.requireNonNull(src, "src");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        float[] tensor = new float[3 * width * height];
        TensorFormat same = TensorFormat.of(src.order(), 0f, 1f);
        resizeRegionToTensor(src, left, top, right, bottom, width, height, same, tensor, width, height);
        return fromPlanes(tensor, width, height, src.order());
    }

    private static Rgb8Image fromPlanes(float[] tensor, int width, int height, ChannelOrder order) {
        byte[] out = new byte[width * height * 3];
        int plane = width * height;
        for (int i = 0, o = 0; i < plane; i++, o += 3) {
            out[o] = round(tensor[i]);
            out[o + 1] = round(tensor[plane + i]);
            out[o + 2] = round(tensor[2 * plane + i]);
        }
        return Rgb8Image.wrap(width, height, out, order);
    }

    private static void checkRegion(Rgb8Image src, int left, int top, int right, int bottom) {
        Objects.requireNonNull(src, "src");
        if (left < 0 || top < 0 || right >= src.width() || bottom >= src.height() || right < left || bottom < top) {
            throw new IllegalArgumentException("region [" + left + ", " + top + ", " + right + ", " + bottom
                    + "] outside a " + src.width() + "x" + src.height() + " image");
        }
    }

    /** For each tensor plane (in the tensor's order), the byte offset within a source pixel. */
    private static int[] channelMap(ChannelOrder source, ChannelOrder tensor) {
        if (source == tensor) {
            return new int[] {0, 1, 2};
        }
        return new int[] {2, 1, 0};
    }

    /**
     * Exact 2:1 reduction: with half-pixel centres every output pixel is the mean of a 2x2 block, which is what
     * the general bilinear path computes too (same values), at a fraction of the cost. Common for 640x480
     * frames going to a 320-pixel detector input.
     */
    private static void halveToTensor(Rgb8Image src, int left, int top, int outWidth, int outHeight,
                                      TensorFormat format, float[] tensor, int planeWidth, int planeHeight) {
        int[] channel = channelMap(src.order(), format.order());
        byte[] d = src.data();
        int stride = src.width() * 3;
        int plane = planeWidth * planeHeight;
        float mean = format.mean();
        float scale = format.scale() / 4f;
        float meanTimesFour = 4f * mean;
        for (int y = 0; y < outHeight; y++) {
            int r0 = (top + 2 * y) * stride + left * 3;
            int r1 = r0 + stride;
            int o = y * planeWidth;
            for (int x = 0; x < outWidth; x++, o++, r0 += 6, r1 += 6) {
                for (int k = 0; k < 3; k++) {
                    int c = channel[k];
                    int sum = (d[r0 + c] & 0xff) + (d[r0 + 3 + c] & 0xff) + (d[r1 + c] & 0xff) + (d[r1 + 3 + c] & 0xff);
                    tensor[k * plane + o] = (sum - meanTimesFour) * scale;
                }
            }
        }
    }

    private static float lerp2(byte[] d, int i00, int i01, int i10, int i11, float wx, float wy) {
        float a = d[i00] & 0xff;
        float b = d[i01] & 0xff;
        float c = d[i10] & 0xff;
        float e = d[i11] & 0xff;
        float top = a + (b - a) * wx;
        float bottom = c + (e - c) * wx;
        return top + (bottom - top) * wy;
    }

    private static void sampleWithBorder(byte[] data, int width, int height, int x0, int y0, float fx, float fy,
                                         int[] channel, float[] out) {
        for (int k = 0; k < 3; k++) {
            int c = channel[k];
            float p00 = at(data, width, height, x0, y0, c);
            float p01 = at(data, width, height, x0 + 1, y0, c);
            float p10 = at(data, width, height, x0, y0 + 1, c);
            float p11 = at(data, width, height, x0 + 1, y0 + 1, c);
            float top = p00 + (p01 - p00) * fx;
            float bottom = p10 + (p11 - p10) * fx;
            out[k] = top + (bottom - top) * fy;
        }
    }

    private static float at(byte[] data, int width, int height, int x, int y, int c) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0f;
        }
        return data[(y * width + x) * 3 + c] & 0xff;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static int u8i(byte[] data, int index) {
        return data[index] & 0xff;
    }

    private static byte round(float v) {
        int r = (int) (v + 0.5f);
        return (byte) (r < 0 ? 0 : (r > 255 ? 255 : r));
    }

    /** Bilinear sample positions along one axis (OpenCV {@code INTER_LINEAR}), as array offsets. */
    private static final class Axis {
        final int[] i0;
        final int[] i1;
        final float[] w1;

        private Axis(int n) {
            i0 = new int[n];
            i1 = new int[n];
            w1 = new float[n];
        }

        static Axis of(int offset, int srcSize, int dstSize, int step) {
            Axis axis = new Axis(dstSize);
            double scale = (double) srcSize / dstSize;
            for (int d = 0; d < dstSize; d++) {
                double pos = (d + 0.5) * scale - 0.5;
                int s = (int) Math.floor(pos);
                double t = pos - s;
                if (s < 0) {
                    s = 0;
                    t = 0;
                }
                if (s >= srcSize - 1) {
                    s = srcSize - 1;
                    t = 0;
                }
                axis.i0[d] = (offset + s) * step;
                axis.i1[d] = (offset + Math.min(s + 1, srcSize - 1)) * step;
                axis.w1[d] = (float) t;
            }
            return axis;
        }
    }
}
