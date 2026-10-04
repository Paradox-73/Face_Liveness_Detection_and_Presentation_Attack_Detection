/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.match;

import java.util.Objects;

/**
 * Result of a one-to-one face comparison. The score stays on the device: it goes to the diagnostic log only,
 * never to the screen or the audit trail.
 */
public final class FaceMatch {

    /** How a comparison ended. */
    public enum Status {
        /** The best similarity reached the threshold. */
        MATCHED,
        /** Faces were compared and the best similarity is below the threshold. */
        NOT_MATCHED,
        /** The user has no usable enrolled face. */
        NO_GALLERY,
        /** The probe has no usable face. */
        NO_PROBE_FACE,
        /** The models are not available (still loading, failed integrity check or failed to run). */
        UNAVAILABLE
    }

    private final Status status;
    private final int score;
    private final int threshold;

    private FaceMatch(Status status, int score, int threshold) {
        this.status = Objects.requireNonNull(status, "status");
        this.score = score;
        this.threshold = threshold;
    }

    /**
     * Creates the result of a comparison that ran.
     *
     * @param score     best similarity, 0-100
     * @param threshold threshold, 0-100
     * @return {@code MATCHED} when {@code score >= threshold}, else {@code NOT_MATCHED}
     */
    public static FaceMatch compared(int score, int threshold) {
        return new FaceMatch(score >= threshold ? Status.MATCHED : Status.NOT_MATCHED, score, threshold);
    }

    /**
     * Creates the result of a comparison that could not run.
     *
     * @param status    why; not {@code MATCHED} or {@code NOT_MATCHED}
     * @param threshold threshold, 0-100
     * @return the result, with score 0
     */
    public static FaceMatch notCompared(Status status, int threshold) {
        if (status == Status.MATCHED || status == Status.NOT_MATCHED) {
            throw new IllegalArgumentException("a comparison that ran needs a score");
        }
        return new FaceMatch(status, 0, threshold);
    }

    /**
     * Returns how the comparison ended.
     *
     * @return the status
     */
    public Status status() {
        return status;
    }

    /**
     * Tells whether the faces match.
     *
     * @return {@code true} for {@link Status#MATCHED}
     */
    public boolean isMatch() {
        return status == Status.MATCHED;
    }

    /**
     * Returns the best similarity.
     *
     * @return 0-100; 0 when no comparison ran
     */
    public int score() {
        return score;
    }

    /**
     * Returns the threshold that was applied.
     *
     * @return 0-100
     */
    public int threshold() {
        return threshold;
    }

    @Override
    public String toString() {
        return "FaceMatch[" + status + ", score=" + score + ", threshold=" + threshold + "]";
    }
}
