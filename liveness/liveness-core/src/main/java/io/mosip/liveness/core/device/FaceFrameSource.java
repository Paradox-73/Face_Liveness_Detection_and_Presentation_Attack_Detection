package io.mosip.liveness.core.device;

/**
 * A running face preview stream that pushes frames to a {@link FrameSink}.
 *
 * <p>Contract (checked by one shared contract test run against every adapter):
 * <ul>
 *   <li>{@link #start(FrameSink)} may be called once; the source owns its reader thread and returns at once.</li>
 *   <li>Sink callbacks come from that thread, one at a time, never concurrently.</li>
 *   <li>Sequence numbers and timestamps of delivered frames increase.</li>
 *   <li>Invalid stream parts are counted in {@link StreamStats#invalidParts()}, not delivered.</li>
 *   <li>The source reconnects by itself up to {@link StreamRequest#reconnectAttempts()} times; then it reports
 *       the failure.</li>
 *   <li>A stream ends with exactly one terminal callback: {@link FrameSink#onError(DeviceError)} or
 *       {@link FrameSink#onEnd(EndReason)}. No callback follows it.</li>
 *   <li>{@link #close()} is idempotent, does not throw, stops the reader thread and, if the stream had not yet
 *       ended, produces {@code onEnd(CLOSED)}.</li>
 * </ul>
 */
public interface FaceFrameSource extends AutoCloseable {

    /**
     * Starts delivering frames.
     *
     * @param sink receiver of frames and of the terminal callback
     * @throws DeviceException       if the stream cannot start
     * @throws IllegalStateException if the source was already started or closed
     */
    void start(FrameSink sink) throws DeviceException;

    /**
     * Returns the statistics measured since the start; safe to call from any thread.
     *
     * @return the current statistics
     */
    StreamStats stats();

    /** Stops the stream and releases its resources. Idempotent; never throws. */
    @Override
    void close();
}
