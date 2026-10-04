/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.mosip.liveness.core.ui.PromptIcon;

/**
 * Debug builds only: makes a replayed frame pack answer the capture screen's challenge prompts, the way the person
 * in front of the camera would. It plays the subject's neutral pack and, a reaction time after the screen asks for
 * an action (blink, smile, head turn, look up or down, gaze), switches the {@link ReplayFrameSource} to the pack
 * that shows that movement; any other prompt ("Action detected", "Look directly at the camera and hold still",
 * checking, results) switches back to the neutral pack at once, continuing where it was left.
 *
 * <p>Packs follow the naming of the evaluation frame packs: {@code <prefix>-main} and one {@code <prefix>-<action>}
 * per action, with the actions of {@link #PACK_ACTIONS} ({@code blink}, {@code smile}, {@code turn-left},
 * {@code turn-right}, {@code look-up}, {@code look-down}, {@code gaze-left}, {@code gaze-right}). Names are compared
 * without case, and {@code _} or a space count as {@code -}, so {@code S3-Turn_Left} answers the turn-left prompt
 * ({@link #findPacks}). A prompt without a pack keeps the neutral pack, so that challenge times out as it would
 * for a person who does not react.
 *
 * <p>The reaction time matters: the engine refuses a movement that starts sooner than
 * {@code active.min_onset_ms} after the prompt ({@code ONSET_TOO_FAST}). The desktop end-to-end driver uses the
 * same rule when it switches MockMDS's frame source.
 */
public final class ReplayPromptFollower {

    /** Default delay between a challenge prompt and the start of the movement. */
    public static final long DEFAULT_REACTION_MS = 500L;

    /** Action name of the neutral pack ({@code <prefix>-main}). */
    public static final String MAIN_ACTION = "main";

    /**
     * Action name of the pack that answers each challenge prompt, as in {@code <prefix>-<action>}. Every other
     * prompt is answered by the neutral pack.
     */
    public static final Map<PromptIcon, String> PACK_ACTIONS = packActions();

    /** Posts a task after a delay (for example {@code Handler::postDelayed}). */
    public interface Scheduler {
        /**
         * Runs the task after the delay.
         *
         * @param task    the task
         * @param delayMs delay in milliseconds
         */
        void schedule(Runnable task, long delayMs);
    }

    private final ReplayFrameSource source;
    private final List<byte[]> neutral;
    private final Map<PromptIcon, List<byte[]>> movements;
    private final Scheduler scheduler;
    private final long reactionMs;
    private List<byte[]> playing;
    private List<byte[]> wanted;
    private long generation;

    /**
     * Creates a follower for one stream.
     *
     * @param source     the replay stream, started with {@code neutral}
     * @param neutral    frames of the neutral pack
     * @param movements  frames per challenge prompt (keys of {@link #PACK_ACTIONS}); missing ones are allowed,
     *                   other prompts are ignored
     * @param scheduler  runs the delayed switch; its tasks must run on the thread that calls {@link #onPrompt}
     * @param reactionMs delay before a movement starts, 0 or more
     */
    public ReplayPromptFollower(ReplayFrameSource source, List<byte[]> neutral,
                                Map<PromptIcon, List<byte[]>> movements, Scheduler scheduler, long reactionMs) {
        this.source = Objects.requireNonNull(source, "source");
        this.neutral = Objects.requireNonNull(neutral, "neutral");
        Map<PromptIcon, List<byte[]>> answered = new EnumMap<>(PromptIcon.class);
        if (movements != null) {
            for (Map.Entry<PromptIcon, List<byte[]>> e : movements.entrySet()) {
                if (PACK_ACTIONS.containsKey(e.getKey()) && e.getValue() != null) {
                    answered.put(e.getKey(), e.getValue());
                }
            }
        }
        this.movements = Collections.unmodifiableMap(answered);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (reactionMs < 0) {
            throw new IllegalArgumentException("reactionMs must be 0 or more: " + reactionMs);
        }
        this.reactionMs = reactionMs;
        this.playing = neutral;
        this.wanted = neutral;
    }

    /**
     * Reacts to the prompt the screen shows now. Call it on one thread (the screen's main thread) for every
     * rendered state; repeated calls with the same prompt change nothing.
     *
     * @param prompt the state's prompt icon; {@code null} counts as a neutral prompt
     */
    public void onPrompt(PromptIcon prompt) {
        List<byte[]> target = prompt == null ? neutral : movements.getOrDefault(prompt, neutral);
        if (target == wanted) {
            return;
        }
        wanted = target;
        long ticket = ++generation;
        if (target == neutral) {
            play(target);
            return;
        }
        scheduler.schedule(() -> {
            if (ticket == generation) {
                play(target);
            }
        }, reactionMs);
    }

    /**
     * Tells whether a pack exists for the prompt.
     *
     * @param prompt a prompt icon
     * @return {@code true} when the follower answers that prompt
     */
    public boolean answers(PromptIcon prompt) {
        return movements.containsKey(prompt);
    }

    /**
     * Finds the packs of one subject among the available pack names: the neutral pack and one movement pack per
     * challenge prompt. A name matches {@code <prefix>-<action>} without regard to case, with {@code _} or a space
     * read as {@code -}; when two names match the same action, the first one in {@code available} wins.
     *
     * @param prefix    subject prefix, for example {@code s3n}
     * @param available names of the packs that exist (folders or asset folders)
     * @return the matching packs
     */
    public static Packs findPacks(String prefix, Collection<String> available) {
        String wanted = normalise(Objects.requireNonNull(prefix, "prefix")) + "-";
        String main = null;
        Map<PromptIcon, String> found = new EnumMap<>(PromptIcon.class);
        for (String name : available == null ? Collections.<String>emptyList() : available) {
            String n = name == null ? "" : normalise(name);
            if (!n.startsWith(wanted)) {
                continue;
            }
            String action = n.substring(wanted.length());
            if (MAIN_ACTION.equals(action)) {
                main = main == null ? name : main;
                continue;
            }
            PromptIcon prompt = promptOf(action);
            if (prompt != null && !found.containsKey(prompt)) {
                found.put(prompt, name);
            }
        }
        return new Packs(main, found);
    }

    /**
     * Returns the challenge prompt an action name stands for. The name is compared without case and {@code _} or
     * a space count as {@code -}: {@code turn-left}, {@code TURN_LEFT} and {@code Turn left} all give
     * {@link PromptIcon#TURN_LEFT}.
     *
     * @param action an action name
     * @return the prompt, or {@code null} for the neutral pack and unknown names
     */
    public static PromptIcon promptOf(String action) {
        if (action == null) {
            return null;
        }
        String n = normalise(action);
        for (Map.Entry<PromptIcon, String> e : PACK_ACTIONS.entrySet()) {
            if (e.getValue().equals(n)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String normalise(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    private static Map<PromptIcon, String> packActions() {
        Map<PromptIcon, String> m = new LinkedHashMap<>();
        m.put(PromptIcon.BLINK, "blink");
        m.put(PromptIcon.SMILE, "smile");
        m.put(PromptIcon.TURN_LEFT, "turn-left");
        m.put(PromptIcon.TURN_RIGHT, "turn-right");
        m.put(PromptIcon.LOOK_UP, "look-up");
        m.put(PromptIcon.LOOK_DOWN, "look-down");
        m.put(PromptIcon.GAZE_LEFT, "gaze-left");
        m.put(PromptIcon.GAZE_RIGHT, "gaze-right");
        return Collections.unmodifiableMap(m);
    }

    /** The packs of one subject found by {@link #findPacks}. */
    public static final class Packs {
        private final String main;
        private final Map<PromptIcon, String> movements;

        Packs(String main, Map<PromptIcon, String> movements) {
            this.main = main;
            this.movements = Collections.unmodifiableMap(movements);
        }

        /**
         * Returns the neutral pack.
         *
         * @return the name as found, or {@code null} when there is none
         */
        public String main() {
            return main;
        }

        /**
         * Returns the movement packs.
         *
         * @return pack name per challenge prompt, as found
         */
        public Map<PromptIcon, String> movements() {
            return movements;
        }

        /**
         * Returns the prompts that have a pack.
         *
         * @return the prompts, in enum order
         */
        public List<PromptIcon> prompts() {
            return new ArrayList<>(movements.keySet());
        }
    }

    private void play(List<byte[]> frames) {
        if (frames != playing) {
            playing = frames;
            if (frames == neutral) {
                source.switchBack();
            } else {
                source.switchTo(frames);
            }
        }
    }
}
