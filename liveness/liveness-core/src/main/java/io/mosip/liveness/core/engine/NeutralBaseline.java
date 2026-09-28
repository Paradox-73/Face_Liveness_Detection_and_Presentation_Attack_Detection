package io.mosip.liveness.core.engine;

/**
 * Running mean of the face measures over the neutral hold before a prompt. Actions are measured as changes
 * from this baseline (a head turn is a yaw change, a smile a mouth-width increase, a blink an eye-openness
 * drop), so each person's resting face is the reference.
 */
final class NeutralBaseline {

    private final Mean yaw = new Mean();
    private final Mean pitch = new Mean();
    private final Mean ear = new Mean();
    private final Mean mouth = new Mean();
    private final Mean smile = new Mean();
    private final Mean gaze = new Mean();
    private int frames;

    void add(FaceSignals s) {
        yaw.add(s.yaw);
        pitch.add(s.pitch);
        ear.add(s.ear);
        mouth.add(s.mouthRatio);
        smile.add(s.smileScore);
        gaze.add(s.gazeHorizontal);
        frames++;
    }

    void clear() {
        yaw.clear();
        pitch.clear();
        ear.clear();
        mouth.clear();
        smile.clear();
        gaze.clear();
        frames = 0;
    }

    int frames() {
        return frames;
    }

    double yaw() {
        return yaw.value();
    }

    double pitch() {
        return pitch.value();
    }

    double ear() {
        return ear.value();
    }

    double mouth() {
        return mouth.value();
    }

    double smile() {
        return smile.value();
    }

    double gaze() {
        return gaze.value();
    }

    /**
     * Returns a copy that later {@link #add} calls do not change.
     *
     * @return the copy
     */
    NeutralBaseline copy() {
        NeutralBaseline c = new NeutralBaseline();
        c.yaw.copyFrom(yaw);
        c.pitch.copyFrom(pitch);
        c.ear.copyFrom(ear);
        c.mouth.copyFrom(mouth);
        c.smile.copyFrom(smile);
        c.gaze.copyFrom(gaze);
        c.frames = frames;
        return c;
    }

    /** Mean of the finite values added; {@code NaN} when there are none. */
    private static final class Mean {
        private double sum;
        private int count;

        void add(double v) {
            if (!Double.isNaN(v)) {
                sum += v;
                count++;
            }
        }

        void clear() {
            sum = 0;
            count = 0;
        }

        double value() {
            return count == 0 ? Double.NaN : sum / count;
        }

        void copyFrom(Mean other) {
            sum = other.sum;
            count = other.count;
        }
    }
}
