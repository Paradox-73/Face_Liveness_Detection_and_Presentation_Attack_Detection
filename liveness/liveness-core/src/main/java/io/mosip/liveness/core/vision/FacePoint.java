/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.vision;

/**
 * A point in image coordinates: pixels from the top-left corner of the analysed image, x to the right and
 * y downwards. Sub-pixel values are allowed.
 */
public final class FacePoint {

    private final double x;
    private final double y;

    private FacePoint(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Creates a point.
     *
     * @param x horizontal position in pixels
     * @param y vertical position in pixels
     * @return the point
     * @throws IllegalArgumentException if a coordinate is not finite
     */
    public static FacePoint of(double x, double y) {
        if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)) {
            throw new IllegalArgumentException("coordinates must be finite");
        }
        return new FacePoint(x, y);
    }

    /**
     * Returns the horizontal position.
     *
     * @return pixels from the left edge
     */
    public double x() {
        return x;
    }

    /**
     * Returns the vertical position.
     *
     * @return pixels from the top edge
     */
    public double y() {
        return y;
    }

    /**
     * Returns the straight-line distance to another point.
     *
     * @param other the other point
     * @return distance in pixels
     */
    public double distanceTo(FacePoint other) {
        double dx = x - other.x;
        double dy = y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FacePoint)) {
            return false;
        }
        FacePoint p = (FacePoint) o;
        return Double.compare(x, p.x) == 0 && Double.compare(y, p.y) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Double.hashCode(x) + Double.hashCode(y);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
