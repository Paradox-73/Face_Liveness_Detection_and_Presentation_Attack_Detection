/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.interop;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParityCheckTest {

    private static Map<String, Object> result(int passive, Integer... pad) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decision", "FAIL");
        m.put("failureReason", "PAD_ATTACK");
        m.put("reasonCodes", Arrays.asList("PAD_LOW_SCORE"));
        m.put("method", "PASSIVE");
        m.put("engineError", null);
        m.put("passiveScore", passive);
        m.put("activePadScore", null);
        m.put("bindingSimilarity", null);
        m.put("challenges", Collections.emptyList());
        m.put("framesStreamed", 30);
        m.put("padScores", Arrays.asList(pad));
        return m;
    }

    @Test
    void scoresWithinTheToleranceMatch() {
        ParityCheck check = ParityCheck.compare(result(40, 10, 20), result(42, 11, 18), 2);
        assertTrue(check.matches(), check.mismatches().toString());
        assertEquals(2, check.maxScoreDifference());
    }

    @Test
    void scoreBeyondTheToleranceIsReported() {
        ParityCheck check = ParityCheck.compare(result(40, 10, 20), result(40, 10, 25), 2);
        assertFalse(check.matches());
        assertTrue(check.mismatches().get(0).startsWith("padScores: frame 1"), check.mismatches().toString());
        assertEquals(5, check.maxScoreDifference());
    }

    @Test
    void decisionAndReasonsMustBeIdentical() {
        Map<String, Object> other = result(40, 10);
        other.put("decision", "PASS");
        other.put("reasonCodes", Collections.emptyList());
        other.put("framesStreamed", 30L);
        ParityCheck check = ParityCheck.compare(result(40, 10), other, 100);
        assertEquals(2, check.mismatches().size(), check.mismatches().toString());
        Map<String, Object> missing = result(40, 10);
        missing.put("passiveScore", null);
        assertFalse(ParityCheck.compare(result(40, 10), missing, 100).matches());
        assertFalse(ParityCheck.compare(result(40, 10), result(40, 10, 11), 100).matches());
    }

    @Test
    void caseIsReadFromItsJsonObject() {
        Map<String, Object> json = new HashMap<>();
        json.put("id", "pack-s2");
        json.put("source", "packs");
        json.put("workflow", "login_auth");
        json.put("seed", 7);
        json.put("main", "s2-main");
        json.put("atEnd", "loop");
        json.put("config", Collections.singletonMap("active.challenge_types", "TURN_LEFT"));
        json.put("actions", Collections.singletonMap("TURN_LEFT", "s2-turn-left"));
        ParityCase c = ParityCase.fromJson(json);
        assertEquals(Workflow.LOGIN_AUTH, c.workflow());
        assertFalse(c.isCommitted());
        assertEquals(7L, c.seed());
        assertEquals(15, c.fps());
        assertEquals(400L, c.reactionMs());
        assertEquals(ParityCase.AtEnd.LOOP, c.atEnd());
        assertEquals(ParityCase.OnPrompt.MATCH, c.onPrompt());
        assertEquals("TURN_LEFT", c.config().get(LivenessConfigKeys.PREFIX + "active.challenge_types"));
        assertEquals("s2-turn-left", c.actions().get(ChallengeType.TURN_LEFT));
        assertNull(c.expected());
        json.put("source", "elsewhere");
        assertThrows(IllegalArgumentException.class, () -> ParityCase.fromJson(json));
    }
}
