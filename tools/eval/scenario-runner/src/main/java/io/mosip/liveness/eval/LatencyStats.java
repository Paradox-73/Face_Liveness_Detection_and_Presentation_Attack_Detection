/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects durations in milliseconds and reports count, mean, median, 95th percentile and maximum.
 *
 * <p>Percentiles use the nearest-rank method on the sorted samples (p95 of 20 samples is the 19th).
 */
final class LatencyStats {

    private double[] samples = new double[64];
    private int count;

    /**
     * Adds one sample.
     *
     * @param ms duration in milliseconds
     */
    void add(double ms) {
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, count * 2);
        }
        samples[count++] = ms;
    }

    /**
     * Adds every sample of another collector.
     *
     * @param other the other collector
     */
    void addAll(LatencyStats other) {
        for (int i = 0; i < other.count; i++) {
            add(other.samples[i]);
        }
    }

    int count() {
        return count;
    }

    double mean() {
        if (count == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += samples[i];
        }
        return sum / count;
    }

    /**
     * Returns a nearest-rank percentile.
     *
     * @param p percentile, 0 (exclusive) to 100
     * @return the value, or {@code NaN} with no samples
     */
    double percentile(double p) {
        if (count == 0) {
            return Double.NaN;
        }
        if (p <= 0 || p > 100) {
            throw new IllegalArgumentException("percentile must be in (0, 100]: " + p);
        }
        double[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * count);
        return sorted[Math.max(0, rank - 1)];
    }

    double max() {
        return percentile(100);
    }

    /**
     * Returns the statistics as a JSON-ready map with values rounded to 0.01 ms.
     *
     * @return keys n, mean, p50, p95, max
     */
    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", count);
        m.put("mean", round(mean()));
        m.put("p50", round(percentile(50)));
        m.put("p95", round(percentile(95)));
        m.put("max", round(max()));
        return m;
    }

    static double round(double v) {
        return Double.isNaN(v) ? v : Math.round(v * 100.0) / 100.0;
    }
}
