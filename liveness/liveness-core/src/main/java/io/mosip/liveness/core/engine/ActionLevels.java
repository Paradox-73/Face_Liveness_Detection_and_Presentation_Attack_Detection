/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;

/**
 * Converts face measures into an action level per challenge type: 0 at the neutral face, 1 at the trigger
 * (docs/design/challenges.md section 4), {@code NaN} when the measure is missing. Signs follow the subject's own left
 * and right: yaw grows when the subject turns to their own left, pitch when they look up, horizontal gaze towards their
 * own left. A mirrored preview does not change these values, because the analyser reports subject-relative angles.
 */
final class ActionLevels {

    private final double turnDeg;
    private final double lookDeg;
    private final double blinkDrop;
    private final double smileIncrease;
    private final double smileScore;
    private final double gazeThreshold;
    private final double smileMaxYawDeg;

    ActionLevels(EngineParameters p) {
        this.turnDeg = p.get(EngineParameter.TURN_YAW_DEG);
        this.lookDeg = p.get(EngineParameter.LOOK_PITCH_DEG);
        this.blinkDrop = 1 - p.get(EngineParameter.BLINK_CLOSE_RATIO);
        this.smileIncrease = p.get(EngineParameter.SMILE_RATIO_INCREASE);
        this.smileScore = p.get(EngineParameter.SMILE_SCORE_THRESHOLD);
        this.gazeThreshold = p.get(EngineParameter.GAZE_THRESHOLD);
        this.smileMaxYawDeg = p.get(EngineParameter.SMILE_MAX_YAW_DEG);
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

    /**
     * Smile level from the mouth width ratio, or from the analyser's smile score. With the head turned more than
     * {@code active.smile_max_yaw_deg} from the neutral face the ratio grows without a smile, so the smile is
     * unmeasured there and cannot fail a head turn as a wrong action.
     */
    private double smile(FaceSignals s, NeutralBaseline baseline) {
        if (Math.abs(delta(s.yaw, baseline.yaw())) > smileMaxYawDeg) {
            return Double.NaN;
        }
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
