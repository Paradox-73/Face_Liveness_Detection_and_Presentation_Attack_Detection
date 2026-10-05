/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.pad;

import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.onnx.image.ChannelOrder;
import io.mosip.liveness.onnx.image.Rgb8Image;

import java.util.Arrays;

/**
 * Finds the regular pattern that a screen leaves on a face filmed from it: the pixel grid and moire stripes show up
 * as a few isolated, very strong peaks in the spatial frequency spectrum of the skin, while live skin, a print and
 * camera noise spread their energy smoothly over all frequencies.
 *
 * <p>Method: the central {@value #SIZE}x{@value #SIZE} pixels of the face box (grey, mean removed, Hann window),
 * 2-D power spectrum, and for each frequency in the ring from {@code SIZE/6} to {@code SIZE/2} cycles its power
 * divided by the median power at the same radius. The largest ratio is the peak ratio. The frequencies of the 8x8
 * JPEG block grid (both components multiples of {@code SIZE/8}) are left out.
 *
 * <p>Measured on the frame packs (run/frame-packs, 640x480, every frame): live faces have a median peak ratio of
 * 15-55 and a largest single-frame value of 748 (one turned frame of {@code s2-turn-left}); faces replayed on a
 * screen ({@code a-s2-screen-*}, {@code a-s3-screen-*}) have medians of 436-5,990. Prints stay at live values. The
 * MockMDS stock photo measures 494. The live probability falls from 1 at a ratio of 600 to 0 at 1,500, so one odd
 * live frame lowers a score but does not end a session; a screen face scores 0 on most frames. The
 * screen packs are synthetic (tools/eval/synth.py: pixel grid with a 3-pixel period plus moire), so the separation
 * on real screens is not known yet; a frame that was enlarged by a whole-number or 1.5x factor before it reached
 * the engine also shows such peaks (pixel repetition) and would score as a screen.
 */
public final class ScreenPatternMeter {

    /** Side of the analysed skin patch in pixels (a power of two). */
    public static final int SIZE = 64;
    /** Peak ratio at and below which the face is treated as not filmed from a screen. */
    public static final double LIVE_MAX_RATIO = 600;
    /** Peak ratio at and above which the face is treated as filmed from a screen. */
    public static final double SCREEN_MIN_RATIO = 1500;

    private static final double[] WINDOW = hann(SIZE);
    private static final int[] RADIUS = new int[SIZE * SIZE];
    private static final boolean[] IN_BAND = new boolean[SIZE * SIZE];
    private static final int MIN_RADIUS = (int) Math.ceil(SIZE / 6.0);
    private static final int MAX_RADIUS = SIZE / 2;

    static {
        for (int v = 0; v < SIZE; v++) {
            int fy = v <= SIZE / 2 ? v : v - SIZE;
            for (int u = 0; u < SIZE; u++) {
                int fx = u <= SIZE / 2 ? u : u - SIZE;
                double r = Math.sqrt(fx * fx + fy * fy);
                int i = v * SIZE + u;
                RADIUS[i] = (int) Math.round(r);
                boolean jpegGrid = Math.abs(fx) % (SIZE / 8) == 0 && Math.abs(fy) % (SIZE / 8) == 0;
                IN_BAND[i] = r >= SIZE / 6.0 && r <= SIZE / 2.0 && !jpegGrid;
            }
        }
    }

    private ScreenPatternMeter() {
    }

    /**
     * Measures the peak ratio of one face.
     *
     * @param image the frame
     * @param face  the face box
     * @return the peak ratio (1 or more), or {@code NaN} when the face box is smaller than {@value #SIZE} pixels on
     * a side or lies partly outside the frame
     */
    public static double peakRatio(Rgb8Image image, FaceBox face) {
        int x = (int) face.x();
        int y = (int) face.y();
        int w = (int) face.width();
        int h = (int) face.height();
        if (w < SIZE || h < SIZE) {
            return Double.NaN;
        }
        int left = x + w / 2 - SIZE / 2;
        int top = y + h / 2 - SIZE / 2;
        if (left < 0 || top < 0 || left + SIZE > image.width() || top + SIZE > image.height()) {
            return Double.NaN;
        }
        double[] re = new double[SIZE * SIZE];
        double[] im = new double[SIZE * SIZE];
        byte[] d = image.data();
        ChannelOrder order = image.order();
        int ri = order.redIndex();
        int bi = order.blueIndex();
        double sum = 0;
        for (int v = 0; v < SIZE; v++) {
            int row = ((top + v) * image.width() + left) * 3;
            for (int u = 0; u < SIZE; u++) {
                int p = row + u * 3;
                double g = 0.299 * (d[p + ri] & 0xFF) + 0.587 * (d[p + 1] & 0xFF) + 0.114 * (d[p + bi] & 0xFF);
                re[v * SIZE + u] = g;
                sum += g;
            }
        }
        double mean = sum / (SIZE * SIZE);
        for (int v = 0; v < SIZE; v++) {
            for (int u = 0; u < SIZE; u++) {
                re[v * SIZE + u] = (re[v * SIZE + u] - mean) * WINDOW[v] * WINDOW[u];
            }
        }
        fft2(re, im);
        double[] power = new double[SIZE * SIZE];
        for (int i = 0; i < power.length; i++) {
            power[i] = re[i] * re[i] + im[i] * im[i];
        }
        double[] median = ringMedians(power);
        double best = 1;
        for (int i = 0; i < power.length; i++) {
            if (IN_BAND[i]) {
                best = Math.max(best, power[i] / (median[RADIUS[i]] + 1e-9));
            }
        }
        return best;
    }

    /**
     * Maps a peak ratio to a live probability: 1 at or below {@link #LIVE_MAX_RATIO}, 0 at or above
     * {@link #SCREEN_MIN_RATIO}, linear in between; 1 for {@code NaN} (not measured).
     *
     * @param ratio the peak ratio
     * @return live probability, 0-1
     */
    public static double liveProbability(double ratio) {
        if (Double.isNaN(ratio) || ratio <= LIVE_MAX_RATIO) {
            return 1.0;
        }
        if (ratio >= SCREEN_MIN_RATIO) {
            return 0.0;
        }
        return (SCREEN_MIN_RATIO - ratio) / (SCREEN_MIN_RATIO - LIVE_MAX_RATIO);
    }

    private static double[] ringMedians(double[] power) {
        double[] median = new double[MAX_RADIUS + 2];
        double[] buf = new double[power.length];
        for (int r = MIN_RADIUS; r <= MAX_RADIUS; r++) {
            int n = 0;
            for (int i = 0; i < power.length; i++) {
                if (IN_BAND[i] && RADIUS[i] == r) {
                    buf[n++] = power[i];
                }
            }
            if (n > 0) {
                Arrays.sort(buf, 0, n);
                median[r] = n % 2 == 1 ? buf[n / 2] : (buf[n / 2 - 1] + buf[n / 2]) / 2;
            }
        }
        return median;
    }

    private static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
        }
        return w;
    }

    /** In-place 2-D FFT of a {@value #SIZE}x{@value #SIZE} array, rows then columns. */
    static void fft2(double[] re, double[] im) {
        double[] r = new double[SIZE];
        double[] c = new double[SIZE];
        for (int v = 0; v < SIZE; v++) {
            System.arraycopy(re, v * SIZE, r, 0, SIZE);
            System.arraycopy(im, v * SIZE, c, 0, SIZE);
            fft(r, c);
            System.arraycopy(r, 0, re, v * SIZE, SIZE);
            System.arraycopy(c, 0, im, v * SIZE, SIZE);
        }
        for (int u = 0; u < SIZE; u++) {
            for (int v = 0; v < SIZE; v++) {
                r[v] = re[v * SIZE + u];
                c[v] = im[v * SIZE + u];
            }
            fft(r, c);
            for (int v = 0; v < SIZE; v++) {
                re[v * SIZE + u] = r[v];
                im[v * SIZE + u] = c[v];
            }
        }
    }

    /** In-place radix-2 FFT; the length must be a power of two. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
