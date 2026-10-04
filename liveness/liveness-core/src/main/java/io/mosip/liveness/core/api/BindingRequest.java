/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.device.DevicePadResult;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.image.ImageBuffer;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The signed capture and the pass token presented to {@link LivenessSession#bind(BindingRequest)}.
 *
 * <p>The caller has already verified the capture signature and decoded the face image from the ISO/IEC
 * 19794-5 record. A missing token is allowed here so the session can fail it with {@code TOKEN_MISSING}.
 */
public final class BindingRequest {

    private final LivenessPassToken token;
    private final ImageBuffer captureImage;
    private final Instant capturedAt;
    private final String deviceSerial;
    private final String echoedChallenge;
    private final DevicePadResult devicePad;

    private BindingRequest(Builder b) {
        this.token = b.token;
        this.captureImage = b.captureImage;
        this.capturedAt = b.capturedAt;
        this.deviceSerial = b.deviceSerial;
        this.echoedChallenge = b.echoedChallenge;
        this.devicePad = b.devicePad;
    }

    /**
     * Starts a builder.
     *
     * @param captureImage face image decoded from the signed ISO record
     * @param capturedAt   capture time reported by the device
     * @param deviceSerial serial number of the device that signed the capture
     * @return a builder
     */
    public static Builder builder(ImageBuffer captureImage, Instant capturedAt, String deviceSerial) {
        return new Builder(captureImage, capturedAt, deviceSerial);
    }

    /**
     * Creates a request from a {@link SignedFaceCapture}, copying its time, device serial, challenge echo and
     * device PAD result.
     *
     * @param capture      the signed capture
     * @param captureImage face image decoded from {@link SignedFaceCapture#isoRecord()}
     * @param token        the pass token presented with the capture, or {@code null} if none
     * @return the request
     */
    public static BindingRequest from(SignedFaceCapture capture, ImageBuffer captureImage, LivenessPassToken token) {
        Objects.requireNonNull(capture, "capture");
        return builder(captureImage, capture.capturedAt(), capture.deviceSerial())
                .token(token)
                .echoedChallenge(capture.echoedChallenge().orElse(null))
                .devicePad(capture.devicePad().orElse(null))
                .build();
    }

    /**
     * Returns the presented pass token.
     *
     * @return the token, or empty if none was presented
     */
    public Optional<LivenessPassToken> token() {
        return Optional.ofNullable(token);
    }

    /**
     * Returns the decoded face image of the capture.
     *
     * @return the image
     */
    public ImageBuffer captureImage() {
        return captureImage;
    }

    /**
     * Returns the device capture time.
     *
     * @return the capture instant
     */
    public Instant capturedAt() {
        return capturedAt;
    }

    /**
     * Returns the serial number of the device that signed the capture.
     *
     * @return the serial number
     */
    public String deviceSerial() {
        return deviceSerial;
    }

    /**
     * Returns the PAD challenge the device echoed.
     *
     * @return the echoed nonce, if any
     */
    public Optional<String> echoedChallenge() {
        return Optional.ofNullable(echoedChallenge);
    }

    /**
     * Returns the device's own PAD result.
     *
     * @return the device PAD result, if the capture carried one
     */
    public Optional<DevicePadResult> devicePad() {
        return Optional.ofNullable(devicePad);
    }

    @Override
    public String toString() {
        return "BindingRequest[token=" + (token != null) + ", " + captureImage + ", at=" + capturedAt + ", device="
                + deviceSerial + ", echo=" + (echoedChallenge != null) + ", devicePad="
                + (devicePad == null ? "none" : devicePad.decision()) + "]";
    }

    /** Builder for {@link BindingRequest}. */
    public static final class Builder {

        private final ImageBuffer captureImage;
        private final Instant capturedAt;
        private final String deviceSerial;
        private LivenessPassToken token;
        private String echoedChallenge;
        private DevicePadResult devicePad;

        private Builder(ImageBuffer captureImage, Instant capturedAt, String deviceSerial) {
            this.captureImage = Objects.requireNonNull(captureImage, "captureImage");
            this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
            this.deviceSerial = Objects.requireNonNull(deviceSerial, "deviceSerial");
        }

        /**
         * Sets the presented pass token.
         *
         * @param token the token, or {@code null} if none was presented
         * @return this builder
         */
        public Builder token(LivenessPassToken token) {
            this.token = token;
            return this;
        }

        /**
         * Sets the echoed PAD challenge.
         *
         * @param echoedChallenge nonce echoed by the device, or {@code null}
         * @return this builder
         */
        public Builder echoedChallenge(String echoedChallenge) {
            this.echoedChallenge = echoedChallenge;
            return this;
        }

        /**
         * Sets the device PAD result.
         *
         * @param devicePad result, or {@code null}
         * @return this builder
         */
        public Builder devicePad(DevicePadResult devicePad) {
            this.devicePad = devicePad;
            return this;
        }

        /**
         * Creates the request.
         *
         * @return the request
         */
        public BindingRequest build() {
            return new BindingRequest(this);
        }
    }
}
