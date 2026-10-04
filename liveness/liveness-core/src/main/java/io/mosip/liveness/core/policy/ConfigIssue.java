/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.policy;

import java.util.Objects;

/**
 * A configuration value the {@link PolicyResolver} could not use as given.
 */
public final class ConfigIssue {

    /** What was wrong with the value. */
    public enum Kind {
        /** The value could not be parsed; the default was used. */
        INVALID,
        /** The value was outside its floor or range; the nearest allowed value was used (audited as LV15). */
        CLAMPED,
        /** The value is valid but not allowed for this workflow; the default was used. */
        NOT_ALLOWED
    }

    private static final int MAX_RAW_LENGTH = 64;

    private final String key;
    private final Kind kind;
    private final String rawValue;
    private final String appliedValue;

    ConfigIssue(String key, Kind kind, String rawValue, String appliedValue) {
        this.key = Objects.requireNonNull(key, "key");
        this.kind = Objects.requireNonNull(kind, "kind");
        String raw = rawValue == null ? "" : rawValue;
        this.rawValue = raw.length() > MAX_RAW_LENGTH ? raw.substring(0, MAX_RAW_LENGTH) : raw;
        this.appliedValue = Objects.requireNonNull(appliedValue, "appliedValue");
    }

    /**
     * Returns the configuration key.
     *
     * @return the full key
     */
    public String key() {
        return key;
    }

    /**
     * Returns what was wrong.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the configured value, cut to 64 characters.
     *
     * @return the raw value
     */
    public String rawValue() {
        return rawValue;
    }

    /**
     * Returns the value used instead.
     *
     * @return the applied value
     */
    public String appliedValue() {
        return appliedValue;
    }

    /**
     * Describes the issue on one line for the diagnostic log.
     *
     * @return for example {@code config key=...passive.threshold issue=CLAMPED value=30 applied=50}
     */
    public String describe() {
        return "config key=" + key + " issue=" + kind + " value=" + rawValue.replace(' ', '_') + " applied="
                + appliedValue;
    }

    @Override
    public String toString() {
        return describe();
    }
}
