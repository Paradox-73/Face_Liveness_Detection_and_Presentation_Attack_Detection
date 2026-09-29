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
    private final Map<String, String> configOverrides;

    private LivenessCaptureRequest(Builder b) {
        this.workflow = b.workflow;
        this.subjectRef = b.subjectRef;
        this.language = b.language;
        this.source = b.source;
        this.replayPack = b.replayPack;
        this.replayFps = b.replayFps;
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
                .language(extras.getString(EXTRA_LANGUAGE, "eng"));
        if ("replay".equalsIgnoreCase(extras.getString(EXTRA_SOURCE, ""))) {
            b.replay(extras.getString(EXTRA_REPLAY, ""), parseInt(extras.get(EXTRA_REPLAY_FPS), 15));
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
        if (source == Source.REPLAY) {
            intent.putExtra(EXTRA_SOURCE, "replay");
            intent.putExtra(EXTRA_REPLAY, replayPack);
            intent.putExtra(EXTRA_REPLAY_FPS, replayFps);
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
                + (source == Source.REPLAY ? ":" + replayPack : "") + "]";
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
