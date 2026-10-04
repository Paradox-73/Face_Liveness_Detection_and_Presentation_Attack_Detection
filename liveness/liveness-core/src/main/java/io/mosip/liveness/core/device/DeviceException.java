/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

import java.util.Objects;

/**
 * Checked exception thrown by {@link FaceCaptureDevice} and {@link FaceFrameSource} operations.
 */
public class DeviceException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient DeviceError error;

    /**
     * Creates an exception from an error code and message.
     *
     * @param code    what went wrong; not {@code null}
     * @param message description safe for logs
     */
    public DeviceException(DeviceErrorCode code, String message) {
        this(DeviceError.of(code, message));
    }

    /**
     * Creates an exception from an error code, message and cause.
     *
     * @param code    what went wrong; not {@code null}
     * @param message description safe for logs
     * @param cause   underlying exception
     */
    public DeviceException(DeviceErrorCode code, String message, Throwable cause) {
        this(DeviceError.of(code, message, cause));
    }

    /**
     * Creates an exception carrying an existing error.
     *
     * @param error the device error; not {@code null}
     */
    public DeviceException(DeviceError error) {
        super(Objects.requireNonNull(error, "error").message(), error.cause().orElse(null));
        this.error = error;
    }

    /**
     * Returns the device error.
     *
     * @return the error
     */
    public DeviceError getError() {
        return error;
    }

    /**
     * Returns the error code.
     *
     * @return the code
     */
    public DeviceErrorCode getErrorCode() {
        return error.code();
    }
}
