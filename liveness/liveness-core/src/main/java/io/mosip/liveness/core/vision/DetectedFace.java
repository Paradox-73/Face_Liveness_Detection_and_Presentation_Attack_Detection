package io.mosip.liveness.core.vision;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Everything a {@link FaceAnalyzer} measured about one face in one image.
 *
 * <p>Only the box is always present. The other measures depend on the analyser's {@link AnalyzerFeature}s: a
 * detector-only analyser (YuNet with five landmarks) gives box and head pose, a mesh analyser adds eye, mouth
 * and gaze measures. Left and right are the subject's own.
 */
public final class DetectedFace {

    private final FaceBox box;
    private final FaceMesh mesh;
    private final HeadPose pose;
    private final Double leftEyeAspectRatio;
    private final Double rightEyeAspectRatio;
    private final Double mouthWidthRatio;
    private final Double smileScore;
    private final Double gazeHorizontal;
    private final Double gazeVertical;
    private final FaceQuality quality;

    private DetectedFace(Builder b) {
        this.box = b.box;
        this.mesh = b.mesh;
        this.pose = b.pose;
        this.leftEyeAspectRatio = b.leftEyeAspectRatio;
        this.rightEyeAspectRatio = b.rightEyeAspectRatio;
        this.mouthWidthRatio = b.mouthWidthRatio;
        this.smileScore = b.smileScore;
        this.gazeHorizontal = b.gazeHorizontal;
        this.gazeVertical = b.gazeVertical;
        this.quality = b.quality;
    }

    /**
     * Starts a builder for a face with the given box.
     *
     * @param box bounding box, with five landmarks if the detector gives them
     * @return a builder
     */
    public static Builder builder(FaceBox box) {
        return new Builder(box);
    }

    /**
     * Returns the bounding box and five landmarks.
     *
     * @return the box
     */
    public FaceBox box() {
        return box;
    }

    /**
     * Returns the dense landmarks.
     *
     * @return the mesh, if the analyser computes one
     */
    public Optional<FaceMesh> mesh() {
        return Optional.ofNullable(mesh);
    }

    /**
     * Returns the head orientation.
     *
     * @return the pose, if the analyser estimates one
     */
    public Optional<HeadPose> pose() {
        return Optional.ofNullable(pose);
    }

    /**
     * Returns the eye aspect ratio (EAR) of the subject's left eye: eyelid opening divided by eye width, from six
     * eye-contour points. Typical values are about 0.25-0.35 open and below 0.2 closed.
     *
     * @return the ratio, if measured
     */
    public OptionalDouble leftEyeAspectRatio() {
        return optional(leftEyeAspectRatio);
    }

    /**
     * Returns the eye aspect ratio (EAR) of the subject's right eye; see {@link #leftEyeAspectRatio()}.
     *
     * @return the ratio, if measured
     */
    public OptionalDouble rightEyeAspectRatio() {
        return optional(rightEyeAspectRatio);
    }

    /**
     * Returns the distance between the mouth corners divided by the inter-eye distance. A smile raises it above
     * the person's neutral value.
     *
     * @return the ratio, if measured
     */
    public OptionalDouble mouthWidthRatio() {
        return optional(mouthWidthRatio);
    }

    /**
     * Returns a smile probability from a classifier (for example a blendshape model), if the analyser has one.
     *
     * @return value 0 (neutral) to 1 (smiling), if measured
     */
    public OptionalDouble smileScore() {
        return optional(smileScore);
    }

    /**
     * Returns the horizontal gaze: iris offset from the eye centre, relative to half the eye width.
     *
     * @return value -1 to 1, positive towards the subject's own left, if measured
     */
    public OptionalDouble gazeHorizontal() {
        return optional(gazeHorizontal);
    }

    /**
     * Returns the vertical gaze: iris offset from the eye centre, relative to half the eye height.
     *
     * @return value -1 to 1, positive upwards, if measured
     */
    public OptionalDouble gazeVertical() {
        return optional(gazeVertical);
    }

    /**
     * Returns the image quality measures of this face.
     *
     * @return the quality, if measured
     */
    public Optional<FaceQuality> quality() {
        return Optional.ofNullable(quality);
    }

    private static OptionalDouble optional(Double value) {
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    @Override
    public String toString() {
        return "DetectedFace[" + box + ", pose=" + pose + ", mesh=" + (mesh != null) + ", earL=" + leftEyeAspectRatio
                + ", earR=" + rightEyeAspectRatio + ", mouth=" + mouthWidthRatio + ", quality=" + quality + "]";
    }

    /** Builder for {@link DetectedFace}; every field except the box is optional. */
    public static final class Builder {

        private final FaceBox box;
        private FaceMesh mesh;
        private HeadPose pose;
        private Double leftEyeAspectRatio;
        private Double rightEyeAspectRatio;
        private Double mouthWidthRatio;
        private Double smileScore;
        private Double gazeHorizontal;
        private Double gazeVertical;
        private FaceQuality quality;

        private Builder(FaceBox box) {
            this.box = Objects.requireNonNull(box, "box");
        }

        /**
         * Sets the dense landmarks.
         *
         * @param mesh the mesh, or {@code null}
         * @return this builder
         */
        public Builder mesh(FaceMesh mesh) {
            this.mesh = mesh;
            return this;
        }

        /**
         * Sets the head pose.
         *
         * @param pose the pose, or {@code null}
         * @return this builder
         */
        public Builder pose(HeadPose pose) {
            this.pose = pose;
            return this;
        }

        /**
         * Sets both eye aspect ratios.
         *
         * @param left  EAR of the subject's left eye; 0 or more
         * @param right EAR of the subject's right eye; 0 or more
         * @return this builder
         */
        public Builder eyeAspectRatios(double left, double right) {
            this.leftEyeAspectRatio = nonNegative(left, "left EAR");
            this.rightEyeAspectRatio = nonNegative(right, "right EAR");
            return this;
        }

        /**
         * Sets the mouth width ratio.
         *
         * @param ratio mouth-corner distance divided by the inter-eye distance; 0 or more
         * @return this builder
         */
        public Builder mouthWidthRatio(double ratio) {
            this.mouthWidthRatio = nonNegative(ratio, "mouth ratio");
            return this;
        }

        /**
         * Sets the smile probability.
         *
         * @param score value 0-1
         * @return this builder
         */
        public Builder smileScore(double score) {
            this.smileScore = inRange(score, 0, 1, "smile score");
            return this;
        }

        /**
         * Sets the gaze direction.
         *
         * @param horizontal value -1 to 1, positive towards the subject's own left
         * @param vertical   value -1 to 1, positive upwards
         * @return this builder
         */
        public Builder gaze(double horizontal, double vertical) {
            this.gazeHorizontal = inRange(horizontal, -1, 1, "horizontal gaze");
            this.gazeVertical = inRange(vertical, -1, 1, "vertical gaze");
            return this;
        }

        /**
         * Sets the quality measures.
         *
         * @param quality the measures, or {@code null}
         * @return this builder
         */
        public Builder quality(FaceQuality quality) {
            this.quality = quality;
            return this;
        }

        /**
         * Creates the detected face.
         *
         * @return the detected face
         */
        public DetectedFace build() {
            return new DetectedFace(this);
        }

        private static Double nonNegative(double v, String what) {
            if (!(v >= 0) || Double.isInfinite(v)) {
                throw new IllegalArgumentException(what + " must be finite and not negative");
            }
            return v;
        }

        private static Double inRange(double v, double min, double max, String what) {
            if (!(v >= min && v <= max)) {
                throw new IllegalArgumentException(what + " must be between " + min + " and " + max);
            }
            return v;
        }
    }
}
