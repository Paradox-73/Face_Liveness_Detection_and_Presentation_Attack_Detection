/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import java.util.Arrays;

/**
 * Combines per-frame passive PAD live probabilities into one 0-100 score (docs/design/decision-flow.md section 1):
 * {@code S = round(100 * median(p_f))} over the most recent frames. A trim fraction below 0.5 gives a trimmed
 * mean instead (drop that share of the lowest and highest values, average the rest).
 */
final class PassivePadAggregator {

    private final double[] window;
    private final double trimFraction;
    private int size;
    private int next;
    private long total;

    /**
     * Creates an aggregator.
     *
     * @param capacity     number of most recent values kept; at least 1
     * @param trimFraction 0.5 for the median, lower for a trimmed mean
     */
    PassivePadAggregator(int capacity, double trimFraction) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.window = new double[capacity];
        this.trimFraction = trimFraction;
    }

    /**
     * Adds a frame's live probability.
     *
     * @param liveProbability value 0-1
     */
    void add(double liveProbability) {
        window[next] = liveProbability;
        next = (next + 1) % window.length;
        if (size < window.length) {
            size++;
        }
        total++;
    }

    /**
     * Returns the number of values kept (at most the capacity).
     *
     * @return value count
     */
    int count() {
        return size;
    }

    /**
     * Returns the number of values added since the last clear.
     *
     * @return value count
     */
    long totalAdded() {
        return total;
    }

    /**
     * Tells whether the window is full.
     *
     * @return {@code true} when as many values as the capacity are kept
     */
    boolean isFull() {
        return size == window.length;
    }

    /** Forgets all values. */
    void clear() {
        size = 0;
        next = 0;
        total = 0;
    }

    /**
     * Returns the aggregate score.
     *
     * @return {@code round(100 * aggregate)}, or -1 when no value was added
     */
    int score() {
        if (size == 0) {
            return -1;
        }
        double[] sorted = Arrays.copyOf(window, size);
        Arrays.sort(sorted);
        double value = trimFraction >= 0.5 ? median(sorted) : trimmedMean(sorted, trimFraction);
        return (int) Math.round(100 * Math.max(0, Math.min(1, value)));
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    static double trimmedMean(double[] sorted, double fraction) {
        int drop = (int) Math.floor(sorted.length * fraction);
        if (2 * drop >= sorted.length) {
            return median(sorted);
        }
        double sum = 0;
        for (int i = drop; i < sorted.length - drop; i++) {
            sum += sorted[i];
        }
        return sum / (sorted.length - 2 * drop);
    }
}
