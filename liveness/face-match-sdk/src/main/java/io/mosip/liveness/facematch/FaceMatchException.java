/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import java.util.Objects;

/**
 * A face sample could not be used. {@link #problem()} says why and decides the MOSIP response status. The message
 * never contains biometric data.
 */
public final class FaceMatchException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why a sample could not be used. */
    public enum Problem {
        /** The BDB is neither a face record, an image nor a template this SDK can read. */
        UNREADABLE,
        /** No face was found in the image. */
        NO_FACE,
        /** The template was made by another model and cannot be compared. */
        OTHER_MODEL,
        /** The model failed while analysing the image. */
        ENGINE
    }

    private final Problem problem;

    /**
     * Creates the exception.
     *
     * @param problem why the sample could not be used
     * @param message description without biometric data
     * @param cause   underlying error, or {@code null}
     */
    public FaceMatchException(Problem problem, String message, Throwable cause) {
        super(message, cause);
        this.problem = Objects.requireNonNull(problem, "problem");
    }

    /**
     * Returns why the sample could not be used.
     *
     * @return the problem
     */
    public Problem problem() {
        return problem;
    }
}
