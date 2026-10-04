/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.ChallengeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Chooses the next frame of a replayed stream: the main pack, and after a challenge prompt (plus a reaction delay)
 * an action pack chosen by the scenario's {@link Scenario.PromptMode}.
 *
 * <p>This is how one recorded person answers randomly drawn challenges without a camera: in {@code MATCH} mode the
 * stream switches to the clip of the prompted action (bona fide, or an adaptive replay attacker who holds a clip of
 * every action); in {@code MISMATCH} and {@code FIXED} modes it plays a clip that does not answer the prompt (a
 * replay attacker without the right clip). The main pack pauses while an action pack plays and resumes afterwards.
 * Not thread-safe.
 */
final class Playlist {

    private final Scenario.StreamPlan plan;
    private final Function<String, FrameSequence> packs;
    private final FrameSequence main;
    private final List<PromptRecord> prompts = new ArrayList<>();

    private int mainPos;
    private int mainDir = 1;
    private boolean ended;
    private FrameSequence overlay;
    private int overlayPos;
    private FrameSequence pending;
    private long switchAtNanos;

    /**
     * Creates a playlist positioned at the first frame of the main pack.
     *
     * @param plan  the scenario's stream plan
     * @param packs resolves a pack id to its frames
     */
    Playlist(Scenario.StreamPlan plan, Function<String, FrameSequence> packs) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.main = packs.apply(plan.main());
    }

    /**
     * Returns the next frame to stream.
     *
     * @param nowNanos stream time of the frame
     * @return the frame, or {@code null} when the stream has ended ({@link Scenario.AtEnd#END})
     */
    Frame next(long nowNanos) {
        if (pending != null && nowNanos >= switchAtNanos) {
            overlay = pending;
            overlayPos = 0;
            pending = null;
        }
        if (overlay != null) {
            if (overlayPos < overlay.size()) {
                int i = overlayPos++;
                return new Frame(overlay.id(), i, overlay.frame(i));
            }
            overlay = null;
        }
        return nextMain();
    }

    private Frame nextMain() {
        if (ended) {
            return null;
        }
        int i = mainPos;
        Frame f = new Frame(main.id(), i, main.frame(i));
        advanceMain();
        return f;
    }

    private void advanceMain() {
        int size = main.size();
        mainPos += mainDir;
        if (mainPos >= 0 && mainPos < size) {
            return;
        }
        switch (plan.atEnd()) {
            case LOOP:
                mainPos = 0;
                break;
            case PINGPONG:
                if (size == 1) {
                    mainPos = 0;
                } else if (mainPos >= size) {
                    mainDir = -1;
                    mainPos = size - 2;
                } else {
                    mainDir = 1;
                    mainPos = 1;
                }
                break;
            case HOLD:
                mainPos = size - 1;
                mainDir = 0;
                break;
            case END:
            default:
                ended = true;
                break;
        }
    }

    /**
     * Reacts to a challenge prompt: after the reaction delay the stream switches to the pack the prompt mode picks.
     *
     * @param type     the prompted challenge
     * @param nowNanos stream time of the prompt
     * @return the pack that will be played, or {@code null} if the stream does not react
     */
    String onPrompt(ChallengeType type, long nowNanos) {
        Objects.requireNonNull(type, "type");
        String packId = choosePack(type);
        prompts.add(new PromptRecord(type, packId, nowNanos / 1_000_000L));
        if (packId != null) {
            pending = packs.apply(packId);
            switchAtNanos = nowNanos + plan.reactionMs() * 1_000_000L;
        }
        return packId;
    }

    private String choosePack(ChallengeType type) {
        Map<ChallengeType, String> actions = plan.actions();
        switch (plan.onPrompt()) {
            case MATCH:
                return actions.get(type);
            case MISMATCH:
                for (Map.Entry<ChallengeType, String> e : actions.entrySet()) {
                    if (e.getKey() != type) {
                        return e.getValue();
                    }
                }
                return null;
            case FIXED:
                return plan.fixedPack();
            case IGNORE:
            default:
                return null;
        }
    }

    /**
     * Returns the prompts seen so far and the pack chosen for each.
     *
     * @return the prompts in order
     */
    List<PromptRecord> prompts() {
        return Collections.unmodifiableList(prompts);
    }

    /** One streamed frame. */
    static final class Frame {
        final String packId;
        final int index;
        final byte[] bytes;

        Frame(String packId, int index, byte[] bytes) {
            this.packId = packId;
            this.index = index;
            this.bytes = bytes;
        }

        @Override
        public String toString() {
            return packId + "#" + index;
        }
    }

    /** A prompt and the stream's reaction to it. */
    static final class PromptRecord {
        final ChallengeType type;
        final String packId;
        final long atMs;

        PromptRecord(ChallengeType type, String packId, long atMs) {
            this.type = type;
            this.packId = packId;
            this.atMs = atMs;
        }
    }
}
