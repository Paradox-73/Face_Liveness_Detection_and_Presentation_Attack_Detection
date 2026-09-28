package io.mosip.liveness.core.stream;

/**
 * Measures frame rate and inter-arrival jitter over the most recent frames.
 *
 * <p>Two rates are available. {@link #fps()} counts the frames recorded here (for example the frames a session
 * actually analysed). {@link #sourceFps()} uses the sequence numbers the source assigned, so frames dropped
 * between the source and this meter still count: that is the rate the device produced. Thread-safe.
 */
public final class FrameRateMeter {

    /** Default number of recent frames kept. */
    public static final int DEFAULT_CAPACITY = 30;

    private final long[] times;
    private final long[] sequences;
    private int size;
    private int next;

    /**
     * Creates a meter keeping the most recent {@value #DEFAULT_CAPACITY} frames.
     */
    public FrameRateMeter() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a meter.
     *
     * @param capacity number of recent frames kept; at least 2
     * @throws IllegalArgumentException if the capacity is below 2
     */
    public FrameRateMeter(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2");
        }
        this.times = new long[capacity];
        this.sequences = new long[capacity];
    }

    /**
     * Records one frame.
     *
     * @param monotonicNanos arrival time of the frame
     * @param sequence       sequence number assigned by the source
     */
    public synchronized void record(long monotonicNanos, long sequence) {
        times[next] = monotonicNanos;
        sequences[next] = sequence;
        next = (next + 1) % times.length;
        if (size < times.length) {
            size++;
        }
    }

    /**
     * Returns the number of frames currently kept.
     *
     * @return frame count, at most the capacity
     */
    public synchronized int count() {
        return size;
    }

    /**
     * Returns the time between the oldest and the newest kept frame.
     *
     * @return nanoseconds; 0 with fewer than two frames
     */
    public synchronized long spanNanos() {
        return size < 2 ? 0 : times[newest()] - times[oldest()];
    }

    /**
     * Returns the rate of the recorded frames.
     *
     * @return frames per second; 0 with fewer than two frames or no elapsed time
     */
    public synchronized double fps() {
        long span = spanNanos();
        return span <= 0 ? 0 : (size - 1) * 1e9 / span;
    }

    /**
     * Returns the rate at which the source produced frames, from the sequence numbers of the recorded frames.
     *
     * @return frames per second; 0 with fewer than two frames or no elapsed time
     */
    public synchronized double sourceFps() {
        long span = spanNanos();
        if (span <= 0) {
            return 0;
        }
        long frames = sequences[newest()] - sequences[oldest()];
        return frames <= 0 ? 0 : frames * 1e9 / span;
    }

    /**
     * Returns the variation of the time between recorded frames.
     *
     * @return standard deviation of the intervals in milliseconds; 0 with fewer than three frames
     */
    public synchronized double jitterMs() {
        if (size < 3) {
            return 0;
        }
        int intervals = size - 1;
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < intervals; i++) {
            double ms = (times[index(i + 1)] - times[index(i)]) / 1e6;
            sum += ms;
            sumSq += ms * ms;
        }
        double mean = sum / intervals;
        return Math.sqrt(Math.max(0, sumSq / intervals - mean * mean));
    }

    /**
     * Returns the mean time between recorded frames.
     *
     * @return milliseconds; 0 with fewer than two frames
     */
    public synchronized double meanIntervalMs() {
        return size < 2 ? 0 : spanNanos() / 1e6 / (size - 1);
    }

    /** Forgets every recorded frame. */
    public synchronized void clear() {
        size = 0;
        next = 0;
    }

    private int oldest() {
        return index(0);
    }

    private int newest() {
        return index(size - 1);
    }

    /** Maps a position counted from the oldest kept frame to an array index. */
    private int index(int fromOldest) {
        int start = size < times.length ? 0 : next;
        return (start + fromOldest) % times.length;
    }
}
