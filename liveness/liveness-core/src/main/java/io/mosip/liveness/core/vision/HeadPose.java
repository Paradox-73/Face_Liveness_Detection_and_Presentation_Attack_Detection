/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.vision;

/**
 * Head orientation in degrees, in the subject's own frame of reference.
 *
 * <p>Sign convention, independent of any mirroring of the preview:
 * <ul>
 *   <li>yaw is positive when the subject turns the head towards their own left ({@code TURN_LEFT});</li>
 *   <li>pitch is positive when the subject tilts the head up ({@code LOOK_UP});</li>
 *   <li>roll is positive when the head tilts towards the subject's own left shoulder.</li>
 * </ul>
 * A frontal face is (0, 0, 0). Adapters convert from their model's convention to this one.
 */
public final class HeadPose {

    /** Pose of a face looking straight at the camera. */
    public static final HeadPose FRONTAL = new HeadPose(0, 0, 0);

    private final double yawDeg;
    private final double pitchDeg;
    private final double rollDeg;

    private HeadPose(double yawDeg, double pitchDeg, double rollDeg) {
        this.yawDeg = yawDeg;
        this.pitchDeg = pitchDeg;
        this.rollDeg = rollDeg;
    }

    /**
     * Creates a pose.
     *
     * @param yawDeg   yaw in degrees (-180 to 180)
     * @param pitchDeg pitch in degrees (-180 to 180)
     * @param rollDeg  roll in degrees (-180 to 180)
     * @return the pose
     * @throws IllegalArgumentException if an angle is not finite or outside -180 to 180
     */
    public static HeadPose of(double yawDeg, double pitchDeg, double rollDeg) {
        checkAngle(yawDeg);
        checkAngle(pitchDeg);
        checkAngle(rollDeg);
        return new HeadPose(yawDeg, pitchDeg, rollDeg);
    }

    private static void checkAngle(double deg) {
        if (!(deg >= -180 && deg <= 180)) {
            throw new IllegalArgumentException("angle must be between -180 and 180 degrees: " + deg);
        }
    }

    /**
     * Returns the yaw.
     *
     * @return degrees; positive towards the subject's own left
     */
    public double yawDeg() {
        return yawDeg;
    }

    /**
     * Returns the pitch.
     *
     * @return degrees; positive upwards
     */
    public double pitchDeg() {
        return pitchDeg;
    }

    /**
     * Returns the roll.
     *
     * @return degrees; positive towards the subject's own left shoulder
     */
    public double rollDeg() {
        return rollDeg;
    }

    /**
     * Tells whether all three angles are within a limit, for example the frontal-pose limit for the stored
     * image.
     *
     * @param maxAbsDeg largest allowed absolute angle in degrees
     * @return {@code true} if |yaw|, |pitch| and |roll| are all at most the limit
     */
    public boolean isWithin(double maxAbsDeg) {
        return Math.abs(yawDeg) <= maxAbsDeg && Math.abs(pitchDeg) <= maxAbsDeg && Math.abs(rollDeg) <= maxAbsDeg;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HeadPose)) {
            return false;
        }
        HeadPose p = (HeadPose) o;
        return Double.compare(yawDeg, p.yawDeg) == 0 && Double.compare(pitchDeg, p.pitchDeg) == 0
                && Double.compare(rollDeg, p.rollDeg) == 0;
    }

    @Override
    public int hashCode() {
        return (Double.hashCode(yawDeg) * 31 + Double.hashCode(pitchDeg)) * 31 + Double.hashCode(rollDeg);
    }

    @Override
    public String toString() {
        return "HeadPose[yaw=" + yawDeg + ", pitch=" + pitchDeg + ", roll=" + rollDeg + "]";
    }
}
