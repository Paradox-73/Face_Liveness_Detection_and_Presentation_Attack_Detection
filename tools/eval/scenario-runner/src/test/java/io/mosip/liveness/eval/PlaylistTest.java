/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.ChallengeType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaylistTest {

    private static final long FRAME_NS = 1_000_000_000L / 10;

    private static final Map<String, FrameSequence> PACKS = new HashMap<>();

    static {
        PACKS.put("main", ScriptedEngine.pack("main", 3));
        PACKS.put("blink", ScriptedEngine.pack("blink", 2));
        PACKS.put("smile", ScriptedEngine.pack("smile", 2));
        PACKS.put("fixed", ScriptedEngine.pack("fixed", 1));
    }

    private static Playlist playlist(Scenario.AtEnd atEnd, Scenario.PromptMode mode, long reactionMs) {
        Map<ChallengeType, String> actions = new EnumMap<>(ChallengeType.class);
        if (mode != Scenario.PromptMode.IGNORE) {
            actions.put(ChallengeType.BLINK, "blink");
            actions.put(ChallengeType.SMILE, "smile");
        }
        Scenario.StreamPlan plan = new Scenario.StreamPlan("main", atEnd, mode, reactionMs, actions,
                mode == Scenario.PromptMode.FIXED ? "fixed" : null);
        return new Playlist(plan, PACKS::get);
    }

    /** Plays n frames from frame number {@code from}, 10 fps, and returns "pack#index" for each. */
    private static List<String> play(Playlist p, int from, int n) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < from + n; i++) {
            Playlist.Frame f = p.next(i * FRAME_NS);
            out.add(f == null ? "END" : f.toString());
        }
        return out;
    }

    private static List<String> list(String... items) {
        List<String> out = new ArrayList<>();
        Collections.addAll(out, items);
        return out;
    }

    @Test
    void mainPackEndings() {
        assertEquals(list("main#0", "main#1", "main#2", "main#0", "main#1"),
                play(playlist(Scenario.AtEnd.LOOP, Scenario.PromptMode.IGNORE, 0), 0, 5));
        assertEquals(list("main#0", "main#1", "main#2", "main#1", "main#0", "main#1"),
                play(playlist(Scenario.AtEnd.PINGPONG, Scenario.PromptMode.IGNORE, 0), 0, 6));
        assertEquals(list("main#0", "main#1", "main#2", "main#2", "main#2"),
                play(playlist(Scenario.AtEnd.HOLD, Scenario.PromptMode.IGNORE, 0), 0, 5));
        assertEquals(list("main#0", "main#1", "main#2", "END", "END"),
                play(playlist(Scenario.AtEnd.END, Scenario.PromptMode.IGNORE, 0), 0, 5));
    }

    @Test
    void matchSwitchesAfterTheReactionTimeAndResumesMain() {
        Playlist p = playlist(Scenario.AtEnd.LOOP, Scenario.PromptMode.MATCH, 200);
        assertEquals(list("main#0"), play(p, 0, 1));
        assertEquals("smile", p.onPrompt(ChallengeType.SMILE, 1 * FRAME_NS));
        // prompt at frame 1, reaction 200 ms = 2 frames: the action clip starts at frame 3
        assertEquals(list("main#1", "main#2", "smile#0", "smile#1", "main#0"), play(p, 1, 5));
        assertEquals(1, p.prompts().size());
        assertEquals(100L, p.prompts().get(0).atMs);
    }

    @Test
    void mismatchPlaysAnotherActionAndFixedAlwaysTheSamePack() {
        Playlist mismatch = playlist(Scenario.AtEnd.LOOP, Scenario.PromptMode.MISMATCH, 0);
        assertEquals("smile", mismatch.onPrompt(ChallengeType.BLINK, 0));
        assertEquals("blink", mismatch.onPrompt(ChallengeType.SMILE, 0));
        Playlist fixed = playlist(Scenario.AtEnd.LOOP, Scenario.PromptMode.FIXED, 0);
        assertEquals("fixed", fixed.onPrompt(ChallengeType.BLINK, 0));
        assertEquals(list("fixed#0", "main#0"), play(fixed, 0, 2));
    }

    @Test
    void ignoreAndUnknownActionsKeepTheMainPack() {
        Playlist ignore = playlist(Scenario.AtEnd.LOOP, Scenario.PromptMode.IGNORE, 0);
        assertNull(ignore.onPrompt(ChallengeType.BLINK, 0));
        assertEquals(list("main#0", "main#1"), play(ignore, 0, 2));
        Playlist match = playlist(Scenario.AtEnd.LOOP, Scenario.PromptMode.MATCH, 0);
        assertNull(match.onPrompt(ChallengeType.TURN_LEFT, 0));
        assertEquals(1, match.prompts().size());
    }

    @Test
    void aNewPromptReplacesAPendingSwitch() {
        Playlist p = playlist(Scenario.AtEnd.LOOP, Scenario.PromptMode.MATCH, 300);
        p.onPrompt(ChallengeType.BLINK, 0);
        p.onPrompt(ChallengeType.SMILE, FRAME_NS);
        assertEquals(list("main#0", "main#1", "main#2", "main#0", "smile#0"), play(p, 0, 5));
    }
}
