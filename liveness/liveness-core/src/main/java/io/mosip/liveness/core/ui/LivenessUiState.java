/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Everything a client needs to draw the liveness panel at one moment.
 *
 * <p>The engine produces these states and both clients only render them, which keeps desktop and Android
 * behaviour identical. The state never contains scores or the reason a PAD check failed (a suspected attack
 * always shows {@link MessageKeys#FAIL_PAD}). Texts are resolved by the client from {@link #messageKey()} and
 * {@link #args()}.
 */
public final class LivenessUiState {

    private final UiPhase phase;
    private final String messageKey;
    private final List<String> args;
    private final List<String> guidanceKeys;
    private final double progress;
    private final PromptIcon promptIcon;
    private final int stepIndex;
    private final int stepCount;
    private final int attempt;
    private final int maxAttempts;
    private final boolean retryAvailable;
    private final boolean overrideAvailable;
    private final Severity severity;

    private LivenessUiState(Builder b) {
        this.phase = b.phase;
        this.messageKey = b.messageKey;
        this.args = Collections.unmodifiableList(new ArrayList<>(b.args));
        this.guidanceKeys = Collections.unmodifiableList(new ArrayList<>(b.guidanceKeys));
        this.progress = b.progress;
        this.promptIcon = b.promptIcon;
        this.stepIndex = b.stepIndex;
        this.stepCount = b.stepCount;
        this.attempt = b.attempt;
        this.maxAttempts = b.maxAttempts;
        this.retryAvailable = b.retryAvailable;
        this.overrideAvailable = b.overrideAvailable;
        this.severity = b.severity;
    }

    /**
     * Starts a builder.
     *
     * @param phase      screen phase
     * @param messageKey main message key from {@link MessageKeys}
     * @return a builder with progress 0, no icon, attempt 1 of 1 and severity INFO
     */
    public static Builder builder(UiPhase phase, String messageKey) {
        return new Builder(phase, messageKey);
    }

    /**
     * Returns the screen phase.
     *
     * @return the phase
     */
    public UiPhase phase() {
        return phase;
    }

    /**
     * Returns the main message key.
     *
     * @return a key from {@link MessageKeys}
     */
    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns the arguments for the message placeholders {@code {0}}, {@code {1}}, ...
     *
     * @return an unmodifiable list, possibly empty
     */
    public List<String> args() {
        return args;
    }

    /**
     * Returns extra guidance message keys shown under the main message (for example after a liveness failure:
     * {@link MessageKeys#GUIDE_LIGHTING}).
     *
     * @return an unmodifiable list, possibly empty
     */
    public List<String> guidanceKeys() {
        return guidanceKeys;
    }

    /**
     * Returns the overall progress of the attempt.
     *
     * @return value 0 to 1
     */
    public double progress() {
        return progress;
    }

    /**
     * Returns the icon to show with the message.
     *
     * @return the icon
     */
    public PromptIcon promptIcon() {
        return promptIcon;
    }

    /**
     * Returns the current challenge step, for "step i of k".
     *
     * @return 1-based step index, or 0 outside the challenge phase
     */
    public int stepIndex() {
        return stepIndex;
    }

    /**
     * Returns the number of challenge steps.
     *
     * @return step count, or 0 if no challenges are planned
     */
    public int stepCount() {
        return stepCount;
    }

    /**
     * Returns the current attempt number.
     *
     * @return 1-based attempt
     */
    public int attempt() {
        return attempt;
    }

    /**
     * Returns the number of attempts allowed.
     *
     * @return {@code max_retries} of the workflow
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Tells whether a Retry button is shown.
     *
     * @return {@code true} if another attempt is allowed
     */
    public boolean isRetryAvailable() {
        return retryAvailable;
    }

    /**
     * Tells whether a supervisor override may be offered (resident workflows at maximum attempts, never after a
     * suspected attack).
     *
     * @return {@code true} if the override action is shown
     */
    public boolean isOverrideAvailable() {
        return overrideAvailable;
    }

    /**
     * Returns the styling of the state.
     *
     * @return the severity
     */
    public Severity severity() {
        return severity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LivenessUiState)) {
            return false;
        }
        LivenessUiState s = (LivenessUiState) o;
        return phase == s.phase && messageKey.equals(s.messageKey) && args.equals(s.args)
                && guidanceKeys.equals(s.guidanceKeys) && Double.compare(progress, s.progress) == 0
                && promptIcon == s.promptIcon && stepIndex == s.stepIndex && stepCount == s.stepCount
                && attempt == s.attempt && maxAttempts == s.maxAttempts && retryAvailable == s.retryAvailable
                && overrideAvailable == s.overrideAvailable && severity == s.severity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(phase, messageKey, args, guidanceKeys, progress, promptIcon, stepIndex, stepCount, attempt,
                maxAttempts, retryAvailable, overrideAvailable, severity);
    }

    @Override
    public String toString() {
        return "LivenessUiState[" + phase + ", " + messageKey + args + ", guidance=" + guidanceKeys + ", progress="
                + progress + ", icon=" + promptIcon + ", step=" + stepIndex + "/" + stepCount + ", attempt=" + attempt
                + "/" + maxAttempts + ", retry=" + retryAvailable + ", override=" + overrideAvailable + ", " + severity
                + "]";
    }

    /** Builder for {@link LivenessUiState}. */
    public static final class Builder {

        private final UiPhase phase;
        private final String messageKey;
        private final List<String> args = new ArrayList<>();
        private final List<String> guidanceKeys = new ArrayList<>();
        private double progress;
        private PromptIcon promptIcon = PromptIcon.NONE;
        private int stepIndex;
        private int stepCount;
        private int attempt = 1;
        private int maxAttempts = 1;
        private boolean retryAvailable;
        private boolean overrideAvailable;
        private Severity severity = Severity.INFO;

        private Builder(UiPhase phase, String messageKey) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.messageKey = Objects.requireNonNull(messageKey, "messageKey");
            if (messageKey.isEmpty()) {
                throw new IllegalArgumentException("messageKey must not be empty");
            }
        }

        /**
         * Appends message arguments.
         *
         * @param values argument texts, in placeholder order
         * @return this builder
         */
        public Builder args(String... values) {
            for (String v : values) {
                args.add(Objects.requireNonNull(v, "arg"));
            }
            return this;
        }

        /**
         * Appends a guidance message key.
         *
         * @param key a key from {@link MessageKeys}
         * @return this builder
         */
        public Builder guidance(String key) {
            guidanceKeys.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        /**
         * Sets the progress.
         *
         * @param progress value 0 to 1
         * @return this builder
         */
        public Builder progress(double progress) {
            this.progress = progress;
            return this;
        }

        /**
         * Sets the icon.
         *
         * @param promptIcon the icon
         * @return this builder
         */
        public Builder promptIcon(PromptIcon promptIcon) {
            this.promptIcon = Objects.requireNonNull(promptIcon, "promptIcon");
            return this;
        }

        /**
         * Sets the challenge step.
         *
         * @param stepIndex 1-based index, or 0 outside the challenge phase
         * @param stepCount number of steps, 0 or more
         * @return this builder
         */
        public Builder step(int stepIndex, int stepCount) {
            this.stepIndex = stepIndex;
            this.stepCount = stepCount;
            return this;
        }

        /**
         * Sets the attempt counter.
         *
         * @param attempt     1-based attempt
         * @param maxAttempts attempts allowed, at least {@code attempt}
         * @return this builder
         */
        public Builder attempt(int attempt, int maxAttempts) {
            this.attempt = attempt;
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Shows or hides the Retry button.
         *
         * @param retryAvailable whether another attempt is allowed
         * @return this builder
         */
        public Builder retryAvailable(boolean retryAvailable) {
            this.retryAvailable = retryAvailable;
            return this;
        }

        /**
         * Shows or hides the supervisor override action.
         *
         * @param overrideAvailable whether an override may be offered
         * @return this builder
         */
        public Builder overrideAvailable(boolean overrideAvailable) {
            this.overrideAvailable = overrideAvailable;
            return this;
        }

        /**
         * Sets the styling.
         *
         * @param severity the severity
         * @return this builder
         */
        public Builder severity(Severity severity) {
            this.severity = Objects.requireNonNull(severity, "severity");
            return this;
        }

        /**
         * Validates the fields and creates the state.
         *
         * @return the state
         * @throws IllegalArgumentException if progress is outside 0-1, the step or attempt counters are inconsistent
         */
        public LivenessUiState build() {
            if (!(progress >= 0 && progress <= 1)) {
                throw new IllegalArgumentException("progress must be 0-1");
            }
            if (stepCount < 0 || stepIndex < 0 || stepIndex > stepCount) {
                throw new IllegalArgumentException("step must satisfy 0 <= stepIndex <= stepCount");
            }
            if (attempt < 1 || maxAttempts < attempt) {
                throw new IllegalArgumentException("attempt must satisfy 1 <= attempt <= maxAttempts");
            }
            return new LivenessUiState(this);
        }
    }
}
