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

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One case of the cross-client parity file ({@code eval/interop/liveness-parity.json}): which frames are streamed,
 * in which workflow, with which configuration and random seed, and the decision the desktop client recorded.
 *
 * <p>Cases are read from the parsed JSON object (each client parses the file with its own JSON library), so this
 * class depends on nothing outside the engine core.
 */
public final class ParityCase {

    /** Frames that are committed to the repository; these cases run everywhere, CI included. */
    public static final String SOURCE_COMMITTED = "committed";
    /** Frames from the frame packs outside git ({@code run/frame-packs}); skipped where the packs are missing. */
    public static final String SOURCE_PACKS = "packs";

    /** What the main stream does at its last frame. */
    public enum AtEnd {
        /** Start again at the first frame. */
        LOOP,
        /** Play backwards, then forwards again (the scenario runner's default). */
        PINGPONG,
        /** End the stream. */
        END
    }

    /** What the stream does when the engine prompts for a challenge. */
    public enum OnPrompt {
        /** Play the clip of the prompted action. */
        MATCH,
        /** Play the clip of a different action. */
        MISMATCH,
        /** Keep playing the main stream. */
        IGNORE
    }

    private final String id;
    private final String source;
    private final Workflow workflow;
    private final long seed;
    private final int fps;
    private final long maxStreamMs;
    private final Map<String, String> config;
    private final String main;
    private final AtEnd atEnd;
    private final OnPrompt onPrompt;
    private final long reactionMs;
    private final int noise;
    private final Map<ChallengeType, String> actions;
    private final Map<String, Object> expected;

    private ParityCase(Map<String, Object> m) {
        this.id = text(m, "id", null);
        this.source = text(m, "source", SOURCE_COMMITTED);
        if (!SOURCE_COMMITTED.equals(source) && !SOURCE_PACKS.equals(source)) {
            throw new IllegalArgumentException(id + ": source must be committed or packs: " + source);
        }
        String wf = text(m, "workflow", Workflow.REGISTRATION.id());
        this.workflow = Workflow.fromId(wf).orElseThrow(() -> new IllegalArgumentException("unknown workflow " + wf));
        this.seed = number(m, "seed", 1L);
        this.fps = (int) number(m, "fps", 15L);
        this.maxStreamMs = number(m, "maxStreamMs", 60_000L);
        this.main = text(m, "main", null);
        this.atEnd = AtEnd.valueOf(text(m, "atEnd", "pingpong").toUpperCase(Locale.ROOT));
        this.onPrompt = OnPrompt.valueOf(text(m, "onPrompt", "match").toUpperCase(Locale.ROOT));
        this.reactionMs = number(m, "reactionMs", 400L);
        this.noise = (int) number(m, "noise", 0L);
        Map<String, String> cfg = new TreeMap<>();
        // Diagnostic records carry the per-frame PAD scores that the check compares; they change no decision.
        cfg.put(LivenessConfigKeys.DIAGNOSTIC_ENABLE, LivenessConfigKeys.YES);
        for (Map.Entry<String, Object> e : map(m, "config").entrySet()) {
            String key = e.getKey().startsWith("mosip.") ? e.getKey() : LivenessConfigKeys.PREFIX + e.getKey();
            cfg.put(key, String.valueOf(e.getValue()));
        }
        this.config = Collections.unmodifiableMap(cfg);
        Map<ChallengeType, String> acts = new EnumMap<>(ChallengeType.class);
        for (Map.Entry<String, Object> e : map(m, "actions").entrySet()) {
            acts.put(ChallengeType.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        this.actions = Collections.unmodifiableMap(acts);
        this.expected = m.get("expected") == null ? null : Collections.unmodifiableMap(map(m, "expected"));
    }

    /**
     * Reads a case from its parsed JSON object.
     *
     * @param json the object (string keys; numbers, strings, lists and objects as values)
     * @return the case
     * @throws IllegalArgumentException if a required field is missing or a value is unknown
     */
    public static ParityCase fromJson(Map<String, Object> json) {
        return new ParityCase(Objects.requireNonNull(json, "json"));
    }

    private static String text(Map<String, Object> m, String key, String dflt) {
        Object v = m.get(key);
        if (v == null) {
            if (dflt == null) {
                throw new IllegalArgumentException("parity case field missing: " + key);
            }
            return dflt;
        }
        return String.valueOf(v);
    }

    private static long number(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : v == null ? dflt : Long.parseLong(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return new LinkedHashMap<>();
        }
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("parity case field " + key + " must be an object");
        }
        return (Map<String, Object>) v;
    }

    /**
     * Returns the case identifier.
     *
     * @return for example {@code "committed-mockmds-still"}
     */
    public String id() {
        return id;
    }

    /**
     * Returns where the frames come from.
     *
     * @return {@link #SOURCE_COMMITTED} or {@link #SOURCE_PACKS}
     */
    public String source() {
        return source;
    }

    /**
     * Tells whether the frames are committed to the repository.
     *
     * @return {@code true} for {@link #SOURCE_COMMITTED}
     */
    public boolean isCommitted() {
        return SOURCE_COMMITTED.equals(source);
    }

    /**
     * Returns the workflow whose policy the session uses.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return workflow;
    }

    /**
     * Returns the seed of the session's random source (challenge draws and nonces).
     *
     * @return the seed
     */
    public long seed() {
        return seed;
    }

    /**
     * Returns the stream frame rate.
     *
     * @return frames per second of stream time
     */
    public int fps() {
        return fps;
    }

    /**
     * Returns the stream time after which the replay cancels the session.
     *
     * @return milliseconds
     */
    public long maxStreamMs() {
        return maxStreamMs;
    }

    /**
     * Returns the liveness configuration of the case (full key names).
     *
     * @return key to value; each client turns it into a policy through its own configuration classes
     */
    public Map<String, String> config() {
        return config;
    }

    /**
     * Returns the main stream: a committed image ({@code faces/<file>}) or a frame pack identifier.
     *
     * @return the reference
     */
    public String main() {
        return main;
    }

    /**
     * Returns what the main stream does at its end.
     *
     * @return the end behaviour
     */
    public AtEnd atEnd() {
        return atEnd;
    }

    /**
     * Returns how the stream answers a prompt.
     *
     * @return the prompt behaviour
     */
    public OnPrompt onPrompt() {
        return onPrompt;
    }

    /**
     * Returns the time between a prompt and the start of the action clip.
     *
     * @return milliseconds of stream time
     */
    public long reactionMs() {
        return reactionMs;
    }

    /**
     * Returns the noise added to every frame. With a value above zero each frame is decoded once by the client's
     * decoder and streamed as raw pixels, every pixel shifted by the same seeded amount in all three channels (at
     * most this many levels), so a committed still image streams like camera frames that differ slightly.
     *
     * @return the largest change per pixel; 0 streams the encoded frames unchanged
     */
    public int noise() {
        return noise;
    }

    /**
     * Returns the action clips by challenge.
     *
     * @return challenge to frame pack identifier
     */
    public Map<ChallengeType, String> actions() {
        return actions;
    }

    /**
     * Returns the recorded result.
     *
     * @return the {@code expected} object, or {@code null} if the file holds none yet
     */
    public Map<String, Object> expected() {
        return expected;
    }

    @Override
    public String toString() {
        return id;
    }
}
