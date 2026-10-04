/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core;

/**
 * Error codes for failures inside the liveness engine or its model adapters.
 *
 * <p>Any engine error ends the session with {@link FailureReason#ENGINE} (fail closed) and is audited as
 * {@code REG-EVT-LV13}; model integrity codes are also audited as {@code REG-EVT-LV14}.
 */
public enum EngineErrorCode {

    /** The engine is still loading its models. */
    NOT_READY(false),
    /** A model file listed in the bundle manifest is missing. */
    MODEL_MISSING(true),
    /** A model file's SHA-256 or the bundle signature does not verify. */
    MODEL_INTEGRITY(true),
    /** The bundle version is below {@code model.min_version} (rollback blocked). */
    MODEL_VERSION(true),
    /** The inference runtime could not load a verified model. */
    MODEL_LOAD(false),
    /** Inference failed at run time. */
    INFERENCE(false),
    /** Inference was too slow (watchdog: three inferences over 500 ms). */
    INFERENCE_TIMEOUT(false),
    /** The configuration handed to the engine is invalid. */
    CONFIG_INVALID(false),
    /** The request asks for something this engine does not support. */
    UNSUPPORTED(false),
    /** Any other internal error. */
    INTERNAL(false);

    private final boolean modelIntegrity;

    EngineErrorCode(boolean modelIntegrity) {
        this.modelIntegrity = modelIntegrity;
    }

    /**
     * Tells whether this error means the model bundle failed verification (audited as {@code REG-EVT-LV14}).
     *
     * @return {@code true} for {@link #MODEL_MISSING}, {@link #MODEL_INTEGRITY} and {@link #MODEL_VERSION}
     */
    public boolean isModelIntegrityFailure() {
        return modelIntegrity;
    }
}
