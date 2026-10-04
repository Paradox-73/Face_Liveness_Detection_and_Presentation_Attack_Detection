/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.stream.FrameRateMeter;

import java.util.Arrays;

/**
 * Frame integrity checks that catch what passive PAD misses (docs/design/engine.md section 6):
 * <ul>
 *   <li>{@code TIMESTAMP_REGRESSION}: a sequence number or timestamp that does not increase;</li>
 *   <li>{@code INVALID_FRAMES}: the source's frame rate (from sequence numbers, so frames dropped on the way
 *       still count) below {@code integrity.min_fps} minus a jitter tolerance, or too many undecodable frames in
 *       a row;</li>
 *   <li>{@code FROZEN}: more than {@code integrity.max_frozen_frames} identical frames in a row, identical meaning
 *       the same SHA-256 of the encoded bytes or a mean absolute difference of the 64x64 grey thumbnails below
 *       a small limit (stock MockMDS resends one JPEG, so it is flagged by design);</li>
 *   <li>{@code LOOP}: the last few frames repeat an earlier run of frames in the recent window (a replayed
 *       clip), judged on 64-bit difference hashes. The matched run must contain real motion and match far more
 *       closely than the frames differ from each other, so a still person is never reported as a loop.</li>
 * </ul>
 */
final class FrameIntegrityChecker {

    private final int maxFrozenFrames;
    private final double frozenMeanAbsDiff;
    private final double minFps;
    private final long fpsWindowNanos;
    private final int maxCorruptFrames;
    private final int loopMatch;
    private final int loopMaxDistance;
    private final int loopMinMotion;
    private final long[] hashes;
    private final FrameRateMeter sourceRate = new FrameRateMeter(60);

    private long lastSequence = -1;
    private long lastNanos;
    private long firstNanos = -1;
    private byte[] lastDigest;
    private int[] lastThumb;
    private int frozenRun;
    private int corruptRun;
    private int hashCount;

    FrameIntegrityChecker(LivenessPolicy policy, EngineParameters p) {
        this.maxFrozenFrames = policy.integrityMaxFrozenFrames();
        this.frozenMeanAbsDiff = p.get(EngineParameter.FROZEN_MEAN_ABS_DIFF);
        this.minFps = policy.integrityMinFps() * (1 - p.get(EngineParameter.FPS_TOLERANCE));
        this.fpsWindowNanos = p.getLong(EngineParameter.FPS_WINDOW_MS) * 1_000_000L;
        this.maxCorruptFrames = p.getInt(EngineParameter.MAX_CORRUPT_FRAMES);
        this.loopMatch = p.getInt(EngineParameter.LOOP_MATCH_FRAMES);
        this.loopMaxDistance = p.getInt(EngineParameter.LOOP_HASH_MAX_DISTANCE);
        this.loopMinMotion = p.getInt(EngineParameter.LOOP_MIN_MOTION_BITS);
        this.hashes = new long[Math.max(p.getInt(EngineParameter.LOOP_WINDOW_FRAMES), 2 * loopMatch)];
    }

    /**
     * Checks the ordering and the rate of an arriving frame (before it is decoded).
     *
     * @param frame the frame
     * @return {@code TIMESTAMP_REGRESSION}, {@code INVALID_FRAMES} or {@code null}
     */
    ReasonCode onArrival(FaceFrame frame) {
        long nanos = frame.monotonicNanos();
        if (lastSequence >= 0 && (frame.sequence() <= lastSequence || nanos < lastNanos)) {
            return ReasonCode.TIMESTAMP_REGRESSION;
        }
        lastSequence = frame.sequence();
        lastNanos = nanos;
        if (firstNanos < 0) {
            firstNanos = nanos;
        }
        sourceRate.record(nanos, frame.sequence());
        boolean measured = nanos - firstNanos >= fpsWindowNanos && sourceRate.spanNanos() >= fpsWindowNanos / 2;
        return measured && sourceRate.sourceFps() < minFps ? ReasonCode.INVALID_FRAMES : null;
    }

    /**
     * Records a frame that could not be decoded.
     *
     * @return {@code INVALID_FRAMES} when too many failed in a row, otherwise {@code null}
     */
    ReasonCode onCorrupt() {
        return ++corruptRun >= maxCorruptFrames ? ReasonCode.INVALID_FRAMES : null;
    }

    /**
     * Checks a decoded frame for freezing and looping.
     *
     * @param frame the frame
     * @param image its decoded pixels
     * @return {@code FROZEN}, {@code LOOP} or {@code null}
     */
    ReasonCode onDecoded(FaceFrame frame, ImageBuffer image) {
        corruptRun = 0;
        boolean same = false;
        if (frame.format().isEncoded()) {
            byte[] digest = FrameFingerprint.sha256(frame.data());
            same = lastDigest != null && Arrays.equals(digest, lastDigest);
            lastDigest = digest;
        }
        int[] thumb = FrameFingerprint.thumbnail(image, FrameFingerprint.THUMB_SIZE, FrameFingerprint.THUMB_SIZE, 1);
        if (!same && lastThumb != null) {
            same = FrameFingerprint.meanAbsDiff(thumb, lastThumb) < frozenMeanAbsDiff;
        }
        lastThumb = thumb;
        frozenRun = same ? frozenRun + 1 : 0;
        if (frozenRun >= maxFrozenFrames) {
            return ReasonCode.FROZEN;
        }
        addHash(FrameFingerprint.dHash(image));
        return isLooping() ? ReasonCode.LOOP : null;
    }

    /**
     * Returns the frame rate of the source.
     *
     * @return frames per second, 0 until measured
     */
    double sourceFps() {
        return sourceRate.sourceFps();
    }

    private void addHash(long hash) {
        hashes[hashCount % hashes.length] = hash;
        hashCount++;
    }

    /** Hash of the frame {@code back} frames before the newest one. */
    private long hashAt(int back) {
        return hashes[(hashCount - 1 - back) % hashes.length];
    }

    private boolean isLooping() {
        int available = Math.min(hashCount, hashes.length);
        for (int lag = loopMatch; lag + loopMatch <= available; lag++) {
            int matchBits = 0;
            int motionBits = 0;
            boolean match = true;
            for (int i = 0; i < loopMatch && match; i++) {
                int d = Long.bitCount(hashAt(i) ^ hashAt(i + lag));
                match = d <= loopMaxDistance;
                matchBits += d;
                if (i > 0) {
                    motionBits += Long.bitCount(hashAt(i) ^ hashAt(i - 1));
                }
            }
            if (match && motionBits >= loopMinMotion && 2 * matchBits <= motionBits) {
                return true;
            }
        }
        return false;
    }
}
