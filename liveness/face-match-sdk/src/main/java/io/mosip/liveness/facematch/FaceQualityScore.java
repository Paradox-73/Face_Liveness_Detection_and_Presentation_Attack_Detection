/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.FaceQuality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Quality score 0-100 of a face image for {@code checkQuality}, from the face detector's output.
 *
 * <p>{@code score = round(100 * confidence * size * exposure * single)}, where
 * <ul>
 *   <li>{@code confidence} is the detector confidence of the subject's face (0-1);</li>
 *   <li>{@code size = min(1, interEyeDistance / 60 px)}: ISO/IEC 19794-5 token images need at least 60 px between
 *       the eye centres;</li>
 *   <li>{@code exposure} is 1 for a mean face brightness of 50-225 grey levels, falling linearly to 0.5 at 0 or
 *       255;</li>
 *   <li>{@code single} is 1 for exactly one face and 0.5 when other faces are visible.</li>
 * </ul>
 * No face gives 0. The flags ({@code NO_FACE}, {@code MULTIPLE_FACES}, {@code FACE_TOO_SMALL}, {@code TOO_DARK},
 * {@code TOO_BRIGHT}) go into the MOSIP {@code QualityScore} errors.
 */
public final class FaceQualityScore {

    /** Inter-eye distance in pixels that gives the full size factor. */
    public static final double FULL_SIZE_IED_PX = 60;
    /** Lowest mean brightness without penalty. */
    public static final double MIN_BRIGHTNESS = 50;
    /** Highest mean brightness without penalty. */
    public static final double MAX_BRIGHTNESS = 225;

    private final int score;
    private final int faces;
    private final double interEyeDistance;
    private final double brightness;
    private final List<String> flags;

    private FaceQualityScore(int score, int faces, double interEyeDistance, double brightness, List<String> flags) {
        this.score = score;
        this.faces = faces;
        this.interEyeDistance = interEyeDistance;
        this.brightness = brightness;
        this.flags = Collections.unmodifiableList(flags);
    }

    /**
     * Scores an analysed image.
     *
     * @param observation detector output for the image
     * @return the quality
     */
    public static FaceQualityScore of(FaceObservation observation) {
        List<String> flags = new ArrayList<>();
        Optional<DetectedFace> primary = observation.primary();
        if (!primary.isPresent()) {
            flags.add("NO_FACE");
            return new FaceQualityScore(0, 0, 0, 0, flags);
        }
        DetectedFace face = primary.get();
        double confidence = clamp(face.box().confidence(), 0, 1);
        double ied = face.quality().map(FaceQuality::interEyeDistancePx).orElse(0.0);
        double brightness = face.quality().map(FaceQuality::brightness).orElse(128.0);
        double size = Math.min(1, ied / FULL_SIZE_IED_PX);
        if (size < 1) {
            flags.add("FACE_TOO_SMALL");
        }
        double exposure = exposure(brightness, flags);
        double single = 1;
        if (observation.faceCount() > 1) {
            single = 0.5;
            flags.add("MULTIPLE_FACES");
        }
        int score = (int) Math.round(100 * confidence * size * exposure * single);
        return new FaceQualityScore(score, observation.faceCount(), ied, brightness, flags);
    }

    private static double exposure(double brightness, List<String> flags) {
        if (brightness < MIN_BRIGHTNESS) {
            flags.add("TOO_DARK");
            return 0.5 + 0.5 * brightness / MIN_BRIGHTNESS;
        }
        if (brightness > MAX_BRIGHTNESS) {
            flags.add("TOO_BRIGHT");
            return 0.5 + 0.5 * (255 - brightness) / (255 - MAX_BRIGHTNESS);
        }
        return 1;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Returns the score.
     *
     * @return 0-100
     */
    public int score() {
        return score;
    }

    /**
     * Returns the number of faces found.
     *
     * @return face count
     */
    public int faces() {
        return faces;
    }

    /**
     * Returns the subject's inter-eye distance.
     *
     * @return pixels, 0 without a face
     */
    public double interEyeDistance() {
        return interEyeDistance;
    }

    /**
     * Returns the subject's mean face brightness.
     *
     * @return grey levels 0-255, 0 without a face
     */
    public double brightness() {
        return brightness;
    }

    /**
     * Returns the quality problems found.
     *
     * @return an unmodifiable list, empty for a good image
     */
    public List<String> flags() {
        return flags;
    }

    @Override
    public String toString() {
        return "FaceQualityScore[" + score + ", faces=" + faces + ", ied=" + Math.round(interEyeDistance)
                + ", brightness=" + Math.round(brightness) + ", flags=" + flags + "]";
    }
}
