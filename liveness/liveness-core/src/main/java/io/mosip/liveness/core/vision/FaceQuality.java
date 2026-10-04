/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.vision;

/**
 * Image quality measures of one face, used by the acquisition gate and to choose guidance messages.
 */
public final class FaceQuality {

    private final double interEyeDistancePx;
    private final double sharpness;
    private final double brightness;

    private FaceQuality(double interEyeDistancePx, double sharpness, double brightness) {
        this.interEyeDistancePx = interEyeDistancePx;
        this.sharpness = sharpness;
        this.brightness = brightness;
    }

    /**
     * Creates quality measures.
     *
     * @param interEyeDistancePx distance between the eye centres in pixels (IED); 0 or more
     * @param sharpness          variance of the Laplacian of the grey face crop; 0 or more, higher is sharper
     * @param brightness         mean grey level of the face crop, 0 (black) to 255 (white)
     * @return the measures
     * @throws IllegalArgumentException if a value is out of range or not finite
     */
    public static FaceQuality of(double interEyeDistancePx, double sharpness, double brightness) {
        if (!(interEyeDistancePx >= 0) || Double.isInfinite(interEyeDistancePx)) {
            throw new IllegalArgumentException("interEyeDistancePx must be finite and not negative");
        }
        if (!(sharpness >= 0) || Double.isInfinite(sharpness)) {
            throw new IllegalArgumentException("sharpness must be finite and not negative");
        }
        if (!(brightness >= 0 && brightness <= 255)) {
            throw new IllegalArgumentException("brightness must be 0-255");
        }
        return new FaceQuality(interEyeDistancePx, sharpness, brightness);
    }

    /**
     * Returns the inter-eye distance (IED); compared with {@code quality.min_ied_px}.
     *
     * @return pixels
     */
    public double interEyeDistancePx() {
        return interEyeDistancePx;
    }

    /**
     * Returns the sharpness: variance of the Laplacian of the grey face crop.
     *
     * @return grey-level variance; higher is sharper
     */
    public double sharpness() {
        return sharpness;
    }

    /**
     * Returns the brightness: mean grey level of the face crop.
     *
     * @return 0 (black) to 255 (white)
     */
    public double brightness() {
        return brightness;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FaceQuality)) {
            return false;
        }
        FaceQuality q = (FaceQuality) o;
        return Double.compare(interEyeDistancePx, q.interEyeDistancePx) == 0 && Double.compare(sharpness, q.sharpness) == 0
                && Double.compare(brightness, q.brightness) == 0;
    }

    @Override
    public int hashCode() {
        return (Double.hashCode(interEyeDistancePx) * 31 + Double.hashCode(sharpness)) * 31 + Double.hashCode(brightness);
    }

    @Override
    public String toString() {
        return "FaceQuality[ied=" + interEyeDistancePx + ", sharpness=" + sharpness + ", brightness=" + brightness + "]";
    }
}
