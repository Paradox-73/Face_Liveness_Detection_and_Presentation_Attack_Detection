package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.FaceQuality;

/**
 * Acquisition quality gate (docs/design/engine.md section 3, state {@code ACQUIRING}): exactly one face, inside the
 * image, large enough, frontal enough, lit and sharp. Each failed check has a reason code, which the UI mapper turns
 * into a guidance message. A measure the analyser does not provide is not checked.
 */
final class QualityGate {

    private final int minIedPx;
    private final double maxYawDeg;
    private final double maxPitchDeg;
    private final double maxRollDeg;
    private final double minConfidence;
    private final double minBrightness;
    private final double maxBrightness;
    private final double minSharpness;
    private final double outOfAreaTolerance;

    QualityGate(LivenessPolicy policy, EngineParameters params) {
        this.minIedPx = policy.qualityMinIedPx();
        this.maxYawDeg = policy.qualityMaxYawDeg();
        this.maxPitchDeg = params.get(EngineParameter.MAX_PITCH_DEG);
        this.maxRollDeg = params.get(EngineParameter.MAX_ROLL_DEG);
        this.minConfidence = params.get(EngineParameter.MIN_FACE_CONFIDENCE);
        this.minBrightness = params.get(EngineParameter.MIN_BRIGHTNESS);
        this.maxBrightness = params.get(EngineParameter.MAX_BRIGHTNESS);
        this.minSharpness = params.get(EngineParameter.MIN_SHARPNESS);
        this.outOfAreaTolerance = params.get(EngineParameter.OUT_OF_AREA_TOLERANCE);
    }

    /**
     * Checks one analysed frame.
     *
     * @param observation the analyser output
     * @return {@code null} if the frame is good, otherwise the first failed check
     */
    ReasonCode evaluate(FaceObservation observation) {
        if (observation.faceCount() == 0) {
            return ReasonCode.NO_FACE;
        }
        if (observation.faceCount() > 1) {
            return ReasonCode.MULTI_FACE;
        }
        DetectedFace face = observation.faces().get(0);
        if (face.box().confidence() < minConfidence) {
            return ReasonCode.POOR_QUALITY;
        }
        if (isOutOfArea(face.box(), observation.imageWidth(), observation.imageHeight())) {
            return ReasonCode.FACE_OUT_OF_AREA;
        }
        FaceSignals s = FaceSignals.of(face);
        if (!Double.isNaN(s.interEyeDistance) && s.interEyeDistance < minIedPx) {
            return ReasonCode.FACE_TOO_SMALL;
        }
        if (isOffPose(s)) {
            return ReasonCode.OFF_POSE;
        }
        return checkImage(face);
    }

    private boolean isOffPose(FaceSignals s) {
        return (!Double.isNaN(s.yaw) && Math.abs(s.yaw) > maxYawDeg)
                || (!Double.isNaN(s.pitch) && Math.abs(s.pitch) > maxPitchDeg)
                || (!Double.isNaN(s.roll) && Math.abs(s.roll) > maxRollDeg);
    }

    private ReasonCode checkImage(DetectedFace face) {
        if (!face.quality().isPresent()) {
            return null;
        }
        FaceQuality q = face.quality().get();
        if (q.brightness() < minBrightness) {
            return ReasonCode.TOO_DARK;
        }
        if (q.brightness() > maxBrightness) {
            return ReasonCode.TOO_BRIGHT;
        }
        if (q.sharpness() < minSharpness) {
            return ReasonCode.BLURRY;
        }
        return null;
    }

    private boolean isOutOfArea(FaceBox box, int imageWidth, int imageHeight) {
        double mx = box.width() * outOfAreaTolerance;
        double my = box.height() * outOfAreaTolerance;
        return box.x() < -mx || box.y() < -my || box.x() + box.width() > imageWidth + mx
                || box.y() + box.height() > imageHeight + my;
    }
}
