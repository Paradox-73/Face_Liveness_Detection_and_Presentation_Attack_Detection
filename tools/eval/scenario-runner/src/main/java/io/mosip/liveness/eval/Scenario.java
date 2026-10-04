/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One test scenario read from {@code eval/scenarios/<id>.yaml}: which frame packs to stream, how the stream reacts
 * to challenge prompts, the configuration and seeds to run with, and the expected outcome. See
 * {@code docs/eval/README.md} for the file format.
 */
final class Scenario {

    /** What the scenario presents to the engine. */
    enum Kind {
        /** A genuine person (bona fide presentation); counts toward BPCER and BPNRR. */
        BONA_FIDE,
        /** A presentation attack of one species; counts toward that species' APCER and APNRR. */
        ATTACK,
        /** Quality, timeout or device regression; not part of the ISO/IEC 30107-3 metrics. */
        OTHER
    }

    /** What happens when a pack reaches its last frame. */
    enum AtEnd {
        /** Start again from the first frame. */
        LOOP,
        /** Play backwards to the first frame, then forwards again. */
        PINGPONG,
        /** Repeat the last frame (the stream freezes). */
        HOLD,
        /** End the stream (device disconnect). */
        END
    }

    /** How the stream reacts to a challenge prompt. */
    enum PromptMode {
        /** Never switch: keep playing the main pack (a still photo, or a person who ignores the prompt). */
        IGNORE,
        /** Switch to the pack recorded for the prompted action (a real person, or an adaptive replay attack). */
        MATCH,
        /** Switch to a pack of a different action than the one prompted (a replay of the wrong clip). */
        MISMATCH,
        /** Switch to one fixed pack whatever was prompted. */
        FIXED
    }

    private final String id;
    private final String title;
    private final Kind kind;
    private final String species;
    private final String subject;
    private final String condition;
    private final Workflow workflow;
    private final List<Long> seeds;
    private final int fps;
    private final long maxStreamMs;
    private final Map<String, String> config;
    private final Map<String, String> engineParams;
    private final StreamPlan stream;
    private final Expectation expect;

    Scenario(String id, String title, Kind kind, String species, String subject, String condition, Workflow workflow,
             List<Long> seeds, int fps, long maxStreamMs, Map<String, String> config, Map<String, String> engineParams,
             StreamPlan stream, Expectation expect) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = title == null ? "" : title;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.species = Objects.requireNonNull(species, "species");
        this.subject = subject == null ? "" : subject;
        this.condition = condition == null ? "" : condition;
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.seeds = Collections.unmodifiableList(seeds);
        this.fps = fps;
        this.maxStreamMs = maxStreamMs;
        this.config = Collections.unmodifiableMap(new LinkedHashMap<>(config));
        this.engineParams = Collections.unmodifiableMap(new LinkedHashMap<>(engineParams));
        this.stream = Objects.requireNonNull(stream, "stream");
        this.expect = Objects.requireNonNull(expect, "expect");
    }

    String id() {
        return id;
    }

    String title() {
        return title;
    }

    Kind kind() {
        return kind;
    }

    /**
     * Returns the attack species (for example {@code SYN-P1}), or {@code BF} for bona fide scenarios.
     *
     * @return the species label used by the analysis
     */
    String species() {
        return species;
    }

    String subject() {
        return subject;
    }

    String condition() {
        return condition;
    }

    Workflow workflow() {
        return workflow;
    }

    /**
     * Returns the seeds; the runner runs one transaction per seed.
     *
     * @return at least one seed
     */
    List<Long> seeds() {
        return seeds;
    }

    /**
     * Returns the stream frame rate at which the packs are replayed.
     *
     * @return frames per second
     */
    int fps() {
        return fps;
    }

    /**
     * Returns the longest stream time one attempt may use before the runner gives up.
     *
     * @return milliseconds of stream time
     */
    long maxStreamMs() {
        return maxStreamMs;
    }

    /**
     * Returns the configuration overrides with full key names.
     *
     * @return keys such as {@code mosip.registration.liveness.active.challenge_types}
     */
    Map<String, String> config() {
        return config;
    }

    /**
     * Returns the engine parameter overrides (keys of {@code EngineParameter}).
     *
     * @return for example {@code planarity.min_residual -> 0.03}
     */
    Map<String, String> engineParams() {
        return engineParams;
    }

    StreamPlan stream() {
        return stream;
    }

    Expectation expect() {
        return expect;
    }

    @Override
    public String toString() {
        return "Scenario[" + id + ", " + kind + ", " + species + ", " + workflow.id() + "]";
    }

    /** The packs to stream and how to switch between them. */
    static final class StreamPlan {
        private final String main;
        private final AtEnd atEnd;
        private final PromptMode onPrompt;
        private final long reactionMs;
        private final Map<ChallengeType, String> actions;
        private final String fixedPack;

        StreamPlan(String main, AtEnd atEnd, PromptMode onPrompt, long reactionMs, Map<ChallengeType, String> actions,
                   String fixedPack) {
            this.main = Objects.requireNonNull(main, "main");
            this.atEnd = Objects.requireNonNull(atEnd, "atEnd");
            this.onPrompt = Objects.requireNonNull(onPrompt, "onPrompt");
            if (reactionMs < 0) {
                throw new IllegalArgumentException("reaction_ms must not be negative");
            }
            this.reactionMs = reactionMs;
            this.actions = actions.isEmpty() ? Collections.<ChallengeType, String>emptyMap()
                    : Collections.unmodifiableMap(new EnumMap<>(actions));
            this.fixedPack = fixedPack;
        }

        /**
         * Returns the pack played outside challenges (neutral face, photo, or looped clip).
         *
         * @return pack id
         */
        String main() {
            return main;
        }

        AtEnd atEnd() {
            return atEnd;
        }

        PromptMode onPrompt() {
            return onPrompt;
        }

        /**
         * Returns how long the main pack keeps playing after a prompt before the switch (human reaction time).
         *
         * @return milliseconds
         */
        long reactionMs() {
            return reactionMs;
        }

        /**
         * Returns the pack recorded for each action.
         *
         * @return action to pack id
         */
        Map<ChallengeType, String> actions() {
            return actions;
        }

        /**
         * Returns the pack used in {@link PromptMode#FIXED}.
         *
         * @return pack id, or {@code null}
         */
        String fixedPack() {
            return fixedPack;
        }

        /**
         * Lists every pack this plan can play.
         *
         * @return pack ids
         */
        Set<String> packIds() {
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            ids.add(main);
            ids.addAll(actions.values());
            if (fixedPack != null) {
                ids.add(fixedPack);
            }
            return ids;
        }
    }

    /** The expected transaction outcome. */
    static final class Expectation {
        private final Outcome outcome;
        private final Set<Layer> layers;

        Expectation(Outcome outcome, Set<Layer> layers) {
            this.outcome = outcome;
            this.layers = layers.isEmpty() ? Collections.<Layer>emptySet() : Collections.unmodifiableSet(layers);
        }

        /**
         * Returns the expected transaction outcome.
         *
         * @return the outcome, or {@code null} if the scenario only records what happens
         */
        Outcome outcome() {
            return outcome;
        }

        /**
         * Returns the layers allowed to reject the attempt; empty means any.
         *
         * @return the layers
         */
        Set<Layer> layers() {
            return layers;
        }
    }
}
