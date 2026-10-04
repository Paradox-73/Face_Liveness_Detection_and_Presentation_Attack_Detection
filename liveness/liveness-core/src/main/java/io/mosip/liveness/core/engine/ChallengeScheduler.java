package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.RandomSource;
import io.mosip.liveness.core.vision.AnalyzerFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Draws the challenge sequence of a session (docs/design/challenges.md section 3).
 *
 * <p>Package-private on purpose: no API lets a client, an operator or the person choose a challenge
 * (T-CORE-CH-01). Rules:
 * <ul>
 *   <li>the pool is the policy's {@code challenge_types}, minus types the stream or analyser cannot support
 *       (BLINK below 10 analysed fps, GAZE below 60 px inter-eye distance, missing analyser features, types the
 *       engine does not list);</li>
 *   <li>{@code count} challenges are drawn with the session's {@link RandomSource} (a {@code SecureRandom} in
 *       production) with no immediate repeats;</li>
 *   <li>when a head turn is required (planarity on and at least 5 analysed fps) the sequence contains at least
 *       one TURN_LEFT or TURN_RIGHT.</li>
 * </ul>
 * Sequences are drawn uniformly from the no-repeat sequences and rejected until they meet the head-turn rule,
 * so every allowed sequence has the same probability (checked by a chi-squared test).
 */
final class ChallengeScheduler {

    private static final int MAX_DRAWS = 10_000;

    private final RandomSource random;

    ChallengeScheduler(RandomSource random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Filters the configured pool down to the challenges this stream and analyser can support.
     *
     * @param pool             configured challenge types
     * @param features         analyser features
     * @param engineSupported  types the engine lists as supported; empty means no restriction
     * @param analysisFps      measured rate of analysed frames
     * @param interEyeDistance inter-eye distance in pixels, or {@code NaN} if unknown
     * @return eligible types in {@link ChallengeType} order
     */
    static List<ChallengeType> eligible(Set<ChallengeType> pool, Set<AnalyzerFeature> features,
            Set<ChallengeType> engineSupported, double analysisFps, double interEyeDistance) {
        List<ChallengeType> out = new ArrayList<>();
        for (ChallengeType t : ChallengeType.values()) {
            if (!pool.contains(t) || (!engineSupported.isEmpty() && !engineSupported.contains(t))
                    || !hasFeatures(t, features) || analysisFps < t.minStreamFps()) {
                continue;
            }
            if (t.minInterEyeDistancePx() > 0 && !(interEyeDistance >= t.minInterEyeDistancePx())) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * Tells whether the analyser measures what a challenge needs.
     *
     * @param type     challenge type
     * @param features analyser features
     * @return {@code true} if the challenge can be detected
     */
    static boolean hasFeatures(ChallengeType type, Set<AnalyzerFeature> features) {
        switch (type) {
            case BLINK:
                return features.contains(AnalyzerFeature.EYE_OPENNESS);
            case SMILE:
                return features.contains(AnalyzerFeature.MOUTH);
            case GAZE_LEFT:
            case GAZE_RIGHT:
                return features.contains(AnalyzerFeature.GAZE) && features.contains(AnalyzerFeature.HEAD_POSE);
            default:
                return features.contains(AnalyzerFeature.HEAD_POSE);
        }
    }

    /**
     * Draws a challenge sequence.
     *
     * @param eligible        eligible types (from {@link #eligible}); not empty
     * @param count           number of challenges; at least 1
     * @param requireHeadTurn whether the sequence must contain a head turn when the pool has one
     * @return the sequence
     * @throws IllegalArgumentException if the pool is empty or the count is below 1
     */
    List<ChallengeType> draw(List<ChallengeType> eligible, int count, boolean requireHeadTurn) {
        if (eligible.isEmpty() || count < 1) {
            throw new IllegalArgumentException("need a non-empty pool and a positive count");
        }
        if (eligible.size() == 1) {
            // A single configured type cannot avoid repeats; the policy asked for it explicitly.
            return Collections.nCopies(count, eligible.get(0));
        }
        boolean needTurn = requireHeadTurn && containsTurn(eligible);
        for (int i = 0; i < MAX_DRAWS; i++) {
            List<ChallengeType> seq = drawWithoutRepeats(eligible, count);
            if (!needTurn || containsTurn(seq)) {
                return seq;
            }
        }
        return forceTurn(drawWithoutRepeats(eligible, count), eligible);
    }

    private List<ChallengeType> drawWithoutRepeats(List<ChallengeType> eligible, int count) {
        int n = eligible.size();
        List<ChallengeType> seq = new ArrayList<>(count);
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int pick;
            if (previous < 0) {
                pick = random.nextInt(n);
            } else {
                pick = random.nextInt(n - 1);
                if (pick >= previous) {
                    pick++;
                }
            }
            seq.add(eligible.get(pick));
            previous = pick;
        }
        return seq;
    }

    /** Fallback that is practically never reached: puts a head turn at the first position. */
    private List<ChallengeType> forceTurn(List<ChallengeType> seq, List<ChallengeType> eligible) {
        List<ChallengeType> turns = new ArrayList<>();
        for (ChallengeType t : eligible) {
            if (t.isHeadTurn() && (seq.size() < 2 || t != seq.get(1))) {
                turns.add(t);
            }
        }
        if (!turns.isEmpty()) {
            seq.set(0, turns.get(random.nextInt(turns.size())));
        }
        return seq;
    }

    static boolean containsTurn(List<ChallengeType> types) {
        for (ChallengeType t : types) {
            if (t.isHeadTurn()) {
                return true;
            }
        }
        return false;
    }
}
