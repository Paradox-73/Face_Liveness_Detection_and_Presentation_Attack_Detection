/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

import java.util.Objects;
import java.util.Optional;

/**
 * A device failure reported through {@link FrameSink#onError(DeviceError)} or carried by a
 * {@link DeviceException}.
 */
public final class DeviceError {

    private final DeviceErrorCode code;
    private final String message;
    private final Throwable cause;

    private DeviceError(DeviceErrorCode code, String message, Throwable cause) {
        this.code = code;
        this.message = message;
        this.cause = cause;
    }

    /**
     * Creates an error without an underlying exception.
     *
     * @param code    what went wrong; not {@code null}
     * @param message description safe for logs (no image data, no user identifiers)
     * @return the error
     */
    public static DeviceError of(DeviceErrorCode code, String message) {
        return of(code, message, null);
    }

    /**
     * Creates an error with an underlying exception.
     *
     * @param code    what went wrong; not {@code null}
     * @param message description safe for logs (no image data, no user identifiers)
     * @param cause   underlying exception, or {@code null}
     * @return the error
     */
    public static DeviceError of(DeviceErrorCode code, String message, Throwable cause) {
        Objects.requireNonNull(code, "code");
        return new DeviceError(code, message == null ? "" : message, cause);
    }

    /**
     * Returns the error code.
     *
     * @return the code
     */
    public DeviceErrorCode code() {
        return code;
    }

    /**
     * Returns the description.
     *
     * @return the message; never {@code null}
     */
    public String message() {
        return message;
    }

    /**
     * Returns the underlying exception.
     *
     * @return the cause, if any
     */
    public Optional<Throwable> cause() {
        return Optional.ofNullable(cause);
    }

    @Override
    public String toString() {
        return "DeviceError[" + code + ": " + message + "]";
    }
}
