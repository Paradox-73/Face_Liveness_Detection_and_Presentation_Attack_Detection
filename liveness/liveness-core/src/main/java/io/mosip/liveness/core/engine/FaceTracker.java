package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.vision.FaceBox;

/**
 * Follows the subject's face from frame to frame (design section 4.8, "tracking"): consecutive boxes must
 * overlap (intersection over union at least {@code integrity.tracking_min_iou}) and must not be further apart in
 * time than {@code integrity.tracking_max_gap_ms}, raised to 2.5 frame intervals on slow streams (at 3 fps one
 * missed detection already makes a 667 ms gap). A broken track means the face may have been swapped.
 */
final class FaceTracker {

    private final double minIou;
    private final long maxGapNanos;
    private FaceBox last;
    private long lastNanos;

    FaceTracker(EngineParameters p) {
        this.minIou = p.get(EngineParameter.TRACKING_MIN_IOU);
        this.maxGapNanos = p.getLong(EngineParameter.TRACKING_MAX_GAP_MS) * 1_000_000L;
    }

    /**
     * Adds the subject's box of a frame.
     *
     * @param nanos          frame time
     * @param box            the subject's face box
     * @param meanIntervalMs mean time between analysed frames, 0 if unknown
     * @return {@code true} if the face continues the track (or starts it), {@code false} if the track broke
     */
    boolean update(long nanos, FaceBox box, double meanIntervalMs) {
        boolean continuous = true;
        if (last != null) {
            long gapLimit = Math.max(maxGapNanos, (long) (2.5 * meanIntervalMs * 1_000_000L));
            continuous = nanos - lastNanos <= gapLimit && box.iou(last) >= minIou;
        }
        last = box;
        lastNanos = nanos;
        return continuous;
    }

    /** Forgets the track. */
    void reset() {
        last = null;
    }
}
