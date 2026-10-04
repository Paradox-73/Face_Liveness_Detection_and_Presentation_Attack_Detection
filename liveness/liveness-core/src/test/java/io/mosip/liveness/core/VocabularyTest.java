/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VocabularyTest {

    @Test
    void workflowIdsMatchTheDesign() {
        Set<String> ids = new HashSet<>();
        for (Workflow w : Workflow.values()) {
            ids.add(w.id());
            assertEquals(w, Workflow.fromId(" " + w.id().toUpperCase(java.util.Locale.ROOT) + " ").orElse(null));
        }
        assertEquals(new HashSet<>(java.util.Arrays.asList("registration", "onboard_auth", "login_auth", "packet_auth",
                "exception_auth", "eod_auth", "exception_photo")), ids);
        assertFalse(Workflow.fromId("unknown").isPresent());
        assertFalse(Workflow.fromId(null).isPresent());
    }

    @Test
    void fiveWorkflowsAreAuthentication() {
        Set<Workflow> auth = EnumSet.noneOf(Workflow.class);
        for (Workflow w : Workflow.values()) {
            if (w.isAuth()) {
                auth.add(w);
            }
            assertTrue(w.isAuth() != w.isResident());
        }
        assertEquals(EnumSet.of(Workflow.ONBOARD_AUTH, Workflow.LOGIN_AUTH, Workflow.PACKET_AUTH, Workflow.EXCEPTION_AUTH,
                Workflow.EOD_AUTH), auth);
    }

    @Test
    void retryCountingFollowsTheDesign() {
        Set<FailureReason> counted = EnumSet.noneOf(FailureReason.class);
        Set<FailureReason> noOverride = EnumSet.noneOf(FailureReason.class);
        for (FailureReason r : FailureReason.values()) {
            if (r.countsAsAttempt()) {
                counted.add(r);
            }
            if (!r.allowsOverride()) {
                noOverride.add(r);
            }
        }
        assertEquals(EnumSet.of(FailureReason.PAD, FailureReason.INTEGRITY, FailureReason.ACTIVE, FailureReason.TIMEOUT,
                FailureReason.BELOW_THRESHOLD, FailureReason.BINDING), counted);
        assertEquals(EnumSet.of(FailureReason.PAD, FailureReason.INTEGRITY, FailureReason.BINDING), noOverride);
    }

    @Test
    void suspectedAttacksShareOneDecision() {
        assertEquals(Decision.FAIL_PAD, FailureReason.PAD.decision());
        assertEquals(Decision.FAIL_PAD, FailureReason.INTEGRITY.decision());
        assertEquals(Decision.FAIL_PAD, FailureReason.BINDING.decision());
        assertEquals(Decision.FAIL_LIVENESS, FailureReason.BELOW_THRESHOLD.decision());
        assertEquals(Decision.ERROR, FailureReason.DEVICE.decision());
        assertEquals(Decision.CANCELLED, FailureReason.CANCELLED.decision());
        for (FailureReason r : FailureReason.values()) {
            assertFalse(r.decision().isPass());
        }
    }

    @Test
    void terminalStatesAreTheLastThree() {
        Set<SessionState> terminal = EnumSet.noneOf(SessionState.class);
        for (SessionState s : SessionState.values()) {
            if (s.isTerminal()) {
                terminal.add(s);
            }
        }
        assertEquals(EnumSet.of(SessionState.PASSED, SessionState.FAILED, SessionState.CANCELLED), terminal);
        assertTrue(SessionState.CAPTURE_READY.isObservingStream());
        assertFalse(SessionState.CAPTURING.isObservingStream());
        assertTrue(SessionState.CHALLENGE_OBSERVE.isChallengePhase());
    }

    @Test
    void challengeEligibilityLimits() {
        assertEquals(10, ChallengeType.BLINK.minStreamFps());
        assertEquals(60, ChallengeType.GAZE_LEFT.minInterEyeDistancePx());
        assertEquals(0, ChallengeType.TURN_LEFT.minStreamFps());
        assertTrue(ChallengeType.TURN_RIGHT.isHeadTurn());
        assertFalse(ChallengeType.LOOK_UP.isHeadTurn());
        assertTrue(ChallengeType.LOOK_DOWN.isHeadMovement());
        assertTrue(ChallengeType.GAZE_RIGHT.isGaze());
        assertEquals(ChallengeType.TURN_LEFT, ChallengeType.fromName(" turn_left ").orElse(null));
        assertFalse(ChallengeType.fromName("WINK").isPresent());
    }

    @Test
    void modelIntegrityErrorsAreFlagged() {
        Set<EngineErrorCode> integrity = EnumSet.noneOf(EngineErrorCode.class);
        for (EngineErrorCode c : EngineErrorCode.values()) {
            if (c.isModelIntegrityFailure()) {
                integrity.add(c);
            }
        }
        assertEquals(EnumSet.of(EngineErrorCode.MODEL_MISSING, EngineErrorCode.MODEL_INTEGRITY,
                EngineErrorCode.MODEL_VERSION), integrity);
        assertEquals(EngineErrorCode.INFERENCE, new EngineException(EngineErrorCode.INFERENCE, "x").getErrorCode());
    }

    @Test
    void systemClockAndSecureRandomWork() {
        Clock clock = Clock.system();
        long a = clock.monotonicNanos();
        assertTrue(clock.monotonicNanos() >= a);
        RandomSource random = RandomSource.secure();
        for (int i = 0; i < 100; i++) {
            int v = random.nextInt(3);
            assertTrue(v >= 0 && v < 3);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        boolean nonZero = false;
        for (byte b : bytes) {
            nonZero |= b != 0;
        }
        assertTrue(nonZero);
    }
}
