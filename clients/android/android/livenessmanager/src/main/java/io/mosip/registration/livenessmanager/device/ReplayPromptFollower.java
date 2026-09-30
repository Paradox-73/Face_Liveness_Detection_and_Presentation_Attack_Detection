package io.mosip.registration.livenessmanager.device;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.mosip.liveness.core.ui.PromptIcon;

/**
 * Debug builds only: makes a replayed frame pack answer the capture screen's challenge prompts, the way the person
 * in front of the camera would. It plays the subject's neutral pack and, a reaction time after the screen asks for
 * a blink, a smile or a head turn, switches the {@link ReplayFrameSource} to the pack that shows that movement;
 * any other prompt switches back to the neutral pack at once, continuing where it was left.
 *
 * <p>Packs follow the naming of the evaluation frame packs: {@code <prefix>-main}, {@code <prefix>-blink},
 * {@code <prefix>-smile}, {@code <prefix>-turn-left} and {@code <prefix>-turn-right}. A prompt without a pack
 * keeps the neutral pack, so that challenge times out as it would for a person who does not react.
 *
 * <p>The reaction time matters: the engine refuses a movement that starts sooner than
 * {@code active.min_onset_ms} after the prompt ({@code ONSET_TOO_FAST}). The desktop end-to-end driver uses the
 * same rule when it switches MockMDS's frame source.
 */
public final class ReplayPromptFollower {

    /** Default delay between a challenge prompt and the start of the movement. */
    public static final long DEFAULT_REACTION_MS = 500L;

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
     * @param movements  frames per challenge prompt ({@link PromptIcon#BLINK}, {@link PromptIcon#SMILE},
     *                   {@link PromptIcon#TURN_LEFT}, {@link PromptIcon#TURN_RIGHT}); missing ones are allowed
     * @param scheduler  runs the delayed switch; its tasks must run on the thread that calls {@link #onPrompt}
     * @param reactionMs delay before a movement starts, 0 or more
     */
    public ReplayPromptFollower(ReplayFrameSource source, List<byte[]> neutral,
                                Map<PromptIcon, List<byte[]>> movements, Scheduler scheduler, long reactionMs) {
        this.source = Objects.requireNonNull(source, "source");
        this.neutral = Objects.requireNonNull(neutral, "neutral");
        this.movements = movements == null || movements.isEmpty() ? Collections.emptyMap()
                : Collections.unmodifiableMap(new EnumMap<>(movements));
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
