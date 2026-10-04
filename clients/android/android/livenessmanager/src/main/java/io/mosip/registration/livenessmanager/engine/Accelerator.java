/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.engine;

import java.util.Locale;

/**
 * ONNX Runtime execution provider for the liveness models on Android, chosen with the synced key
 * {@value #CONFIG_KEY}.
 *
 * <p>{@link #CPU} (the default) is ONNX Runtime's own CPU code. {@link #XNNPACK} uses the XNNPACK library for the
 * operators it supports (float models, ARM and x86 CPUs). {@link #NNAPI} hands supported operators to the Android
 * Neural Networks API, which may run them on a GPU, DSP or NPU depending on the device. Operators a provider does
 * not support stay on the CPU, so the results are the same models' results; only the speed differs. When a
 * provider is missing or refuses the models the engine loads on the CPU (see {@link AcceleratorCustomizer} and
 * {@link EngineLoader#onnx}).
 */
public enum Accelerator {

    /** ONNX Runtime's CPU execution (default). */
    CPU,
    /** XNNPACK execution provider. */
    XNNPACK,
    /** Android Neural Networks API execution provider. */
    NNAPI;

    /**
     * Configuration key: {@code CPU} (default), {@code XNNPACK} or {@code NNAPI}. The same key and values as the
     * engine's {@code LivenessConfigKeys.ENGINE_EXECUTION_PROVIDER}, so one synced setting serves both clients;
     * synced only, never overridable locally.
     */
    public static final String CONFIG_KEY = "mosip.registration.liveness.engine.execution_provider";

    /**
     * Parses a configuration value.
     *
     * @param value the value; blank or unknown values give {@link #CPU}
     * @return the accelerator
     */
    public static Accelerator parse(String value) {
        if (value == null) {
            return CPU;
        }
        switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "XNNPACK":
                return XNNPACK;
            case "NNAPI":
                return NNAPI;
            default:
                return CPU;
        }
    }
}
