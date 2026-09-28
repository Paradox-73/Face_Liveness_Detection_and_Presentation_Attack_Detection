package io.mosip.liveness.core.device;

import java.util.Objects;

/**
 * Parameters for opening a face preview stream.
 *
 * <p>Defaults follow the design (sections 4.5 and 4.12, change D1): a 60 s stream timeout
 * ({@code liveness.stream.timeout_ms}), a 3 s deadline for the first frame, a 2 s socket read timeout and one
 * automatic reconnect ({@code liveness.device.reconnect_attempts}).
 */
public final class StreamRequest {

    /** SBI sub-device identifier for the face camera. */
    public static final String FACE_DEVICE_SUB_ID = "0";
    /** Default stream timeout in milliseconds. */
    public static final long DEFAULT_STREAM_TIMEOUT_MS = 60_000L;
    /** Default deadline for the first frame in milliseconds. */
    public static final long DEFAULT_FIRST_FRAME_TIMEOUT_MS = 3_000L;
    /** Default socket read timeout in milliseconds; a longer silence is a stalled stream. */
    public static final int DEFAULT_SOCKET_TIMEOUT_MS = 2_000;
    /** Default number of automatic reconnects before a device error is reported. */
    public static final int DEFAULT_RECONNECT_ATTEMPTS = 1;

    private final String deviceSubId;
    private final long streamTimeoutMs;
    private final long firstFrameTimeoutMs;
    private final int socketTimeoutMs;
    private final int reconnectAttempts;

    private StreamRequest(Builder b) {
        this.deviceSubId = b.deviceSubId;
        this.streamTimeoutMs = b.streamTimeoutMs;
        this.firstFrameTimeoutMs = b.firstFrameTimeoutMs;
        this.socketTimeoutMs = b.socketTimeoutMs;
        this.reconnectAttempts = b.reconnectAttempts;
    }

    /**
     * Returns a request with every default.
     *
     * @return the default request
     */
    public static StreamRequest defaults() {
        return builder().build();
    }

    /**
     * Starts a builder preset with the defaults.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
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
     * Returns how long the device should keep streaming.
     *
     * @return milliseconds
     */
    public long streamTimeoutMs() {
        return streamTimeoutMs;
    }

    /**
     * Returns the deadline for the first frame after the stream opens.
     *
     * @return milliseconds
     */
    public long firstFrameTimeoutMs() {
        return firstFrameTimeoutMs;
    }

    /**
     * Returns the socket read timeout; a longer gap between bytes is reported as a stalled stream.
     *
     * @return milliseconds
     */
    public int socketTimeoutMs() {
        return socketTimeoutMs;
    }

    /**
     * Returns how many times the source reconnects by itself before it reports a device error.
     *
     * @return reconnect attempts, 0 or more
     */
    public int reconnectAttempts() {
        return reconnectAttempts;
    }

    @Override
    public String toString() {
        return "StreamRequest[subId=" + deviceSubId + ", streamTimeoutMs=" + streamTimeoutMs + ", firstFrameTimeoutMs="
                + firstFrameTimeoutMs + ", socketTimeoutMs=" + socketTimeoutMs + ", reconnects=" + reconnectAttempts + "]";
    }

    /** Builder for {@link StreamRequest}, preset with the defaults. */
    public static final class Builder {

        private String deviceSubId = FACE_DEVICE_SUB_ID;
        private long streamTimeoutMs = DEFAULT_STREAM_TIMEOUT_MS;
        private long firstFrameTimeoutMs = DEFAULT_FIRST_FRAME_TIMEOUT_MS;
        private int socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT_MS;
        private int reconnectAttempts = DEFAULT_RECONNECT_ATTEMPTS;

        private Builder() {
        }

        /**
         * Sets the SBI sub-device identifier.
         *
         * @param deviceSubId identifier, {@code "0"} for face
         * @return this builder
         */
        public Builder deviceSubId(String deviceSubId) {
            this.deviceSubId = deviceSubId;
            return this;
        }

        /**
         * Sets the stream timeout.
         *
         * @param streamTimeoutMs milliseconds; positive
         * @return this builder
         */
        public Builder streamTimeoutMs(long streamTimeoutMs) {
            this.streamTimeoutMs = streamTimeoutMs;
            return this;
        }

        /**
         * Sets the first-frame deadline.
         *
         * @param firstFrameTimeoutMs milliseconds; positive
         * @return this builder
         */
        public Builder firstFrameTimeoutMs(long firstFrameTimeoutMs) {
            this.firstFrameTimeoutMs = firstFrameTimeoutMs;
            return this;
        }

        /**
         * Sets the socket read timeout.
         *
         * @param socketTimeoutMs milliseconds; positive
         * @return this builder
         */
        public Builder socketTimeoutMs(int socketTimeoutMs) {
            this.socketTimeoutMs = socketTimeoutMs;
            return this;
        }

        /**
         * Sets the number of automatic reconnects.
         *
         * @param reconnectAttempts 0 or more
         * @return this builder
         */
        public Builder reconnectAttempts(int reconnectAttempts) {
            this.reconnectAttempts = reconnectAttempts;
            return this;
        }

        /**
         * Validates the fields and creates the request.
         *
         * @return the request
         * @throws IllegalArgumentException if a timeout is not positive or the reconnect count is negative
         */
        public StreamRequest build() {
            Objects.requireNonNull(deviceSubId, "deviceSubId");
            if (streamTimeoutMs <= 0 || firstFrameTimeoutMs <= 0 || socketTimeoutMs <= 0) {
                throw new IllegalArgumentException("timeouts must be positive");
            }
            if (reconnectAttempts < 0) {
                throw new IllegalArgumentException("reconnectAttempts must not be negative");
            }
            return new StreamRequest(this);
        }
    }
}
