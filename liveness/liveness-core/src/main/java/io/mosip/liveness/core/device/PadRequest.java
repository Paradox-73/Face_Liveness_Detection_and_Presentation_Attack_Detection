/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

import java.util.Objects;

/**
 * The {@code bio[].pad} object added to a capture request for devices that advertise {@code padCapability}
 * (MOSIP issue {@code #1117} shape: {@code {"required": true, "method": "passive", "challenge": "<nonce>"}}).
 *
 * <p>The device must echo {@link #challenge()} in its signed response; the binding step checks the echo. Stock
 * devices get no {@code pad} object at all.
 */
public final class PadRequest {

    private final boolean required;
    private final PadMethod method;
    private final String challenge;

    private PadRequest(boolean required, PadMethod method, String challenge) {
        this.required = required;
        this.method = method;
        this.challenge = challenge;
    }

    /**
     * Creates a PAD request.
     *
     * @param required  whether the device must run PAD
     * @param method    method to apply; not {@code null}
     * @param challenge nonce from the liveness pass token; not empty
     * @return the request
     */
    public static PadRequest of(boolean required, PadMethod method, String challenge) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(challenge, "challenge");
        if (challenge.isEmpty()) {
            throw new IllegalArgumentException("challenge must not be empty");
        }
        return new PadRequest(required, method, challenge);
    }

    /**
     * Tells whether the device must run PAD.
     *
     * @return the {@code required} flag
     */
    public boolean isRequired() {
        return required;
    }

    /**
     * Returns the requested method.
     *
     * @return the method
     */
    public PadMethod method() {
        return method;
    }

    /**
     * Returns the nonce the device must echo.
     *
     * @return the challenge nonce
     */
    public String challenge() {
        return challenge;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PadRequest)) {
            return false;
        }
        PadRequest other = (PadRequest) o;
        return required == other.required && method == other.method && challenge.equals(other.challenge);
    }

    @Override
    public int hashCode() {
        return Objects.hash(required, method, challenge);
    }

    @Override
    public String toString() {
        return "PadRequest[required=" + required + ", method=" + method.wireValue() + "]";
    }
}
