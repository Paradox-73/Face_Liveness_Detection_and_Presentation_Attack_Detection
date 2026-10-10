/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.config;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The table of global liveness configuration keys: key name, shipped default and, for numbers, the allowed range
 * (docs/design/configuration.md). The policy resolver clamps a configured number to this range and
 * {@link LivenessPolicy.Builder} rejects a value outside it, so the two use the same limits. Where a key has a
 * per-workflow form ({@code <workflow>.passive.threshold}, {@code .min_challenges}, {@code .max_retries},
 * {@code .on_max_failure}), the range applies to it too; the per-workflow defaults are in
 * {@link LivenessConfigKeys}.
 */
public enum PolicyKey {

    /** Master switch; server only, never overridden locally. {@code N} means policy OFF everywhere. */
    ENABLE(LivenessConfigKeys.ENABLE, LivenessConfigKeys.YES, Type.YES_NO),
    /** Engine identifier, looked up in the engine registry. */
    ENGINE(LivenessConfigKeys.ENGINE, "mosip-onnx-default", Type.TEXT),
    /** Inference back end of the default ONNX engine; plain CPU is the fastest measured on desktop. */
    ENGINE_EXECUTION_PROVIDER(LivenessConfigKeys.ENGINE_EXECUTION_PROVIDER, "CPU", Type.TEXT),
    /** Passive score at or above which a face passes; floor 50 (authentication workflows ship 95). */
    PASSIVE_THRESHOLD(LivenessConfigKeys.PASSIVE_THRESHOLD, 80, 50, 100),
    /** Passive score below which the face fails at once as an attack; floor 5. */
    PAD_REJECT_THRESHOLD(LivenessConfigKeys.PAD_REJECT_THRESHOLD, 20, 5, 100),
    /**
     * Good frames for the passive decision: 30 frames (2 s at 15 fps), long enough for the loop detector to see a
     * replayed clip repeat before the passive decision.
     */
    PASSIVE_MIN_FRAMES(LivenessConfigKeys.PASSIVE_MIN_FRAMES, 30, 1, 60),
    /** Acquisition plus passive phase, in milliseconds. */
    PASSIVE_TIMEOUT_MS(LivenessConfigKeys.PASSIVE_TIMEOUT_MS, 4_000, 1_000, 60_000),
    /** Whether escalation to challenges is allowed. */
    ACTIVE_ENABLE(LivenessConfigKeys.ACTIVE_ENABLE, LivenessConfigKeys.YES, Type.YES_NO),
    /** Challenges in the active phase. */
    ACTIVE_MIN_CHALLENGES(LivenessConfigKeys.ACTIVE_MIN_CHALLENGES, 2, 1, 5),
    /** Challenge pool, comma-separated {@code ChallengeType} names. */
    ACTIVE_CHALLENGE_TYPES(LivenessConfigKeys.ACTIVE_CHALLENGE_TYPES, "BLINK,SMILE,TURN_LEFT,TURN_RIGHT,LOOK_UP",
            Type.TEXT),
    /** Time per challenge in milliseconds (accessibility: up to 15 s). */
    ACTIVE_CHALLENGE_TIMEOUT_MS(LivenessConfigKeys.ACTIVE_CHALLENGE_TIMEOUT_MS, 5_000, 2_000, 15_000),
    /** Shortest accepted reaction time after a prompt, in milliseconds. */
    ACTIVE_MIN_ONSET_MS(LivenessConfigKeys.ACTIVE_MIN_ONSET_MS, 200, 0, 2_000),
    /** Time an action must be held, in milliseconds. */
    ACTIVE_HOLD_MS(LivenessConfigKeys.ACTIVE_HOLD_MS, 300, 0, 3_000),
    /** Attempts per transaction. */
    MAX_RETRIES(LivenessConfigKeys.MAX_RETRIES, 3, 1, 10),
    /** Behaviour after the last failed attempt ({@link OnMaxFailure}); operator login ships {@code BLOCK_AND_LOCK}. */
    ON_MAX_FAILURE(LivenessConfigKeys.ON_MAX_FAILURE, OnMaxFailure.BLOCK.name(), Type.TEXT),
    /** Stream timeout of a liveness session in milliseconds. */
    STREAM_TIMEOUT_MS(LivenessConfigKeys.STREAM_TIMEOUT_MS, 60_000, 1_000, 600_000),
    /** 0 processes every frame, else at most one frame per interval (milliseconds). */
    FRAME_SAMPLE_INTERVAL_MS(LivenessConfigKeys.FRAME_SAMPLE_INTERVAL_MS, 0, 0, 5_000),
    /** Automatic stream reconnects before a device error. */
    DEVICE_RECONNECT_ATTEMPTS(LivenessConfigKeys.DEVICE_RECONNECT_ATTEMPTS, 1, 0, 10),
    /** Minimum inter-eye distance in pixels during acquisition. */
    QUALITY_MIN_IED_PX(LivenessConfigKeys.QUALITY_MIN_IED_PX, 60, 0, 1_000),
    /** Largest head turn in degrees during acquisition. */
    QUALITY_MAX_YAW_DEG(LivenessConfigKeys.QUALITY_MAX_YAW_DEG, 15, 0, 90),
    /** Identical frames in a row before the stream counts as frozen. */
    INTEGRITY_MAX_FROZEN_FRAMES(LivenessConfigKeys.INTEGRITY_MAX_FROZEN_FRAMES, 5, 1, 100),
    /** Minimum stream frame rate (the SBI minimum). */
    INTEGRITY_MIN_FPS(LivenessConfigKeys.INTEGRITY_MIN_FPS, 3, 1, 60),
    /** Minimum similarity (0-100) between the captured face and the session face. */
    BINDING_MIN_SIMILARITY(LivenessConfigKeys.BINDING_MIN_SIMILARITY, 60, 0, 100),
    /** Time limit from pass to capture in milliseconds. */
    BINDING_MAX_GAP_MS(LivenessConfigKeys.BINDING_MAX_GAP_MS, 5_000, 500, 120_000),
    /** Behaviour for devices that cannot stream ({@link NoStreamPolicy}). */
    NO_STREAM_POLICY(LivenessConfigKeys.NO_STREAM_POLICY, NoStreamPolicy.BLOCK.name(), Type.TEXT),
    /** Minimum model bundle version, {@code MAJOR.MINOR.PATCH}; blocks rollback. */
    MODEL_MIN_VERSION(LivenessConfigKeys.MODEL_MIN_VERSION, "1.3.0", Type.TEXT),
    /** Diagnostic log lines and supervisor overlay. */
    DIAGNOSTIC_ENABLE(LivenessConfigKeys.DIAGNOSTIC_ENABLE, LivenessConfigKeys.NO, Type.YES_NO),
    /** Anonymised metrics. */
    METRICS_ENABLE(LivenessConfigKeys.METRICS_ENABLE, LivenessConfigKeys.YES, Type.YES_NO),
    /** Accepted SBI certification levels, CSV. */
    MDS_ALLOWED_CERTIFICATIONS(LivenessConfigKeys.MDS_ALLOWED_CERTIFICATIONS, "L0,L1", Type.TEXT),
    /** Android face device adapter: {@code BUILTIN} (in-process camera) or {@code SBI} (device app). */
    ANDROID_DEVICE(LivenessConfigKeys.ANDROID_DEVICE, "BUILTIN", Type.TEXT),
    /** Android: block liveness on an emulator ({@code Y} recommended in production). */
    ANDROID_BLOCK_EMULATOR(LivenessConfigKeys.ANDROID_BLOCK_EMULATOR, LivenessConfigKeys.NO, Type.YES_NO),
    /** Android: allow supervisor approval without network. */
    ANDROID_OFFLINE_APPROVAL_ENABLE(LivenessConfigKeys.ANDROID_OFFLINE_APPROVAL_ENABLE, LivenessConfigKeys.YES,
            Type.YES_NO);

    /** Kind of value. */
    public enum Type {
        /** Whole number with a range. */
        NUMBER,
        /** {@code Y}/{@code N}; {@code YES}/{@code NO} and {@code TRUE}/{@code FALSE} are read too. */
        YES_NO,
        /** Text the resolver parses itself (names, lists, versions). */
        TEXT
    }

    private final String key;
    private final String defaultValue;
    private final Type type;
    private final long min;
    private final long max;

    PolicyKey(String key, String defaultValue, Type type) {
        this(key, defaultValue, type, 0, 0);
    }

    PolicyKey(String key, long defaultValue, long min, long max) {
        this(key, Long.toString(defaultValue), Type.NUMBER, min, max);
    }

    PolicyKey(String key, String defaultValue, Type type, long min, long max) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.type = type;
        this.min = min;
        this.max = max;
    }

    /**
     * Returns the full configuration key.
     *
     * @return for example {@code mosip.registration.liveness.passive.min_frames}
     */
    public String key() {
        return key;
    }

    /**
     * Returns the shipped default as written in configuration.
     *
     * @return the default text
     */
    public String defaultValue() {
        return defaultValue;
    }

    /**
     * Returns the kind of value.
     *
     * @return the type
     */
    public Type type() {
        return type;
    }

    /**
     * Returns the default of a number key.
     *
     * @return the default
     * @throws IllegalStateException if the key is not a number
     */
    public long defaultLong() {
        requireNumber();
        return Long.parseLong(defaultValue);
    }

    /**
     * Returns the default of a number key as an {@code int}.
     *
     * @return the default
     * @throws IllegalStateException if the key is not a number
     */
    public int defaultInt() {
        return (int) defaultLong();
    }

    /**
     * Returns the default of a yes/no key.
     *
     * @return {@code true} for {@code Y}
     */
    public boolean defaultOn() {
        return LivenessConfigKeys.YES.equals(defaultValue);
    }

    /**
     * Returns the smallest allowed value of a number key.
     *
     * @return the minimum
     */
    public long min() {
        requireNumber();
        return min;
    }

    /**
     * Returns the largest allowed value of a number key.
     *
     * @return the maximum
     */
    public long max() {
        requireNumber();
        return max;
    }

    /**
     * Clamps a number to the allowed range.
     *
     * @param value the value
     * @return the nearest allowed value
     */
    public long clamp(long value) {
        return Math.max(min(), Math.min(max(), value));
    }

    /**
     * Checks a number against the allowed range.
     *
     * @param value the value
     * @return the value
     * @throws IllegalArgumentException if it is outside the range
     */
    public long require(long value) {
        if (value != clamp(value)) {
            throw new IllegalArgumentException(key + " must be " + min + "-" + max + ", was " + value);
        }
        return value;
    }

    /**
     * Reads a yes/no key; an unreadable value keeps the default.
     *
     * @param config the configuration
     * @return whether the switch is on
     */
    public boolean isOn(ConfigSource config) {
        Optional<String> v;
        try {
            v = config.get(key);
        } catch (RuntimeException e) {
            return defaultOn();
        }
        return v == null || !v.isPresent() ? defaultOn() : parseYesNo(v.get()).orElse(defaultOn());
    }

    /**
     * Parses a yes/no value: {@code Y}/{@code YES}/{@code TRUE} and {@code N}/{@code NO}/{@code FALSE}, any case.
     *
     * @param value the text
     * @return the value; empty when it is neither
     */
    public static Optional<Boolean> parseYesNo(String value) {
        String s = Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT);
        if ("Y".equals(s) || "YES".equals(s) || "TRUE".equals(s)) {
            return Optional.of(Boolean.TRUE);
        }
        if ("N".equals(s) || "NO".equals(s) || "FALSE".equals(s)) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }

    private void requireNumber() {
        if (type != Type.NUMBER) {
            throw new IllegalStateException(key + " is not a number");
        }
    }
}
