package io.mosip.liveness.core.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of analysing one image: every face found, the subject's face first.
 *
 * <p>The analyser orders faces so that the one most likely to be the subject (largest, closest to the centre)
 * comes first. The session requires exactly one face; {@link #faceCount()} drives the "no face" and "more than
 * one face" guidance.
 */
public final class FaceObservation {

    private final int imageWidth;
    private final int imageHeight;
    private final List<DetectedFace> faces;

    private FaceObservation(int imageWidth, int imageHeight, List<DetectedFace> faces) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.faces = faces;
    }

    /**
     * Creates an observation.
     *
     * @param imageWidth  width of the analysed image in pixels; positive
     * @param imageHeight height of the analysed image in pixels; positive
     * @param faces       detected faces, subject first; copied
     * @return the observation
     * @throws IllegalArgumentException if the image size is not positive
     */
    public static FaceObservation of(int imageWidth, int imageHeight, List<DetectedFace> faces) {
        Objects.requireNonNull(faces, "faces");
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("image size must be positive");
        }
        List<DetectedFace> copy = new ArrayList<>(faces);
        for (DetectedFace f : copy) {
            Objects.requireNonNull(f, "face");
        }
        return new FaceObservation(imageWidth, imageHeight, Collections.unmodifiableList(copy));
    }

    /**
     * Creates an observation with no face.
     *
     * @param imageWidth  width of the analysed image in pixels; positive
     * @param imageHeight height of the analysed image in pixels; positive
     * @return the empty observation
     */
    public static FaceObservation none(int imageWidth, int imageHeight) {
        return of(imageWidth, imageHeight, Collections.<DetectedFace>emptyList());
    }

    /**
     * Returns the analysed image width.
     *
     * @return pixels
     */
    public int imageWidth() {
        return imageWidth;
    }

    /**
     * Returns the analysed image height.
     *
     * @return pixels
     */
    public int imageHeight() {
        return imageHeight;
    }

    /**
     * Returns every detected face, subject first.
     *
     * @return an unmodifiable list, possibly empty
     */
    public List<DetectedFace> faces() {
        return faces;
    }

    /**
     * Returns the number of faces found.
     *
     * @return face count
     */
    public int faceCount() {
        return faces.size();
    }

    /**
     * Returns the subject's face.
     *
     * @return the first face, or empty if none was found
     */
    public Optional<DetectedFace> primary() {
        return faces.isEmpty() ? Optional.<DetectedFace>empty() : Optional.of(faces.get(0));
    }

    @Override
    public String toString() {
        return "FaceObservation[" + imageWidth + "x" + imageHeight + ", faces=" + faces.size() + "]";
    }
}
