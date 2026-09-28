package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FacePoint;
import io.mosip.liveness.core.vision.HeadPose;
import io.mosip.liveness.core.vision.Landmark5;

import java.util.Optional;

/**
 * The per-frame measures of one face that the challenge detectors use, with {@code NaN} for a measure the
 * analyser did not provide. Left, right, yaw and pitch follow the subject-relative conventions of
 * {@link HeadPose}.
 */
final class FaceSignals {

    final double yaw;
    final double pitch;
    final double roll;
    final double ear;
    final double mouthRatio;
    final double smileScore;
    final double gazeHorizontal;
    final double interEyeDistance;

    private FaceSignals(double yaw, double pitch, double roll, double ear, double mouthRatio, double smileScore,
            double gazeHorizontal, double interEyeDistance) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.ear = ear;
        this.mouthRatio = mouthRatio;
        this.smileScore = smileScore;
        this.gazeHorizontal = gazeHorizontal;
        this.interEyeDistance = interEyeDistance;
    }

    /**
     * Extracts the measures of a face.
     *
     * @param face the detected face
     * @return the measures
     */
    static FaceSignals of(DetectedFace face) {
        Optional<HeadPose> pose = face.pose();
        double yaw = pose.isPresent() ? pose.get().yawDeg() : Double.NaN;
        double pitch = pose.isPresent() ? pose.get().pitchDeg() : Double.NaN;
        double roll = pose.isPresent() ? pose.get().rollDeg() : Double.NaN;
        double ear = Double.NaN;
        if (face.leftEyeAspectRatio().isPresent() && face.rightEyeAspectRatio().isPresent()) {
            ear = (face.leftEyeAspectRatio().getAsDouble() + face.rightEyeAspectRatio().getAsDouble()) / 2;
        }
        double mouth = face.mouthWidthRatio().isPresent() ? face.mouthWidthRatio().getAsDouble() : Double.NaN;
        double smile = face.smileScore().isPresent() ? face.smileScore().getAsDouble() : Double.NaN;
        double gaze = face.gazeHorizontal().isPresent() ? face.gazeHorizontal().getAsDouble() : Double.NaN;
        return new FaceSignals(yaw, pitch, roll, ear, mouth, smile, gaze, interEyeDistance(face));
    }

    /**
     * Returns the inter-eye distance: the analyser's quality measure, else the distance between the two eye
     * landmarks.
     *
     * @param face the face
     * @return pixels, or {@code NaN} if unknown
     */
    static double interEyeDistance(DetectedFace face) {
        if (face.quality().isPresent() && face.quality().get().interEyeDistancePx() > 0) {
            return face.quality().get().interEyeDistancePx();
        }
        Optional<FacePoint> right = face.box().landmark(Landmark5.RIGHT_EYE);
        Optional<FacePoint> left = face.box().landmark(Landmark5.LEFT_EYE);
        if (right.isPresent() && left.isPresent()) {
            return right.get().distanceTo(left.get());
        }
        return Double.NaN;
    }

    boolean hasPose() {
        return !Double.isNaN(yaw) && !Double.isNaN(pitch);
    }

    /**
     * Returns the largest absolute pose angle.
     *
     * @return degrees, or {@code NaN} without a pose
     */
    double maxPoseAngle() {
        if (!hasPose()) {
            return Double.NaN;
        }
        double r = Double.isNaN(roll) ? 0 : Math.abs(roll);
        return Math.max(Math.abs(yaw), Math.max(Math.abs(pitch), r));
    }
}
