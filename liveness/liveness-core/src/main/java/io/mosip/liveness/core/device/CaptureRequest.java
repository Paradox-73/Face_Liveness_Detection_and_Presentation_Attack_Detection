package io.mosip.liveness.core.device;

import java.util.Objects;
import java.util.Optional;

/**
 * Parameters for one signed face capture (SBI rCapture, or its in-process equivalent).
 */
public final class CaptureRequest {

    /** Default capture timeout in milliseconds. */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;

    private final String transactionId;
    private final String deviceSubId;
    private final long timeoutMs;
    private final PadRequest pad;
    private final FaceFrame sourceFrame;

    private CaptureRequest(Builder b) {
        this.transactionId = b.transactionId;
        this.deviceSubId = b.deviceSubId;
        this.timeoutMs = b.timeoutMs;
        this.pad = b.pad;
        this.sourceFrame = b.sourceFrame;
    }

    /**
     * Starts a builder.
     *
     * @return a builder preset with the defaults
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the transaction identifier the device must echo in its signed response.
     *
     * @return the transaction identifier
     */
    public String transactionId() {
        return transactionId;
    }

    /**
     * Returns the SBI sub-device identifier.
     *
     * @return {@code "0"} for face unless overridden
     */
    public String deviceSubId() {
        return deviceSubId;
    }

    /**
     * Returns how long the device may take.
     *
     * @return milliseconds
     */
    public long timeoutMs() {
        return timeoutMs;
    }

    /**
     * Returns the PAD request; present only for devices that advertised {@code padCapability}.
     *
     * @return the PAD request, if any
     */
    public Optional<PadRequest> pad() {
        return Optional.ofNullable(pad);
    }

    /**
     * Returns the session frame an in-process device must sign instead of taking a new picture (the Android
     * built-in L0 device signs the frame the session selected; see {@code LivenessSession.captureFrame()}).
     * SBI devices ignore it.
     *
     * @return the frame to capture, if any
     */
    public Optional<FaceFrame> sourceFrame() {
        return Optional.ofNullable(sourceFrame);
    }

    @Override
    public String toString() {
        return "CaptureRequest[tx=" + transactionId + ", subId=" + deviceSubId + ", timeoutMs=" + timeoutMs + ", pad="
                + (pad != null) + ", sourceFrame=" + (sourceFrame != null) + "]";
    }

    /** Builder for {@link CaptureRequest}. */
    public static final class Builder {

        private String transactionId;
        private String deviceSubId = StreamRequest.FACE_DEVICE_SUB_ID;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;
        private PadRequest pad;
        private FaceFrame sourceFrame;

        private Builder() {
        }

        /**
         * Sets the transaction identifier; required.
         *
         * @param transactionId identifier echoed by the device
         * @return this builder
         */
        public Builder transactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        /**
         * Sets the SBI sub-device identifier; default {@code "0"}.
         *
         * @param deviceSubId sub-device identifier
         * @return this builder
         */
        public Builder deviceSubId(String deviceSubId) {
            this.deviceSubId = deviceSubId;
            return this;
        }

        /**
         * Sets the capture timeout; default {@value CaptureRequest#DEFAULT_TIMEOUT_MS} ms.
         *
         * @param timeoutMs milliseconds; positive
         * @return this builder
         */
        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * Sets the PAD request; leave unset for devices without {@code padCapability}.
         *
         * @param pad PAD request, or {@code null}
         * @return this builder
         */
        public Builder pad(PadRequest pad) {
            this.pad = pad;
            return this;
        }

        /**
         * Sets the session frame an in-process device must sign.
         *
         * @param sourceFrame frame, or {@code null}
         * @return this builder
         */
        public Builder sourceFrame(FaceFrame sourceFrame) {
            this.sourceFrame = sourceFrame;
            return this;
        }

        /**
         * Validates the fields and creates the request.
         *
         * @return the request
         * @throws NullPointerException     if the transaction or sub-device identifier is missing
         * @throws IllegalArgumentException if the transaction identifier is empty or the timeout is not positive
         */
        public CaptureRequest build() {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(deviceSubId, "deviceSubId");
            if (transactionId.isEmpty()) {
                throw new IllegalArgumentException("transactionId must not be empty");
            }
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("timeoutMs must be positive");
            }
            return new CaptureRequest(this);
        }
    }
}
