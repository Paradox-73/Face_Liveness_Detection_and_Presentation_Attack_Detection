package io.mosip.liveness.core.device;

import io.mosip.liveness.core.image.PixelFormat;

import java.util.Arrays;
import java.util.Objects;

/**
 * One unsigned preview frame from a face stream, with the timing data the integrity checks need.
 *
 * <p>Frames come from an SBI STREAM (JPEG parts of an MJPEG response), from the Android camera (RGBA or NV21),
 * or from a replay source. Only the metadata is immutable: for speed the pixel array is not copied, and
 * {@link #data()} returns the same array the producer handed over. The producer must not reuse the array; the
 * consumer must only read it, except that the session owner may {@link #wipe()} it when the session ends.
 * {@link #toString()} never prints pixel data.
 */
public final class FaceFrame {

    private final byte[] data;
    private final PixelFormat format;
    private final int width;
    private final int height;
    private final int rotationDegrees;
    private final boolean mirrored;
    private final long monotonicNanos;
    private final long sequence;
    private final String sourceId;

    private FaceFrame(Builder b) {
        this.data = b.data;
        this.format = b.format;
        this.width = b.width;
        this.height = b.height;
        this.rotationDegrees = b.rotationDegrees;
        this.mirrored = b.mirrored;
        this.monotonicNanos = b.monotonicNanos;
        this.sequence = b.sequence;
        this.sourceId = b.sourceId;
    }

    /**
     * Starts a new frame builder.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the frame bytes (shared, not copied; read only).
     *
     * @return encoded file bytes or tightly packed raw pixels, as given by {@link #format()}
     */
    public byte[] data() {
        return data;
    }

    /**
     * Returns the data format.
     *
     * @return any byte-backed format (never {@link PixelFormat#ARGB8888_INT})
     */
    public PixelFormat format() {
        return format;
    }

    /**
     * Returns the width.
     *
     * @return width in pixels, or 0 for an encoded frame whose size is known only after decoding
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height.
     *
     * @return height in pixels, or 0 for an encoded frame whose size is known only after decoding
     */
    public int height() {
        return height;
    }

    /**
     * Returns the clockwise rotation that makes the image upright (Android sensors are usually mounted at 90
     * or 270 degrees). The decoder applies it.
     *
     * @return 0, 90, 180 or 270
     */
    public int rotationDegrees() {
        return rotationDegrees;
    }

    /**
     * Tells whether the image is mirrored left-to-right compared with what the sensor saw (a selfie-style
     * preview). Analysis frames are normally not mirrored; the flag lets detectors keep the subject's own left
     * and right correct.
     *
     * @return {@code true} if mirrored
     */
    public boolean isMirrored() {
        return mirrored;
    }

    /**
     * Returns when the frame arrived, read from the session {@link io.mosip.liveness.core.Clock}.
     *
     * @return monotonic nanoseconds
     */
    public long monotonicNanos() {
        return monotonicNanos;
    }

    /**
     * Returns the sequence number assigned by the source; it increases by at least one per frame.
     *
     * @return sequence number, 0 or more
     */
    public long sequence() {
        return sequence;
    }

    /**
     * Returns an identifier of the source (for example the device serial number or {@code "camera-front"}).
     *
     * @return the source identifier; never {@code null}
     */
    public String sourceId() {
        return sourceId;
    }

    /** Overwrites the frame bytes with zeros; called by the owner when the frame is no longer needed. */
    public void wipe() {
        Arrays.fill(data, (byte) 0);
    }

    /**
     * Describes the frame without its pixel data.
     *
     * @return a short description
     */
    @Override
    public String toString() {
        return "FaceFrame[seq=" + sequence + ", " + format + ", " + width + "x" + height + ", rot=" + rotationDegrees
                + ", mirrored=" + mirrored + ", bytes=" + data.length + ", source=" + sourceId + "]";
    }

    /** Builder for {@link FaceFrame}. */
    public static final class Builder {

        private byte[] data;
        private PixelFormat format;
        private int width;
        private int height;
        private int rotationDegrees;
        private boolean mirrored;
        private long monotonicNanos;
        private long sequence;
        private String sourceId;

        private Builder() {
        }

        /**
         * Sets the frame bytes; required. The array is taken over, not copied.
         *
         * @param data encoded file bytes or tightly packed raw pixels
         * @return this builder
         */
        public Builder data(byte[] data) {
            this.data = data;
            return this;
        }

        /**
         * Sets the data format; required.
         *
         * @param format any byte-backed format
         * @return this builder
         */
        public Builder format(PixelFormat format) {
            this.format = format;
            return this;
        }

        /**
         * Sets the size; required for raw formats, optional (0) for encoded formats.
         *
         * @param width  width in pixels
         * @param height height in pixels
         * @return this builder
         */
        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Sets the clockwise rotation needed to make the image upright; default 0.
         *
         * @param rotationDegrees 0, 90, 180 or 270
         * @return this builder
         */
        public Builder rotationDegrees(int rotationDegrees) {
            this.rotationDegrees = rotationDegrees;
            return this;
        }

        /**
         * Marks the frame as mirrored left-to-right; default {@code false}.
         *
         * @param mirrored whether the image is mirrored
         * @return this builder
         */
        public Builder mirrored(boolean mirrored) {
            this.mirrored = mirrored;
            return this;
        }

        /**
         * Sets the arrival time; required.
         *
         * @param monotonicNanos reading of the session clock's {@code monotonicNanos()}
         * @return this builder
         */
        public Builder monotonicNanos(long monotonicNanos) {
            this.monotonicNanos = monotonicNanos;
            return this;
        }

        /**
         * Sets the source sequence number; default 0.
         *
         * @param sequence sequence number, 0 or more
         * @return this builder
         */
        public Builder sequence(long sequence) {
            this.sequence = sequence;
            return this;
        }

        /**
         * Sets the source identifier; required.
         *
         * @param sourceId device serial number or camera name
         * @return this builder
         */
        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        /**
         * Validates the fields and creates the frame.
         *
         * @return the frame
         * @throws NullPointerException     if data, format or sourceId is missing
         * @throws IllegalArgumentException if the format is int-packed, the rotation is not a right angle, the
         *                                  sequence is negative, or raw data does not match the size
         */
        public FaceFrame build() {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(sourceId, "sourceId");
            if (format.isIntPacked()) {
                throw new IllegalArgumentException("frames hold bytes; int-packed format not allowed: " + format);
            }
            if (rotationDegrees != 0 && rotationDegrees != 90 && rotationDegrees != 180 && rotationDegrees != 270) {
                throw new IllegalArgumentException("rotation must be 0, 90, 180 or 270: " + rotationDegrees);
            }
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            if (!format.isEncoded() && data.length != format.requiredLength(width, height)) {
                throw new IllegalArgumentException("raw frame length " + data.length + " does not match "
                        + width + "x" + height + " " + format);
            }
            return new FaceFrame(this);
        }
    }
}
