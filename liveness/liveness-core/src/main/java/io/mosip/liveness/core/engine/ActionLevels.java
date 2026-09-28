package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;

/**
 * Converts face measures into an action level per challenge type: 0 at the neutral face, 1 at the trigger
 * (design section 4.7), {@code NaN} when the measure is missing. Signs follow the subject's own left and right:
 * yaw grows when the subject turns to their own left, pitch when they look up, horizontal gaze towards their own
 * left. A mirrored preview does not change these values, because the analyser reports subject-relative angles.
 */
final class ActionLevels {

    private final double turnDeg;
    private final double lookDeg;
    private final double blinkDrop;
    private final double smileIncrease;
    private final double smileScore;
    private final double gazeThreshold;

    ActionLevels(EngineParameters p) {
        this.turnDeg = p.get(EngineParameter.TURN_YAW_DEG);
        this.lookDeg = p.get(EngineParameter.LOOK_PITCH_DEG);
        this.blinkDrop = 1 - p.get(EngineParameter.BLINK_CLOSE_RATIO);
        this.smileIncrease = p.get(EngineParameter.SMILE_RATIO_INCREASE);
        this.smileScore = p.get(EngineParameter.SMILE_SCORE_THRESHOLD);
        this.gazeThreshold = p.get(EngineParameter.GAZE_THRESHOLD);
    }

    /**
     * Returns the action level of one challenge type in one frame.
     *
     * @param type     challenge type
     * @param s        measures of the frame
     * @param baseline neutral measures before the prompt
     * @return the level (1 = trigger), or {@code NaN} if it cannot be measured
     */
    double level(ChallengeType type, FaceSignals s, NeutralBaseline baseline) {
        switch (type) {
            case TURN_LEFT:
                return delta(s.yaw, baseline.yaw()) / turnDeg;
            case TURN_RIGHT:
                return -delta(s.yaw, baseline.yaw()) / turnDeg;
            case LOOK_UP:
                return delta(s.pitch, baseline.pitch()) / lookDeg;
            case LOOK_DOWN:
                return -delta(s.pitch, baseline.pitch()) / lookDeg;
            case BLINK:
                return blink(s.ear, baseline.ear());
            case SMILE:
                return smile(s, baseline);
            case GAZE_LEFT:
                return delta(s.gazeHorizontal, zeroIfNaN(baseline.gaze())) / gazeThreshold;
            case GAZE_RIGHT:
                return -delta(s.gazeHorizontal, zeroIfNaN(baseline.gaze())) / gazeThreshold;
            default:
                return Double.NaN;
        }
    }

    private double blink(double ear, double neutralEar) {
        if (Double.isNaN(ear) || !(neutralEar > 0)) {
            return Double.NaN;
        }
        return (neutralEar - ear) / (neutralEar * blinkDrop);
    }

    private double smile(FaceSignals s, NeutralBaseline baseline) {
        if (!Double.isNaN(s.mouthRatio) && baseline.mouth() > 0) {
            return (s.mouthRatio / baseline.mouth() - 1) / smileIncrease;
        }
        if (!Double.isNaN(s.smileScore)) {
            return s.smileScore / smileScore;
        }
        return Double.NaN;
    }

    private static double delta(double value, double neutral) {
        return Double.isNaN(value) || Double.isNaN(neutral) ? Double.NaN : value - neutral;
    }

    private static double zeroIfNaN(double v) {
        return Double.isNaN(v) ? 0 : v;
    }
}
