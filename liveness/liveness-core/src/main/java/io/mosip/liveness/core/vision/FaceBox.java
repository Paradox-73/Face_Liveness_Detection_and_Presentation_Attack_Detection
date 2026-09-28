package io.mosip.liveness.core.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A detected face: its bounding box, the detector confidence, and optionally the five landmarks.
 *
 * <p>Coordinates are pixels in the analysed {@link io.mosip.liveness.core.image.ImageBuffer}, from the top-left
 * corner. The box may extend beyond the image edge. Passive PAD crops around the box; face embedding aligns on
 * the five landmarks, so a box passed to {@link FaceEmbedder} must have them.
 */
public final class FaceBox {

    private final double x;
    private final double y;
    private final double width;
    private final double height;
    private final double confidence;
    private final List<FacePoint> landmarks;

    private FaceBox(double x, double y, double width, double height, double confidence, List<FacePoint> landmarks) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.confidence = confidence;
        this.landmarks = landmarks;
    }

    /**
     * Creates a box without landmarks.
     *
     * @param x          left edge in pixels
     * @param y          top edge in pixels
     * @param width      width in pixels; positive
     * @param height     height in pixels; positive
     * @param confidence detector confidence 0-1
     * @return the box
     * @throws IllegalArgumentException if a value is not finite, the size is not positive, or the confidence is
     *                                  outside 0-1
     */
    public static FaceBox of(double x, double y, double width, double height, double confidence) {
        checkFinite(x, y, width, height);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        if (!(confidence >= 0 && confidence <= 1)) {
            throw new IllegalArgumentException("confidence must be 0-1");
        }
        return new FaceBox(x, y, width, height, confidence, Collections.<FacePoint>emptyList());
    }

    private static void checkFinite(double... values) {
        for (double v : values) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("box values must be finite");
            }
        }
    }

    /**
     * Returns a copy of this box with the five landmarks attached.
     *
     * @param landmarks exactly five points in {@link Landmark5} order
     * @return the new box
     * @throws IllegalArgumentException if the list does not hold exactly five points
     */
    public FaceBox withLandmarks(List<FacePoint> landmarks) {
        Objects.requireNonNull(landmarks, "landmarks");
        if (landmarks.size() != Landmark5.values().length) {
            throw new IllegalArgumentException("exactly 5 landmarks expected, got " + landmarks.size());
        }
        List<FacePoint> copy = new ArrayList<>(landmarks);
        for (FacePoint p : copy) {
            Objects.requireNonNull(p, "landmark");
        }
        return new FaceBox(x, y, width, height, confidence, Collections.unmodifiableList(copy));
    }

    /**
     * Returns the left edge.
     *
     * @return pixels
     */
    public double x() {
        return x;
    }

    /**
     * Returns the top edge.
     *
     * @return pixels
     */
    public double y() {
        return y;
    }

    /**
     * Returns the width.
     *
     * @return pixels
     */
    public double width() {
        return width;
    }

    /**
     * Returns the height.
     *
     * @return pixels
     */
    public double height() {
        return height;
    }

    /**
     * Returns the detector confidence.
     *
     * @return value 0-1
     */
    public double confidence() {
        return confidence;
    }

    /**
     * Returns the horizontal centre.
     *
     * @return pixels
     */
    public double centerX() {
        return x + width / 2;
    }

    /**
     * Returns the vertical centre.
     *
     * @return pixels
     */
    public double centerY() {
        return y + height / 2;
    }

    /**
     * Returns the area.
     *
     * @return square pixels
     */
    public double area() {
        return width * height;
    }

    /**
     * Tells whether the five landmarks are present.
     *
     * @return {@code true} if {@link #landmarks()} has five points
     */
    public boolean hasLandmarks() {
        return !landmarks.isEmpty();
    }

    /**
     * Returns the five landmarks in {@link Landmark5} order.
     *
     * @return an unmodifiable list of five points, or an empty list
     */
    public List<FacePoint> landmarks() {
        return landmarks;
    }

    /**
     * Returns one landmark.
     *
     * @param which the landmark
     * @return the point, or empty if this box has no landmarks
     */
    public Optional<FacePoint> landmark(Landmark5 which) {
        return landmarks.isEmpty() ? Optional.<FacePoint>empty() : Optional.of(landmarks.get(which.ordinal()));
    }

    /**
     * Returns the intersection-over-union overlap with another box, used to track one face across frames.
     *
     * @param other the other box
     * @return overlap 0 (disjoint) to 1 (identical)
     */
    public double iou(FaceBox other) {
        double ix = Math.max(0, Math.min(x + width, other.x + other.width) - Math.max(x, other.x));
        double iy = Math.max(0, Math.min(y + height, other.y + other.height) - Math.max(y, other.y));
        double intersection = ix * iy;
        double union = area() + other.area() - intersection;
        return union <= 0 ? 0 : intersection / union;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FaceBox)) {
            return false;
        }
        FaceBox b = (FaceBox) o;
        return Double.compare(x, b.x) == 0 && Double.compare(y, b.y) == 0 && Double.compare(width, b.width) == 0
                && Double.compare(height, b.height) == 0 && Double.compare(confidence, b.confidence) == 0
                && landmarks.equals(b.landmarks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, width, height, confidence, landmarks);
    }

    @Override
    public String toString() {
        return "FaceBox[x=" + x + ", y=" + y + ", w=" + width + ", h=" + height + ", conf=" + confidence
                + ", landmarks=" + landmarks.size() + "]";
    }
}
