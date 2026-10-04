/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable set of {@link EngineParameter} values: the detection thresholds of the default engine.
 *
 * <p>{@link #defaults()} gives the shipped values. A model bundle may replace some of them through its signed
 * {@code engineParams} ({@link #fromMap(Map)}); unknown keys and out-of-range values are rejected, so a bundle
 * cannot switch a check off by mistake.
 */
public final class EngineParameters {

    private static final EngineParameters DEFAULTS = new Builder().build();

    private final double[] values;

    private EngineParameters(double[] values) {
        this.values = values;
    }

    /**
     * Returns the shipped defaults.
     *
     * @return the default parameters
     */
    public static EngineParameters defaults() {
        return DEFAULTS;
    }

    /**
     * Starts a builder preset with the defaults.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates parameters from key-value pairs, for example the {@code engineParams} object of a bundle manifest.
     * Keys not given keep their default.
     *
     * @param values keys from {@link EngineParameter#key()} with numeric values (numbers or numeric text)
     * @return the parameters
     * @throws IllegalArgumentException if a key is unknown, a value is not numeric or out of range, or the
     *                                  values contradict each other
     */
    public static EngineParameters fromMap(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        Builder b = builder();
        for (Map.Entry<String, ?> e : values.entrySet()) {
            EngineParameter p = EngineParameter.fromKey(e.getKey());
            if (p == null) {
                throw new IllegalArgumentException("unknown engine parameter: " + e.getKey());
            }
            b.set(p, toDouble(e.getKey(), e.getValue()));
        }
        return b.build();
    }

    private static double toDouble(String key, Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("engine parameter " + key + " is not a number", e);
            }
        }
        throw new IllegalArgumentException("engine parameter " + key + " is not a number");
    }

    /**
     * Returns a value.
     *
     * @param parameter the parameter
     * @return its value
     */
    public double get(EngineParameter parameter) {
        return values[parameter.ordinal()];
    }

    /**
     * Returns a whole-number value.
     *
     * @param parameter the parameter
     * @return its value as an int
     */
    public int getInt(EngineParameter parameter) {
        return (int) Math.round(values[parameter.ordinal()]);
    }

    /**
     * Returns a whole-number value.
     *
     * @param parameter the parameter
     * @return its value as a long
     */
    public long getLong(EngineParameter parameter) {
        return Math.round(values[parameter.ordinal()]);
    }

    /**
     * Returns a switch value.
     *
     * @param parameter a 0/1 parameter
     * @return {@code true} when the value is 1
     */
    public boolean isOn(EngineParameter parameter) {
        return values[parameter.ordinal()] >= 0.5;
    }

    /**
     * Returns all values by key, for the diagnostic page and tests.
     *
     * @return an unmodifiable map in {@link EngineParameter} order
     */
    public Map<String, Double> asMap() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (EngineParameter p : EngineParameter.values()) {
            m.put(p.key(), values[p.ordinal()]);
        }
        return Collections.unmodifiableMap(m);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof EngineParameters && Arrays.equals(values, ((EngineParameters) o).values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "EngineParameters" + asMap();
    }

    /** Builder for {@link EngineParameters}, preset with the defaults. */
    public static final class Builder {

        private final double[] values = new double[EngineParameter.values().length];

        private Builder() {
            for (EngineParameter p : EngineParameter.values()) {
                values[p.ordinal()] = p.defaultValue();
            }
        }

        /**
         * Sets one value.
         *
         * @param parameter the parameter
         * @param value     a value within the parameter's range (a whole number for integer parameters)
         * @return this builder
         * @throws IllegalArgumentException if the value is out of range or not a whole number where required
         */
        public Builder set(EngineParameter parameter, double value) {
            Objects.requireNonNull(parameter, "parameter");
            if (!(value >= parameter.min() && value <= parameter.max())) {
                throw new IllegalArgumentException(parameter.key() + " must be between " + parameter.min() + " and "
                        + parameter.max() + ": " + value);
            }
            if (parameter.isInteger() && value != Math.rint(value)) {
                throw new IllegalArgumentException(parameter.key() + " must be a whole number: " + value);
            }
            values[parameter.ordinal()] = value;
            return this;
        }

        /**
         * Validates the combination of values and creates the parameters.
         *
         * @return the parameters
         * @throws IllegalArgumentException if a minimum exceeds its maximum or the blink levels are inverted
         */
        public EngineParameters build() {
            order(EngineParameter.CHALLENGE_DELAY_MIN_MS, EngineParameter.CHALLENGE_DELAY_MAX_MS);
            order(EngineParameter.MIN_BRIGHTNESS, EngineParameter.MAX_BRIGHTNESS);
            order(EngineParameter.BLINK_MIN_CLOSED_MS, EngineParameter.BLINK_MAX_CLOSED_MS);
            order(EngineParameter.BLINK_CLOSE_RATIO, EngineParameter.BLINK_OPEN_RATIO);
            return new EngineParameters(values.clone());
        }

        private void order(EngineParameter low, EngineParameter high) {
            if (values[low.ordinal()] > values[high.ordinal()]) {
                throw new IllegalArgumentException(low.key() + " must not exceed " + high.key());
            }
        }
    }
}
