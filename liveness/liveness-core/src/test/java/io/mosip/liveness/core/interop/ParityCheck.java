/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.interop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares a replay result with the recorded one. Decision, failure reason, reason codes, method, engine error,
 * challenges and the number of frames streamed must be identical; the scores (0-100) may differ by the tolerance
 * the parity file states, because the clients decode JPEG with different libraries and may run ONNX Runtime with a
 * different thread count.
 */
public final class ParityCheck {

    /** Fields that must be identical. */
    public static final List<String> EXACT = Collections.unmodifiableList(Arrays.asList("decision", "failureReason",
            "reasonCodes", "method", "engineError", "challenges", "framesStreamed"));
    /** Single scores compared within the tolerance. */
    public static final List<String> SCORES = Collections.unmodifiableList(Arrays.asList("passiveScore",
            "activePadScore", "bindingSimilarity"));
    /** Per-frame PAD scores, compared element by element within the tolerance. */
    public static final String PAD_SCORES = "padScores";

    private final List<String> mismatches;
    private final int maxScoreDifference;

    private ParityCheck(List<String> mismatches, int maxScoreDifference) {
        this.mismatches = Collections.unmodifiableList(mismatches);
        this.maxScoreDifference = maxScoreDifference;
    }

    /**
     * Compares one result with the recorded one.
     *
     * @param expected  the recorded result ({@link ParityCase#expected()})
     * @param actual    the replay result ({@link ParityReplay#run})
     * @param tolerance largest allowed score difference, in points
     * @return the comparison
     */
    public static ParityCheck compare(Map<String, Object> expected, Map<String, Object> actual, int tolerance) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        List<String> out = new ArrayList<>();
        int max = 0;
        for (String key : EXACT) {
            Object e = normalise(expected.get(key));
            Object a = normalise(actual.get(key));
            if (!Objects.equals(e, a)) {
                out.add(key + ": expected " + e + ", got " + a);
            }
        }
        for (String key : SCORES) {
            Object e = expected.get(key);
            Object a = actual.get(key);
            if (e == null || a == null) {
                if (e != a) {
                    out.add(key + ": expected " + e + ", got " + a);
                }
                continue;
            }
            int d = Math.abs(((Number) e).intValue() - ((Number) a).intValue());
            max = Math.max(max, d);
            if (d > tolerance) {
                out.add(key + ": expected " + e + ", got " + a + " (tolerance " + tolerance + ")");
            }
        }
        List<?> ep = list(expected.get(PAD_SCORES));
        List<?> ap = list(actual.get(PAD_SCORES));
        if (ep.size() != ap.size()) {
            out.add(PAD_SCORES + ": expected " + ep.size() + " frames, got " + ap.size());
        }
        int worstFrame = -1;
        int worst = 0;
        for (int i = 0; i < Math.min(ep.size(), ap.size()); i++) {
            int d = Math.abs(((Number) ep.get(i)).intValue() - ((Number) ap.get(i)).intValue());
            if (d > worst) {
                worst = d;
                worstFrame = i;
            }
        }
        max = Math.max(max, worst);
        if (worst > tolerance) {
            out.add(PAD_SCORES + ": frame " + worstFrame + " expected " + ep.get(worstFrame) + ", got "
                    + ap.get(worstFrame) + " (tolerance " + tolerance + ")");
        }
        return new ParityCheck(out, max);
    }

    private static Object normalise(Object v) {
        return v instanceof Number ? (Object) ((Number) v).longValue() : v;
    }

    private static List<?> list(Object v) {
        return v instanceof List ? (List<?>) v : Collections.emptyList();
    }

    /**
     * Tells whether the result matches.
     *
     * @return {@code true} if no field differs beyond its tolerance
     */
    public boolean matches() {
        return mismatches.isEmpty();
    }

    /**
     * Returns the differences.
     *
     * @return one line per differing field; empty when the result matches
     */
    public List<String> mismatches() {
        return mismatches;
    }

    /**
     * Returns the largest score difference seen, whether or not within the tolerance.
     *
     * @return points
     */
    public int maxScoreDifference() {
        return maxScoreDifference;
    }
}
