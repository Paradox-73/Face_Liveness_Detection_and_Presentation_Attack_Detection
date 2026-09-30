package io.mosip.registration.livenessmanager;

import android.content.Intent;
import android.os.Bundle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.mosip.liveness.core.Workflow;

/**
 * What the capture screen should verify: workflow, subject, language and (debug builds only) the frame source.
 * Travels as {@link Intent} extras, so the screen can also be started from adb in debug builds:
 *
 * <pre>
 * adb shell am start -n io.mosip.registration_client/io.mosip.registration.livenessmanager.ui.LivenessCaptureActivity \
 *     --es liveness.workflow registration --es liveness.subject demo --es liveness.language eng \
 *     --es liveness.source replay --es liveness.replay mockmds-still
 * </pre>
 */
public final class LivenessCaptureRequest {

    /** Extra: workflow ID, for example {@code registration} or {@code packet_auth}. */
    public static final String EXTRA_WORKFLOW = "liveness.workflow";
    /** Extra: subject reference (registration ID or user ID); never shown. */
    public static final String EXTRA_SUBJECT = "liveness.subject";
    /** Extra: ARC language code ({@code eng}, {@code fra}, {@code ara}, {@code hin}, {@code kan}, {@code tam}). */
    public static final String EXTRA_LANGUAGE = "liveness.language";
    /** Extra: {@code camera} (default) or {@code replay} (debug builds only). */
    public static final String EXTRA_SOURCE = "liveness.source";
    /** Extra: replay pack name (debug builds only). */
    public static final String EXTRA_REPLAY = "liveness.replay";
    /** Extra: replay frames per second (debug builds only). */
    public static final String EXTRA_REPLAY_FPS = "liveness.replay_fps";
    /**
     * Extra: {@code true} makes the replay answer challenge prompts: the pack name is then a prefix, and the
     * packs {@code <prefix>-main}, {@code -blink}, {@code -smile}, {@code -turn-left}, {@code -turn-right} are
     * played as the prompts ask (debug builds only).
     */
    public static final String EXTRA_REPLAY_FOLLOW = "liveness.replay_follow";
    /**
     * Extra: {@code true} hides the replayed frames on the screen (the preview stays dark, like the masked
     * snapshots of the desktop end-to-end run), so screenshots show no face (debug builds only).
     */
    public static final String EXTRA_REPLAY_MASKED = "liveness.replay_masked";
    /**
     * Extra: {@code true} asks for a signed photo without a liveness check when the workflow's liveness policy is
     * {@code OFF}, instead of closing the screen at once (the exception photo taken with the built-in camera).
     */
    public static final String EXTRA_PHOTO_WHEN_OFF = "liveness.photo_when_off";
    /** Extra: attempts the subject already used for this capture on earlier screens (resident capture). */
    public static final String EXTRA_PRIOR_ATTEMPTS = "liveness.prior_attempts";
    /**
     * Extra: {@code true} opens the screen at the end of a resident transaction whose attempts are used up, only
     * to offer the supervisor override again (no session runs).
     */
    public static final String EXTRA_OVERRIDE_OFFER = "liveness.override_offer";
    /**
     * Extra: user ID of the supervisor who verified with face liveness and granted an override; the screen then
     * takes the resident's photo without a liveness check and flags it {@code LIVENESS_DECISION=OVERRIDE}.
     */
    public static final String EXTRA_OVERRIDE_SUPERVISOR = "liveness.override_supervisor";
    /** Extra: packet evidence of the failed transaction the override replaces (a {@link Bundle}). */
    public static final String EXTRA_OVERRIDE_EVIDENCE = "liveness.override_evidence";
    /** Extra: prefix of configuration values that replace synced ones (debug builds only). */
    public static final String EXTRA_CONFIG_PREFIX = "config.";

    /** Where frames come from. */
    public enum Source {
        /** The front camera (CameraX). */
        CAMERA,
        /** A recorded frame sequence (debug builds only). */
        REPLAY
    }

    private final Workflow workflow;
    private final String subjectRef;
    private final String language;
    private final Source source;
    private final String replayPack;
    private final int replayFps;
    private final boolean replayFollow;
    private final boolean replayMasked;
    private final boolean photoWhenOff;
    private final int priorAttempts;
    private final boolean overrideOffer;
    private final SupervisorOverride override;
    private final Map<String, String> configOverrides;

    private LivenessCaptureRequest(Builder b) {
        this.workflow = b.workflow;
        this.subjectRef = b.subjectRef;
        this.language = b.language;
        this.source = b.source;
        this.replayPack = b.replayPack;
        this.replayFps = b.replayFps;
        this.replayFollow = b.replayFollow;
        this.replayMasked = b.replayMasked;
        this.photoWhenOff = b.photoWhenOff;
        this.priorAttempts = b.priorAttempts;
        this.overrideOffer = b.overrideOffer;
        this.override = b.override;
        this.configOverrides = Collections.unmodifiableMap(new LinkedHashMap<>(b.configOverrides));
    }

    /**
     * Starts a request.
     *
     * @param workflow   the workflow
     * @param subjectRef registration ID or user ID
     * @return a builder
     */
    public static Builder builder(Workflow workflow, String subjectRef) {
        return new Builder(workflow, subjectRef);
    }

    /**
     * Reads a request from intent extras.
     *
     * @param intent the intent
     * @return the request; the workflow defaults to {@code registration} when missing or unknown
     */
    public static LivenessCaptureRequest fromIntent(Intent intent) {
        Bundle extras = intent == null ? null : intent.getExtras();
        if (extras == null) {
            extras = new Bundle();
        }
        Workflow workflow = Workflow.fromId(extras.getString(EXTRA_WORKFLOW, "")).orElse(Workflow.REGISTRATION);
        Builder b = builder(workflow, extras.getString(EXTRA_SUBJECT, ""))
                .language(extras.getString(EXTRA_LANGUAGE, "eng"))
                .photoWhenOff(parseBoolean(extras.get(EXTRA_PHOTO_WHEN_OFF)))
                .priorAttempts(parseInt(extras.get(EXTRA_PRIOR_ATTEMPTS), 0))
                .overrideOffer(parseBoolean(extras.get(EXTRA_OVERRIDE_OFFER)));
        String supervisor = extras.getString(EXTRA_OVERRIDE_SUPERVISOR, "");
        if (!supervisor.trim().isEmpty()) {
            b.override(new SupervisorOverride(supervisor, toMap(extras.getBundle(EXTRA_OVERRIDE_EVIDENCE))));
        }
        if ("replay".equalsIgnoreCase(extras.getString(EXTRA_SOURCE, ""))) {
            b.replay(extras.getString(EXTRA_REPLAY, ""), parseInt(extras.get(EXTRA_REPLAY_FPS), 15));
            b.replayFollow(parseBoolean(extras.get(EXTRA_REPLAY_FOLLOW)));
            b.replayMasked(parseBoolean(extras.get(EXTRA_REPLAY_MASKED)));
        }
        for (String key : extras.keySet()) {
            if (key.startsWith(EXTRA_CONFIG_PREFIX)) {
                Object v = extras.get(key);
                if (v != null) {
                    b.configOverride(key.substring(EXTRA_CONFIG_PREFIX.length()), String.valueOf(v));
                }
            }
        }
        return b.build();
    }

    /**
     * Writes this request into intent extras.
     *
     * @param intent the intent to fill
     * @return the same intent
     */
    public Intent writeTo(Intent intent) {
        intent.putExtra(EXTRA_WORKFLOW, workflow.id());
        intent.putExtra(EXTRA_SUBJECT, subjectRef);
        intent.putExtra(EXTRA_LANGUAGE, language);
        intent.putExtra(EXTRA_PHOTO_WHEN_OFF, photoWhenOff);
        intent.putExtra(EXTRA_PRIOR_ATTEMPTS, priorAttempts);
        intent.putExtra(EXTRA_OVERRIDE_OFFER, overrideOffer);
        if (override != null) {
            intent.putExtra(EXTRA_OVERRIDE_SUPERVISOR, override.supervisorId());
            Bundle evidence = new Bundle();
            override.failedEvidence().forEach(evidence::putString);
            intent.putExtra(EXTRA_OVERRIDE_EVIDENCE, evidence);
        }
        if (source == Source.REPLAY) {
            intent.putExtra(EXTRA_SOURCE, "replay");
            intent.putExtra(EXTRA_REPLAY, replayPack);
            intent.putExtra(EXTRA_REPLAY_FPS, replayFps);
            intent.putExtra(EXTRA_REPLAY_FOLLOW, replayFollow);
            intent.putExtra(EXTRA_REPLAY_MASKED, replayMasked);
        }
        for (Map.Entry<String, String> e : configOverrides.entrySet()) {
            intent.putExtra(EXTRA_CONFIG_PREFIX + e.getKey(), e.getValue());
        }
        return intent;
    }

    /**
     * Returns the workflow.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return workflow;
    }

    /**
     * Returns the subject reference.
     *
     * @return registration ID or user ID; may be empty
     */
    public String subjectRef() {
        return subjectRef;
    }

    /**
     * Returns the ARC language code.
     *
     * @return for example {@code eng}
     */
    public String language() {
        return language;
    }

    /**
     * Returns the requested frame source. Release builds ignore {@link Source#REPLAY}.
     *
     * @return the source
     */
    public Source source() {
        return source;
    }

    /**
     * Returns the replay pack name.
     *
     * @return the name; empty for the camera
     */
    public String replayPack() {
        return replayPack;
    }

    /**
     * Returns the replay rate.
     *
     * @return frames per second
     */
    public int replayFps() {
        return replayFps;
    }

    /**
     * Tells whether the replay answers challenge prompts ({@link #EXTRA_REPLAY_FOLLOW}).
     *
     * @return {@code true} when {@link #replayPack()} is a prefix of prompt-following packs
     */
    public boolean isReplayFollow() {
        return replayFollow;
    }

    /**
     * Tells whether replayed frames are hidden on the screen ({@link #EXTRA_REPLAY_MASKED}).
     *
     * @return {@code true} when the preview stays dark
     */
    public boolean isReplayMasked() {
        return replayMasked;
    }

    /**
     * Tells whether the screen takes a signed photo without liveness when the policy is {@code OFF}.
     *
     * @return {@code true} for the exception photo
     */
    public boolean isPhotoWhenOff() {
        return photoWhenOff;
    }

    /**
     * Returns the attempts the subject already used for this capture on earlier screens.
     *
     * @return 0 or more
     */
    public int priorAttempts() {
        return priorAttempts;
    }

    /**
     * Tells whether the screen only offers the supervisor override of a transaction whose attempts are used up.
     *
     * @return {@code true} for the override offer
     */
    public boolean isOverrideOffer() {
        return overrideOffer;
    }

    /**
     * Returns the supervisor override granted for this capture.
     *
     * @return the override; {@code null} for a normal capture
     */
    public SupervisorOverride override() {
        return override;
    }

    /**
     * Returns configuration values that replace synced ones. Only debug builds apply them.
     *
     * @return key-value pairs
     */
    public Map<String, String> configOverrides() {
        return configOverrides;
    }

    /**
     * Tells whether the capture is a supervisor's (shown with the "Supervisor verification" title).
     *
     * @return {@code true} for exception (reviewer) and end-of-day authentication
     */
    public boolean isSupervisorFlow() {
        return workflow == Workflow.EXCEPTION_AUTH || workflow == Workflow.EOD_AUTH;
    }

    @Override
    public String toString() {
        return "LivenessCaptureRequest[" + workflow.id() + ", lang=" + language + ", source=" + source
                + (source == Source.REPLAY ? ":" + replayPack + (replayFollow ? " (follows prompts)" : "") : "")
                + (overrideOffer ? ", override offer" : "") + (override != null ? ", supervisor override" : "")
                + "]";
    }

    private static Map<String, String> toMap(Bundle bundle) {
        Map<String, String> map = new LinkedHashMap<>();
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                String value = bundle.getString(key);
                if (value != null) {
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    private static boolean parseBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static int parseInt(Object value, int fallback) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Builder of {@link LivenessCaptureRequest}. */
    public static final class Builder {

        private final Workflow workflow;
        private final String subjectRef;
        private String language = "eng";
        private Source source = Source.CAMERA;
        private String replayPack = "";
        private int replayFps = 15;
        private boolean replayFollow;
        private boolean replayMasked;
        private boolean photoWhenOff;
        private int priorAttempts;
        private boolean overrideOffer;
        private SupervisorOverride override;
        private final Map<String, String> configOverrides = new LinkedHashMap<>();

        private Builder(Workflow workflow, String subjectRef) {
            this.workflow = Objects.requireNonNull(workflow, "workflow");
            this.subjectRef = subjectRef == null ? "" : subjectRef;
        }

        /**
         * Sets the display language.
         *
         * @param code ARC language code; blank keeps {@code eng}
         * @return this builder
         */
        public Builder language(String code) {
            if (code != null && !code.trim().isEmpty()) {
                this.language = code.trim().toLowerCase(Locale.ROOT);
            }
            return this;
        }

        /**
         * Uses a recorded frame sequence instead of the camera (debug builds only).
         *
         * @param pack pack name: an asset folder under {@code liveness-replay/} or a folder under the app's
         *             external files folder {@code liveness-replay/}
         * @param fps  frames per second
         * @return this builder
         */
        public Builder replay(String pack, int fps) {
            this.source = Source.REPLAY;
            this.replayPack = pack == null ? "" : pack.trim();
            this.replayFps = Math.max(1, Math.min(60, fps));
            return this;
        }

        /**
         * Makes the replay answer challenge prompts; {@link #replay(String, int)} then names the pack prefix
         * (debug builds only).
         *
         * @param follow {@code true} to follow prompts
         * @return this builder
         */
        public Builder replayFollow(boolean follow) {
            this.replayFollow = follow;
            return this;
        }

        /**
         * Hides the replayed frames on the screen, for screenshots without a face (debug builds only).
         *
         * @param masked {@code true} to keep the preview dark
         * @return this builder
         */
        public Builder replayMasked(boolean masked) {
            this.replayMasked = masked;
            return this;
        }

        /**
         * Asks for a signed photo without a liveness check when the workflow's liveness policy is {@code OFF}
         * (the exception photo); by default the screen closes with {@code CANCELLED} in that case.
         *
         * @param photo {@code true} to take the photo
         * @return this builder
         */
        public Builder photoWhenOff(boolean photo) {
            this.photoWhenOff = photo;
            return this;
        }

        /**
         * Sets the attempts the subject already used for this capture on earlier screens, so the screen continues
         * the count ("Attempt 2 of 3") instead of starting again.
         *
         * @param attempts 0 or more
         * @return this builder
         */
        public Builder priorAttempts(int attempts) {
            this.priorAttempts = Math.max(0, attempts);
            return this;
        }

        /**
         * Opens the screen only to offer the supervisor override again, after the resident's attempts were used
         * up on an earlier screen.
         *
         * @param offer {@code true} for the override offer
         * @return this builder
         */
        public Builder overrideOffer(boolean offer) {
            this.overrideOffer = offer;
            return this;
        }

        /**
         * Takes the resident's photo under a supervisor override granted after the supervisor's own face
         * verification (resident workflows with {@code on_max_failure=SUPERVISOR_OVERRIDE} only).
         *
         * @param grant the override; {@code null} for a normal capture
         * @return this builder
         */
        public Builder override(SupervisorOverride grant) {
            this.override = grant;
            return this;
        }

        /**
         * Replaces one configuration value for this capture (debug builds only).
         *
         * @param key   full key
         * @param value value
         * @return this builder
         */
        public Builder configOverride(String key, String value) {
            configOverrides.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the request
         */
        public LivenessCaptureRequest build() {
            return new LivenessCaptureRequest(this);
        }
    }
}
