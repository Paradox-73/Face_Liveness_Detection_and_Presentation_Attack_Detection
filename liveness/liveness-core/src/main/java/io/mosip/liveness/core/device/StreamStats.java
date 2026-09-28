package io.mosip.liveness.core.device;

/**
 * Counters and timing measured by a {@link FaceFrameSource} since it started.
 *
 * <p>The session uses the frame rate to filter the challenge pool (for example BLINK needs 10 fps) and to
 * detect streams below {@code integrity.min_fps}.
 */
public final class StreamStats {

    /** Statistics of a source that has not delivered anything yet. */
    public static final StreamStats EMPTY = new StreamStats(0, 0, 0, 0.0, 0.0);

    private final long framesDelivered;
    private final long framesDropped;
    private final long invalidParts;
    private final double fps;
    private final double jitterMs;

    /**
     * Creates statistics.
     *
     * @param framesDelivered frames handed to the sink
     * @param framesDropped   frames discarded because the consumer was busy
     * @param invalidParts    stream parts that were not a valid image (bad length, non-JPEG, truncated)
     * @param fps             measured frames per second over the recent window
     * @param jitterMs        standard deviation of the time between frames, in milliseconds
     * @throws IllegalArgumentException if a value is negative or not finite
     */
    public StreamStats(long framesDelivered, long framesDropped, long invalidParts, double fps, double jitterMs) {
        if (framesDelivered < 0 || framesDropped < 0 || invalidParts < 0) {
            throw new IllegalArgumentException("counters must not be negative");
        }
        if (!(fps >= 0 && jitterMs >= 0) || Double.isInfinite(fps) || Double.isInfinite(jitterMs)) {
            throw new IllegalArgumentException("fps and jitter must be finite and not negative");
        }
        this.framesDelivered = framesDelivered;
        this.framesDropped = framesDropped;
        this.invalidParts = invalidParts;
        this.fps = fps;
        this.jitterMs = jitterMs;
    }

    /**
     * Returns the number of frames handed to the sink.
     *
     * @return frame count
     */
    public long framesDelivered() {
        return framesDelivered;
    }

    /**
     * Returns the number of frames discarded because the consumer was still busy.
     *
     * @return frame count
     */
    public long framesDropped() {
        return framesDropped;
    }

    /**
     * Returns the number of stream parts that were not a valid image.
     *
     * @return part count
     */
    public long invalidParts() {
        return invalidParts;
    }

    /**
     * Returns the measured frame rate over the recent window.
     *
     * @return frames per second
     */
    public double fps() {
        return fps;
    }

    /**
     * Returns the variation of the time between frames.
     *
     * @return standard deviation in milliseconds
     */
    public double jitterMs() {
        return jitterMs;
    }

    @Override
    public String toString() {
        return "StreamStats[delivered=" + framesDelivered + ", dropped=" + framesDropped + ", invalid=" + invalidParts
                + ", fps=" + fps + ", jitterMs=" + jitterMs + "]";
    }
}
