package io.mosip.registration.livenessmanager.session;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;

/**
 * Measures how long the session takes to process each frame (decode, face detection, landmarks, PAD models and
 * challenge tracking on the worker thread). It sits between the frame hand-off and the session, so the waiting
 * time for the next camera frame is not counted. The summary is written to Logcat when an attempt ends, which is
 * how the per-frame latency on a device or emulator is measured (no image data, only durations).
 *
 * <p>Worker thread only, like the session it wraps.
 */
final class FrameTimer implements FrameSink {

    private static final int CAPACITY = 4096;

    private final FrameSink target;
    private final LongSupplier nanoTime;
    private final long[] micros = new long[CAPACITY];
    private long total;

    /**
     * Wraps a frame sink.
     *
     * @param target   the session
     * @param nanoTime monotonic time source ({@code System::nanoTime})
     */
    FrameTimer(FrameSink target, LongSupplier nanoTime) {
        this.target = Objects.requireNonNull(target, "target");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void onFrame(FaceFrame frame) {
        long start = nanoTime.getAsLong();
        try {
            target.onFrame(frame);
        } finally {
            record((nanoTime.getAsLong() - start) / 1_000L);
        }
    }

    @Override
    public void onError(DeviceError error) {
        target.onError(error);
    }

    @Override
    public void onEnd(EndReason reason) {
        target.onEnd(reason);
    }

    /**
     * Returns the number of frames timed.
     *
     * @return frames passed to the session
     */
    int frames() {
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Returns a percentile of the per-frame time, over the most recent {@value #CAPACITY} frames.
     *
     * @param percent 0-100
     * @return milliseconds, or 0 when no frame was timed
     */
    double percentileMs(double percent) {
        int n = (int) Math.min(total, CAPACITY);
        if (n == 0) {
            return 0.0;
        }
        long[] sorted = Arrays.copyOf(micros, n);
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percent / 100.0 * n) - 1;
        return sorted[Math.max(0, Math.min(n - 1, index))] / 1000.0;
    }

    /**
     * Returns a one-line summary for Logcat.
     *
     * @return for example {@code frames=42 p50=31.2ms p95=48.0ms max=61.5ms}
     */
    String summary() {
        return String.format(Locale.ROOT, "frames=%d p50=%.1fms p95=%.1fms max=%.1fms", frames(),
                percentileMs(50), percentileMs(95), percentileMs(100));
    }

    private void record(long elapsedMicros) {
        micros[(int) (total % CAPACITY)] = elapsedMicros;
        total++;
    }
}
