/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

/**
 * Status codes of the MOSIP biometric SDK {@code Response}, with the same numbers and messages as MOSIP's mock SDK
 * ({@code io.mosip.mock.sdk.constant.ResponseStatus}). MOSIP's SDK loaders treat 200-299 with a response body as
 * success and anything else as failure.
 */
public enum SdkStatus {

    /** The call succeeded. */
    SUCCESS(200, "OK"),
    /** An input is invalid: unreadable data, a template of another model, or an unsupported modality. */
    INVALID_INPUT(401, "Invalid Input Parameter - %s"),
    /** An input is missing: no record, or no FACE segment. */
    MISSING_INPUT(402, "Missing Input Parameter - %s"),
    /** The data is of poor quality: no face was found. */
    POOR_DATA_QUALITY(406, "Data provided is of poor quality"),
    /** Unexpected failure, for example the SDK was not initialised or a model failed. */
    UNKNOWN_ERROR(500, "UNKNOWN_ERROR");

    private final int code;
    private final String message;

    SdkStatus(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * Returns the numeric status.
     *
     * @return for example 200
     */
    public int code() {
        return code;
    }

    /**
     * Returns the status message with the detail filled in.
     *
     * @param detail what was invalid or missing; used only by messages with a {@code %s}
     * @return the message
     */
    public String message(String detail) {
        return message.contains("%s") ? String.format(message, detail) : message;
    }
}
