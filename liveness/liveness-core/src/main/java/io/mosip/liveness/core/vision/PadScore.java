package io.mosip.liveness.core.vision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Passive presentation attack detection result for one face in one image.
 *
 * <p>The engine takes the median of {@link #score100()} over {@code passive.min_frames} good frames and compares
 * it with {@code pad.reject_threshold} and the workflow's {@code passive.threshold}. Scores are never shown to
 * the user and never written to audits; they go only to packet evidence and the diagnostic log.
 */
public final class PadScore {

    private final double liveProbability;
    private final Map<String, Double> modelProbabilities;

    private PadScore(double liveProbability, Map<String, Double> modelProbabilities) {
        this.liveProbability = liveProbability;
        this.modelProbabilities = modelProbabilities;
    }

    /**
     * Creates a score without per-model detail.
     *
     * @param liveProbability probability that the face is live, 0-1
     * @return the score
     */
    public static PadScore of(double liveProbability) {
        return of(liveProbability, Collections.<String, Double>emptyMap());
    }

    /**
     * Creates a score with the probability of each model of an ensemble.
     *
     * @param liveProbability    combined probability that the face is live, 0-1
     * @param modelProbabilities live probability per model identifier, each 0-1; copied, order kept
     * @return the score
     * @throws IllegalArgumentException if a probability is outside 0-1
     */
    public static PadScore of(double liveProbability, Map<String, Double> modelProbabilities) {
        checkProbability(liveProbability);
        Objects.requireNonNull(modelProbabilities, "modelProbabilities");
        Map<String, Double> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : modelProbabilities.entrySet()) {
            Objects.requireNonNull(e.getKey(), "model id");
            Objects.requireNonNull(e.getValue(), "probability");
            checkProbability(e.getValue());
            copy.put(e.getKey(), e.getValue());
        }
        return new PadScore(liveProbability, Collections.unmodifiableMap(copy));
    }

    private static void checkProbability(double p) {
        if (!(p >= 0 && p <= 1)) {
            throw new IllegalArgumentException("probability must be 0-1: " + p);
        }
    }

    /**
     * Returns the probability that the face is live.
     *
     * @return value 0-1
     */
    public double liveProbability() {
        return liveProbability;
    }

    /**
     * Returns the live probability as an integer score, {@code round(100 * p)}.
     *
     * @return score 0-100
     */
    public int score100() {
        return (int) Math.round(liveProbability * 100);
    }

    /**
     * Returns the live probability of each model of an ensemble.
     *
     * @return an unmodifiable map from model identifier to probability 0-1; empty if not reported
     */
    public Map<String, Double> modelProbabilities() {
        return modelProbabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PadScore)) {
            return false;
        }
        PadScore s = (PadScore) o;
        return Double.compare(liveProbability, s.liveProbability) == 0 && modelProbabilities.equals(s.modelProbabilities);
    }

    @Override
    public int hashCode() {
        return 31 * Double.hashCode(liveProbability) + modelProbabilities.hashCode();
    }

    @Override
    public String toString() {
        return "PadScore[" + liveProbability + ", models=" + modelProbabilities + "]";
    }
}
