/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.ui;

import io.mosip.liveness.core.ChallengeType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiContractTest {

    @Test
    void messageCatalogueHasFortyOneUniqueKeys() {
        List<String> all = MessageKeys.all();
        assertEquals(41, all.size());
        assertEquals(41, new HashSet<>(all).size());
        for (String key : all) {
            assertTrue(key.matches("liveness\\.[a-z]+\\.[a-z_]+"), key);
        }
    }

    @Test
    void everyChallengeHasAPromptKeyAndIcon() {
        for (ChallengeType type : ChallengeType.values()) {
            assertTrue(MessageKeys.all().contains(MessageKeys.challengeKey(type)), type.name());
            assertEquals(type.name(), PromptIcon.forChallenge(type).name());
        }
        assertEquals("liveness.challenge.turn_left", MessageKeys.challengeKey(ChallengeType.TURN_LEFT));
    }

    @Test
    void uiStateBuilderValidatesCounters() {
        LivenessUiState s = LivenessUiState.builder(UiPhase.FAILED, MessageKeys.RETRY_AVAILABLE).args("1", "3")
                .guidance(MessageKeys.GUIDE_LIGHTING).attempt(1, 3).retryAvailable(true).severity(Severity.WARNING)
                .promptIcon(PromptIcon.WARNING).build();
        assertEquals(List.of("1", "3"), s.args());
        assertEquals(List.of(MessageKeys.GUIDE_LIGHTING), s.guidanceKeys());
        assertTrue(s.isRetryAvailable());
        assertFalse(s.isOverrideAvailable());
        assertEquals(s, LivenessUiState.builder(UiPhase.FAILED, MessageKeys.RETRY_AVAILABLE).args("1", "3")
                .guidance(MessageKeys.GUIDE_LIGHTING).attempt(1, 3).retryAvailable(true).severity(Severity.WARNING)
                .promptIcon(PromptIcon.WARNING).build());
        assertThrows(IllegalArgumentException.class,
                () -> LivenessUiState.builder(UiPhase.CHECKING, MessageKeys.STATUS_CHECKING).progress(1.5).build());
        assertThrows(IllegalArgumentException.class,
                () -> LivenessUiState.builder(UiPhase.CHALLENGE, MessageKeys.CHALLENGE_BLINK).step(3, 2).build());
        assertThrows(IllegalArgumentException.class,
                () -> LivenessUiState.builder(UiPhase.FAILED, MessageKeys.FAIL_PAD).attempt(4, 3).build());
        assertThrows(IllegalArgumentException.class, () -> LivenessUiState.builder(UiPhase.FAILED, ""));
    }

    @Test
    void terminalPhasesStartAtSuccess() {
        assertFalse(UiPhase.VERIFYING.isTerminal());
        assertTrue(UiPhase.SUCCESS.isTerminal());
        assertTrue(UiPhase.CANCELLED.isTerminal());
    }
}
