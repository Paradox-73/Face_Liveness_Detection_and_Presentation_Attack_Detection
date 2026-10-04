/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core;

import java.util.Objects;

/**
 * Checked exception thrown by the liveness engine and its model adapters (face analyser, passive PAD, face
 * embedder) when they cannot produce a result.
 *
 * <p>Messages must not contain image data, embeddings, scores tied to a person, or user identifiers.
 */
public class EngineException extends Exception {

    private static final long serialVersionUID = 1L;

    private final EngineErrorCode errorCode;

    /**
     * Creates an exception with a code and a message.
     *
     * @param errorCode what went wrong; not {@code null}
     * @param message   description safe for logs
     */
    public EngineException(EngineErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /**
     * Creates an exception with a code, a message and the underlying cause.
     *
     * @param errorCode what went wrong; not {@code null}
     * @param message   description safe for logs
     * @param cause     underlying exception
     */
    public EngineException(EngineErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /**
     * Returns the error code.
     *
     * @return the code; never {@code null}
     */
    public EngineErrorCode getErrorCode() {
        return errorCode;
    }
}
