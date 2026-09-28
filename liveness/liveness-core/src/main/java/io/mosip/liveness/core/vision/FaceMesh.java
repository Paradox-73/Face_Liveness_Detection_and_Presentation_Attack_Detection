package io.mosip.liveness.core.vision;

import java.util.Arrays;
import java.util.Objects;

/**
 * Dense face landmarks, in MediaPipe Face Mesh order (478 points with iris, or 468 without).
 *
 * <p>Each point has x and y in pixels of the analysed image and a relative depth z (same scale as x; smaller is
 * closer to the camera). The coordinates are copied on creation and on {@link #toArray()}; single points are
 * read without copying through {@link #x(int)}, {@link #y(int)} and {@link #z(int)}.
 */
public final class FaceMesh {

    /** Number of points of MediaPipe Face Mesh V2 including the 10 iris points. */
    public static final int POINTS_WITH_IRIS = 478;
    /** Number of points of MediaPipe Face Mesh without the iris points. */
    public static final int POINTS_WITHOUT_IRIS = 468;

    private final float[] xyz;

    private FaceMesh(float[] xyz) {
        this.xyz = xyz;
    }

    /**
     * Creates a mesh from interleaved coordinates {@code x0, y0, z0, x1, y1, z1, ...}.
     *
     * @param xyz coordinates of 468 or 478 points; copied
     * @return the mesh
     * @throws IllegalArgumentException if the array does not hold 468 or 478 points
     */
    public static FaceMesh of(float[] xyz) {
        Objects.requireNonNull(xyz, "xyz");
        int points = xyz.length / 3;
        if (xyz.length % 3 != 0 || (points != POINTS_WITH_IRIS && points != POINTS_WITHOUT_IRIS)) {
            throw new IllegalArgumentException("expected 468 or 478 points of x, y, z; got " + xyz.length + " values");
        }
        return new FaceMesh(xyz.clone());
    }

    /**
     * Returns the number of points.
     *
     * @return 468 or 478
     */
    public int pointCount() {
        return xyz.length / 3;
    }

    /**
     * Tells whether the iris points (indices 468-477) are present; gaze challenges need them.
     *
     * @return {@code true} for a 478-point mesh
     */
    public boolean hasIris() {
        return pointCount() == POINTS_WITH_IRIS;
    }

    /**
     * Returns the x coordinate of one point.
     *
     * @param index point index in MediaPipe order
     * @return pixels from the left edge
     */
    public float x(int index) {
        return xyz[index * 3];
    }

    /**
     * Returns the y coordinate of one point.
     *
     * @param index point index in MediaPipe order
     * @return pixels from the top edge
     */
    public float y(int index) {
        return xyz[index * 3 + 1];
    }

    /**
     * Returns the relative depth of one point.
     *
     * @param index point index in MediaPipe order
     * @return relative depth; smaller is closer to the camera
     */
    public float z(int index) {
        return xyz[index * 3 + 2];
    }

    /**
     * Returns a copy of all coordinates, interleaved x, y, z.
     *
     * @return a new array
     */
    public float[] toArray() {
        return xyz.clone();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof FaceMesh && Arrays.equals(xyz, ((FaceMesh) o).xyz));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(xyz);
    }

    @Override
    public String toString() {
        return "FaceMesh[" + pointCount() + " points]";
    }
}
