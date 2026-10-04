package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.vision.AnalyzerFeature;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-CORE-CH-01..06: challenge drawing. */
class ChallengeSchedulerTest {

    private static final Set<ChallengeType> DEFAULT_POOL = EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE,
            ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT, ChallengeType.LOOK_UP);
    private static final Set<AnalyzerFeature> MESH_FEATURES = EnumSet.allOf(AnalyzerFeature.class);
    private static final Set<ChallengeType> NONE = Collections.emptySet();

    @Test
    void noPublicSelectionApi() {
        assertFalse(Modifier.isPublic(ChallengeScheduler.class.getModifiers()), "scheduler must be package-private");
        for (Class<?> c : Arrays.asList(SessionRequest.class, SessionRequest.Builder.class, LivenessSession.class,
                DefaultLivenessEngine.class, DefaultLivenessEngine.Builder.class)) {
            for (Method m : c.getMethods()) {
                for (Class<?> p : m.getParameterTypes()) {
                    assertNotEquals(ChallengeType.class, p, c.getSimpleName() + "." + m.getName());
                    assertFalse(java.util.Collection.class.isAssignableFrom(p), c.getSimpleName() + "." + m.getName());
                }
            }
        }
    }

    @Test
    void noImmediateRepeats() {
        ChallengeScheduler s = new ChallengeScheduler(new SeededRandom(1));
        List<ChallengeType> pool = ChallengeScheduler.eligible(DEFAULT_POOL, MESH_FEATURES, NONE, 30, 80);
        for (int i = 0; i < 2_000; i++) {
            List<ChallengeType> seq = s.draw(pool, 5, true);
            for (int j = 1; j < seq.size(); j++) {
                assertNotEquals(seq.get(j - 1), seq.get(j));
            }
        }
    }

    @Test
    void blinkNeedsTenAnalysedFramesPerSecond() {
        assertFalse(ChallengeScheduler.eligible(DEFAULT_POOL, MESH_FEATURES, NONE, 9.9, 80).contains(ChallengeType.BLINK));
        assertTrue(ChallengeScheduler.eligible(DEFAULT_POOL, MESH_FEATURES, NONE, 10, 80).contains(ChallengeType.BLINK));
    }

    @Test
    void poolIsFilteredByFeaturesFaceSizeAndEngine() {
        Set<ChallengeType> all = EnumSet.allOf(ChallengeType.class);
        List<ChallengeType> detectorOnly = ChallengeScheduler.eligible(all,
                EnumSet.of(AnalyzerFeature.LANDMARKS_5, AnalyzerFeature.HEAD_POSE), NONE, 30, 80);
        assertEquals(Arrays.asList(ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT, ChallengeType.LOOK_UP,
                ChallengeType.LOOK_DOWN), detectorOnly);
        assertTrue(ChallengeScheduler.eligible(all, MESH_FEATURES, NONE, 30, 80).contains(ChallengeType.GAZE_LEFT));
        assertFalse(ChallengeScheduler.eligible(all, MESH_FEATURES, NONE, 30, 59).contains(ChallengeType.GAZE_LEFT));
        assertFalse(ChallengeScheduler.eligible(all, MESH_FEATURES, NONE, 30, Double.NaN).contains(ChallengeType.GAZE_RIGHT));
        assertEquals(Collections.singletonList(ChallengeType.SMILE),
                ChallengeScheduler.eligible(all, MESH_FEATURES, EnumSet.of(ChallengeType.SMILE), 30, 80));
    }

    /**
     * Chi-squared test over the 14 allowed sequences of the default pool with k = 2
     * (docs/design/challenges.md section 3).
     */
    @Test
    void drawsAreUniformOverTheAllowedSequences() {
        ChallengeScheduler s = new ChallengeScheduler(new SeededRandom(2026));
        List<ChallengeType> pool = ChallengeScheduler.eligible(DEFAULT_POOL, MESH_FEATURES, NONE, 30, 80);
        Map<List<ChallengeType>, Integer> counts = new HashMap<>();
        int draws = 14_000;
        for (int i = 0; i < draws; i++) {
            counts.merge(s.draw(pool, 2, true), 1, Integer::sum);
        }
        assertEquals(14, counts.size(), "20 no-repeat sequences minus the 6 without a head turn");
        double expected = draws / 14.0;
        double chi2 = 0;
        for (int c : counts.values()) {
            chi2 += (c - expected) * (c - expected) / expected;
        }
        // 13 degrees of freedom: the 99.9th percentile of chi-squared is 34.5.
        assertTrue(chi2 < 34.5, "chi2=" + chi2);
    }

    @Test
    void threeChallengesGiveSixtyEightSequences() {
        ChallengeScheduler s = new ChallengeScheduler(new SeededRandom(3));
        List<ChallengeType> pool = ChallengeScheduler.eligible(DEFAULT_POOL, MESH_FEATURES, NONE, 30, 80);
        Set<List<ChallengeType>> seen = new java.util.HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            seen.add(s.draw(pool, 3, true));
        }
        assertEquals(68, seen.size());
    }

    @Test
    void headTurnOnlyWhenRequired() {
        ChallengeScheduler s = new ChallengeScheduler(new SeededRandom(4));
        List<ChallengeType> pool = ChallengeScheduler.eligible(DEFAULT_POOL, MESH_FEATURES, NONE, 30, 80);
        boolean sawWithoutTurn = false;
        for (int i = 0; i < 500; i++) {
            assertTrue(ChallengeScheduler.containsTurn(s.draw(pool, 1, true)));
            sawWithoutTurn |= !ChallengeScheduler.containsTurn(s.draw(pool, 2, false));
        }
        assertTrue(sawWithoutTurn);
        List<ChallengeType> noTurns = Arrays.asList(ChallengeType.BLINK, ChallengeType.SMILE);
        assertFalse(ChallengeScheduler.containsTurn(s.draw(noTurns, 2, true)), "no turn available, rule not applied");
    }

    @Test
    void singleTypePoolRepeatsAndBadInputIsRejected() {
        ChallengeScheduler s = new ChallengeScheduler(new SeededRandom(5));
        assertEquals(Arrays.asList(ChallengeType.TURN_LEFT, ChallengeType.TURN_LEFT),
                s.draw(Collections.singletonList(ChallengeType.TURN_LEFT), 2, true));
        assertThrows(IllegalArgumentException.class, () -> s.draw(Collections.<ChallengeType>emptyList(), 1, false));
        assertThrows(IllegalArgumentException.class, () -> s.draw(Arrays.asList(ChallengeType.SMILE), 0, false));
    }

    @Test
    void fallbackPutsATurnFirstWhenRejectionNeverSucceeds() {
        // A source that always returns 0 never draws the turn at index 1, so the fallback must insert it.
        ChallengeScheduler s = new ChallengeScheduler(new io.mosip.liveness.core.RandomSource() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }

            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) 0);
            }
        });
        List<ChallengeType> seq = s.draw(Arrays.asList(ChallengeType.SMILE, ChallengeType.TURN_LEFT), 1, true);
        assertEquals(Collections.singletonList(ChallengeType.TURN_LEFT), seq);
    }

    @Test
    void featureRequirements() {
        Set<AnalyzerFeature> poseOnly = EnumSet.of(AnalyzerFeature.HEAD_POSE);
        assertFalse(ChallengeScheduler.hasFeatures(ChallengeType.BLINK, poseOnly));
        assertFalse(ChallengeScheduler.hasFeatures(ChallengeType.SMILE, poseOnly));
        assertFalse(ChallengeScheduler.hasFeatures(ChallengeType.GAZE_LEFT, poseOnly));
        assertTrue(ChallengeScheduler.hasFeatures(ChallengeType.LOOK_DOWN, poseOnly));
        assertTrue(ChallengeScheduler.hasFeatures(ChallengeType.BLINK, EnumSet.of(AnalyzerFeature.EYE_OPENNESS)));
        assertTrue(ChallengeScheduler.hasFeatures(ChallengeType.SMILE, EnumSet.of(AnalyzerFeature.MOUTH)));
    }
}
