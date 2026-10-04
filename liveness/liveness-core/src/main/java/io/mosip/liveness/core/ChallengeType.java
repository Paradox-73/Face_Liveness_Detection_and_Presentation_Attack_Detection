/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core;

import java.util.Locale;
import java.util.Optional;

/**
 * An action the person in front of the camera is asked to perform during active liveness.
 *
 * <p>Directions are always the subject's own: {@link #TURN_LEFT} means the person turns the head towards
 * their own left shoulder, whatever the camera or a mirrored preview shows. The engine draws challenges at
 * random from the configured pool; no API lets a user or operator choose one.
 *
 * <p>Eligibility limits from the design (section 4.7) are exposed per constant: {@link #minStreamFps()} and
 * {@link #minInterEyeDistancePx()}. A challenge is removed from the pool for a session whose stream does not
 * meet them.
 */
public enum ChallengeType {

    /** Close both eyes and open them again (blink lasts about 100-400 ms, so it needs at least 10 fps). */
    BLINK(10, 0),

    /** Smile, widening the mouth relative to the neutral face. */
    SMILE(0, 0),

    /** Turn the head towards the subject's own left (yaw beyond the configured angle). */
    TURN_LEFT(0, 0),

    /** Turn the head towards the subject's own right. */
    TURN_RIGHT(0, 0),

    /** Tilt the head up (pitch beyond the configured angle). */
    LOOK_UP(0, 0),

    /** Tilt the head down. */
    LOOK_DOWN(0, 0),

    /** Look to the subject's own left with the eyes only; optional, needs iris landmarks and a large face. */
    GAZE_LEFT(0, 60),

    /** Look to the subject's own right with the eyes only; optional, needs iris landmarks and a large face. */
    GAZE_RIGHT(0, 60);

    private final int minStreamFps;
    private final int minInterEyeDistancePx;

    ChallengeType(int minStreamFps, int minInterEyeDistancePx) {
        this.minStreamFps = minStreamFps;
        this.minInterEyeDistancePx = minInterEyeDistancePx;
    }

    /**
     * Returns the lowest measured stream frame rate at which this challenge can be detected reliably.
     *
     * @return frames per second; 0 when the challenge has no frame-rate limit
     */
    public int minStreamFps() {
        return minStreamFps;
    }

    /**
     * Returns the smallest inter-eye distance (IED) at which this challenge can be detected reliably.
     *
     * @return pixels between the eye centres; 0 when the challenge has no size limit
     */
    public int minInterEyeDistancePx() {
        return minInterEyeDistancePx;
    }

    /**
     * Tells whether this challenge rotates the head left or right; such turns feed the planarity check.
     *
     * @return {@code true} for {@link #TURN_LEFT} and {@link #TURN_RIGHT}
     */
    public boolean isHeadTurn() {
        return this == TURN_LEFT || this == TURN_RIGHT;
    }

    /**
     * Tells whether this challenge moves the whole head (turn or look up/down).
     *
     * @return {@code true} for the four head-pose challenges
     */
    public boolean isHeadMovement() {
        return isHeadTurn() || this == LOOK_UP || this == LOOK_DOWN;
    }

    /**
     * Tells whether this challenge moves only the eyes.
     *
     * @return {@code true} for {@link #GAZE_LEFT} and {@link #GAZE_RIGHT}
     */
    public boolean isGaze() {
        return this == GAZE_LEFT || this == GAZE_RIGHT;
    }

    /**
     * Finds a challenge by its constant name, ignoring case and surrounding spaces.
     *
     * @param name name such as {@code "turn_left"}; may be {@code null}
     * @return the challenge type, or empty if the name is unknown
     */
    public static Optional<ChallengeType> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.trim().toUpperCase(Locale.ROOT);
        for (ChallengeType t : values()) {
            if (t.name().equals(wanted)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
