/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Every liveness configuration key, with its default and allowed range (docs/design/configuration.md).
 *
 * <p>The same key names are used by the desktop and Android clients and served by the MOSIP config sync. Keys
 * avoid the substrings {@code kernel}, {@code mosip.primary}, {@code mosip.biometric.sdk} and {@code languages},
 * which would force a client restart when changed. Boolean keys use {@code Y} / {@code N}.
 *
 * <p>Floors are enforced by the policy resolver, which clamps a lower value to the floor and audits the clamp
 * ({@code REG-EVT-LV15}).
 */
public final class LivenessConfigKeys {

    /** Common prefix of all liveness keys. */
    public static final String PREFIX = "mosip.registration.liveness.";
    /** Value meaning "yes" in boolean keys. */
    public static final String YES = "Y";
    /** Value meaning "no" in boolean keys. */
    public static final String NO = "N";

    // ---- Master switch and engine ----

    /** Master switch, {@code Y}/{@code N}; server only, never overridden locally. {@code N} means policy OFF everywhere. */
    public static final String ENABLE = PREFIX + "enable";
    /** Default of {@link #ENABLE}. */
    public static final String DEFAULT_ENABLE = YES;

    /** Identifier of the engine to use, looked up in the engine registry. */
    public static final String ENGINE = PREFIX + "engine";
    /** Default of {@link #ENGINE}. */
    public static final String DEFAULT_ENGINE = "mosip-onnx-default";

    /**
     * Inference back end of the default ONNX engine: {@code CPU}, {@code XNNPACK} or {@code NNAPI} (Android only).
     * A back end that the installed ONNX Runtime does not include falls back to {@code CPU}. Read when the engine
     * loads, so a change needs a client restart.
     */
    public static final String ENGINE_EXECUTION_PROVIDER = PREFIX + "engine.execution_provider";
    /** Default of {@link #ENGINE_EXECUTION_PROVIDER}: plain CPU, the fastest back end measured on desktop. */
    public static final String DEFAULT_ENGINE_EXECUTION_PROVIDER = "CPU";

    // ---- Passive ----

    /** Passive score (0-100) at or above which a face passes without a challenge. */
    public static final String PASSIVE_THRESHOLD = PREFIX + "passive.threshold";
    /** Default of {@link #PASSIVE_THRESHOLD} for resident workflows. */
    public static final int DEFAULT_PASSIVE_THRESHOLD = 80;
    /** Shipped value of {@code <workflow>.passive.threshold} for the five authentication workflows. */
    public static final int DEFAULT_AUTH_PASSIVE_THRESHOLD = 95;
    /** Lowest value the resolver accepts for any passive threshold. */
    public static final int PASSIVE_THRESHOLD_FLOOR = 50;

    /** Passive score (0-100) below which the face is treated as a presentation attack and fails at once. */
    public static final String PAD_REJECT_THRESHOLD = PREFIX + "pad.reject_threshold";
    /** Default of {@link #PAD_REJECT_THRESHOLD}. */
    public static final int DEFAULT_PAD_REJECT_THRESHOLD = 20;
    /** Lowest value the resolver accepts for {@link #PAD_REJECT_THRESHOLD}. */
    public static final int PAD_REJECT_THRESHOLD_FLOOR = 5;

    /** Number of good frames needed for the passive decision. */
    public static final String PASSIVE_MIN_FRAMES = PREFIX + "passive.min_frames";
    /**
     * Default of {@link #PASSIVE_MIN_FRAMES}: 30 frames (2 s at 15 fps), long enough for the loop detector to see
     * a replayed clip repeat before the passive decision (calibration notes, proposal P3).
     */
    public static final int DEFAULT_PASSIVE_MIN_FRAMES = 30;

    /** Maximum time for acquisition plus the passive phase, in milliseconds. */
    public static final String PASSIVE_TIMEOUT_MS = PREFIX + "passive.timeout_ms";
    /** Default of {@link #PASSIVE_TIMEOUT_MS}. */
    public static final long DEFAULT_PASSIVE_TIMEOUT_MS = 4_000L;

    // ---- Active ----

    /** Whether escalation to challenges is allowed, {@code Y}/{@code N}. */
    public static final String ACTIVE_ENABLE = PREFIX + "active.enable";
    /** Default of {@link #ACTIVE_ENABLE}. */
    public static final String DEFAULT_ACTIVE_ENABLE = YES;

    /** Minimum number of challenges in the active phase. */
    public static final String ACTIVE_MIN_CHALLENGES = PREFIX + "active.min_challenges";
    /** Default of {@link #ACTIVE_MIN_CHALLENGES}. */
    public static final int DEFAULT_MIN_CHALLENGES = 2;
    /** Smallest allowed number of challenges. */
    public static final int MIN_CHALLENGES_LOWER = 1;
    /** Largest allowed number of challenges. */
    public static final int MIN_CHALLENGES_UPPER = 5;

    /** Challenge pool as comma-separated {@link ChallengeType} names. */
    public static final String ACTIVE_CHALLENGE_TYPES = PREFIX + "active.challenge_types";
    /** Default of {@link #ACTIVE_CHALLENGE_TYPES}. */
    public static final String DEFAULT_CHALLENGE_TYPES = "BLINK,SMILE,TURN_LEFT,TURN_RIGHT,LOOK_UP";

    /** Time allowed per challenge, in milliseconds. */
    public static final String ACTIVE_CHALLENGE_TIMEOUT_MS = PREFIX + "active.challenge_timeout_ms";
    /** Default of {@link #ACTIVE_CHALLENGE_TIMEOUT_MS}. */
    public static final long DEFAULT_CHALLENGE_TIMEOUT_MS = 5_000L;
    /** Smallest allowed challenge timeout in milliseconds. */
    public static final long CHALLENGE_TIMEOUT_LOWER_MS = 2_000L;
    /** Largest allowed challenge timeout in milliseconds (accessibility: up to 15 s). */
    public static final long CHALLENGE_TIMEOUT_UPPER_MS = 15_000L;

    /** Shortest accepted reaction time after a prompt, in milliseconds; faster onsets are rejected. */
    public static final String ACTIVE_MIN_ONSET_MS = PREFIX + "active.min_onset_ms";
    /** Default of {@link #ACTIVE_MIN_ONSET_MS}. */
    public static final long DEFAULT_MIN_ONSET_MS = 200L;

    /** Time an action must be held, in milliseconds. */
    public static final String ACTIVE_HOLD_MS = PREFIX + "active.hold_ms";
    /** Default of {@link #ACTIVE_HOLD_MS}. */
    public static final long DEFAULT_HOLD_MS = 300L;

    // ---- Retries ----

    /** Liveness attempts per transaction (one workflow for one subject). */
    public static final String MAX_RETRIES = PREFIX + "max_retries";
    /** Default of {@link #MAX_RETRIES}. */
    public static final int DEFAULT_MAX_RETRIES = 3;
    /** Smallest allowed number of attempts. */
    public static final int MAX_RETRIES_LOWER = 1;
    /** Largest allowed number of attempts. */
    public static final int MAX_RETRIES_UPPER = 10;

    /** Global behaviour after the last failed attempt; see {@link OnMaxFailure}. */
    public static final String ON_MAX_FAILURE = PREFIX + "on_max_failure";
    /** Default of {@link #ON_MAX_FAILURE}. */
    public static final OnMaxFailure DEFAULT_ON_MAX_FAILURE = OnMaxFailure.BLOCK;

    // ---- Stream, device and integrity ----

    /** STREAM timeout for liveness sessions, in milliseconds (mosip-config {@code capture_time_out} is 10000). */
    public static final String STREAM_TIMEOUT_MS = PREFIX + "stream.timeout_ms";
    /** Default of {@link #STREAM_TIMEOUT_MS}. */
    public static final long DEFAULT_STREAM_TIMEOUT_MS = 60_000L;

    /** 0 processes every frame; a positive value processes at most one frame per interval, in milliseconds. */
    public static final String FRAME_SAMPLE_INTERVAL_MS = PREFIX + "frame.sample_interval_ms";
    /** Default of {@link #FRAME_SAMPLE_INTERVAL_MS}. */
    public static final long DEFAULT_FRAME_SAMPLE_INTERVAL_MS = 0L;

    /** Automatic stream reconnects before a device error. */
    public static final String DEVICE_RECONNECT_ATTEMPTS = PREFIX + "device.reconnect_attempts";
    /** Default of {@link #DEVICE_RECONNECT_ATTEMPTS}. */
    public static final int DEFAULT_DEVICE_RECONNECT_ATTEMPTS = 1;

    /** Minimum inter-eye distance in pixels during acquisition. */
    public static final String QUALITY_MIN_IED_PX = PREFIX + "quality.min_ied_px";
    /** Default of {@link #QUALITY_MIN_IED_PX}. */
    public static final int DEFAULT_QUALITY_MIN_IED_PX = 60;

    /** Largest head turn in degrees accepted during acquisition (not the stored-image limit). */
    public static final String QUALITY_MAX_YAW_DEG = PREFIX + "quality.max_yaw_deg";
    /** Default of {@link #QUALITY_MAX_YAW_DEG}. */
    public static final int DEFAULT_QUALITY_MAX_YAW_DEG = 15;

    /** Identical frames allowed in a row before the stream counts as frozen. */
    public static final String INTEGRITY_MAX_FROZEN_FRAMES = PREFIX + "integrity.max_frozen_frames";
    /** Default of {@link #INTEGRITY_MAX_FROZEN_FRAMES}. */
    public static final int DEFAULT_INTEGRITY_MAX_FROZEN_FRAMES = 5;

    /** Minimum stream frame rate (the SBI minimum). */
    public static final String INTEGRITY_MIN_FPS = PREFIX + "integrity.min_fps";
    /** Default of {@link #INTEGRITY_MIN_FPS}. */
    public static final int DEFAULT_INTEGRITY_MIN_FPS = 3;

    // ---- Binding ----

    /** Minimum similarity (0-100) between the captured face and the session face. */
    public static final String BINDING_MIN_SIMILARITY = PREFIX + "binding.min_similarity";
    /** Default of {@link #BINDING_MIN_SIMILARITY}. */
    public static final int DEFAULT_BINDING_MIN_SIMILARITY = 60;

    /** Time limit from pass to capture in milliseconds (authentication flows: from device Ready). */
    public static final String BINDING_MAX_GAP_MS = PREFIX + "binding.max_gap_ms";
    /** Default of {@link #BINDING_MAX_GAP_MS}. */
    public static final long DEFAULT_BINDING_MAX_GAP_MS = 5_000L;

    // ---- Devices without a stream, models, diagnostics ----

    /** Behaviour for devices that cannot stream; see {@link NoStreamPolicy}. */
    public static final String NO_STREAM_POLICY = PREFIX + "no_stream_policy";
    /** Default of {@link #NO_STREAM_POLICY}. */
    public static final NoStreamPolicy DEFAULT_NO_STREAM_POLICY = NoStreamPolicy.BLOCK;

    /** Minimum model bundle version (semantic version); blocks rollback. */
    public static final String MODEL_MIN_VERSION = PREFIX + "model.min_version";
    /** Default of {@link #MODEL_MIN_VERSION}. */
    public static final String DEFAULT_MODEL_MIN_VERSION = "1.3.0";

    /**
     * Model-signing public key(s) a model bundle must be signed with, as PEM {@code PUBLIC KEY} blocks (RSA). Unset:
     * the built-in development key, which a production deployment must replace. Synced only; never a local value.
     */
    public static final String MODEL_TRUST_ANCHOR_PEM = PREFIX + "model.trust_anchor_pem";

    /** Diagnostic log lines and supervisor overlay, {@code Y}/{@code N}. */
    public static final String DIAGNOSTIC_ENABLE = PREFIX + "diagnostic.enable";
    /** Default of {@link #DIAGNOSTIC_ENABLE}. */
    public static final String DEFAULT_DIAGNOSTIC_ENABLE = NO;

    /** Anonymised metrics, {@code Y}/{@code N}. */
    public static final String METRICS_ENABLE = PREFIX + "metrics.enable";
    /** Default of {@link #METRICS_ENABLE}. */
    public static final String DEFAULT_METRICS_ENABLE = YES;

    // ---- Client-specific keys ----

    /** Android device check: accepted SBI certification levels as CSV (change N7). */
    public static final String MDS_ALLOWED_CERTIFICATIONS = "mosip.registration.mds.allowed_certifications";
    /** Default of {@link #MDS_ALLOWED_CERTIFICATIONS}. */
    public static final String DEFAULT_MDS_ALLOWED_CERTIFICATIONS = "L0,L1";

    /** Android face device adapter: {@code BUILTIN} (in-process camera) or {@code SBI} (device app). */
    public static final String ANDROID_DEVICE = PREFIX + "android.device";
    /** Default of {@link #ANDROID_DEVICE}. */
    public static final String DEFAULT_ANDROID_DEVICE = "BUILTIN";

    /** Android: block liveness on an emulator, {@code Y}/{@code N} ({@code Y} recommended in production). */
    public static final String ANDROID_BLOCK_EMULATOR = PREFIX + "android.block_emulator";
    /** Default of {@link #ANDROID_BLOCK_EMULATOR}. */
    public static final String DEFAULT_ANDROID_BLOCK_EMULATOR = NO;

    /** Android: allow supervisor approval without network, {@code Y}/{@code N} (change N12). */
    public static final String ANDROID_OFFLINE_APPROVAL_ENABLE = "mosip.registration.android.offline_approval.enable";
    /** Default of {@link #ANDROID_OFFLINE_APPROVAL_ENABLE}. */
    public static final String DEFAULT_ANDROID_OFFLINE_APPROVAL_ENABLE = YES;

    // ---- Per-workflow key suffixes ----

    /** Suffix of the per-workflow passive threshold: {@code <PREFIX><workflow>.passive.threshold}. */
    public static final String WORKFLOW_PASSIVE_THRESHOLD_SUFFIX = "passive.threshold";
    /** Suffix of the per-workflow challenge count: {@code <PREFIX><workflow>.min_challenges}. */
    public static final String WORKFLOW_MIN_CHALLENGES_SUFFIX = "min_challenges";
    /** Suffix of the per-workflow attempt limit: {@code <PREFIX><workflow>.max_retries}. */
    public static final String WORKFLOW_MAX_RETRIES_SUFFIX = "max_retries";
    /** Suffix of the per-workflow failure behaviour: {@code <PREFIX><workflow>.on_max_failure}. */
    public static final String WORKFLOW_ON_MAX_FAILURE_SUFFIX = "on_max_failure";

    private static final List<String> GLOBAL_KEYS = Collections.unmodifiableList(Arrays.asList(
            ENABLE, ENGINE, ENGINE_EXECUTION_PROVIDER, PASSIVE_THRESHOLD, PAD_REJECT_THRESHOLD, PASSIVE_MIN_FRAMES, PASSIVE_TIMEOUT_MS,
            ACTIVE_ENABLE, ACTIVE_MIN_CHALLENGES, ACTIVE_CHALLENGE_TYPES, ACTIVE_CHALLENGE_TIMEOUT_MS,
            ACTIVE_MIN_ONSET_MS, ACTIVE_HOLD_MS, MAX_RETRIES, ON_MAX_FAILURE, STREAM_TIMEOUT_MS,
            FRAME_SAMPLE_INTERVAL_MS, DEVICE_RECONNECT_ATTEMPTS, QUALITY_MIN_IED_PX, QUALITY_MAX_YAW_DEG,
            INTEGRITY_MAX_FROZEN_FRAMES, INTEGRITY_MIN_FPS, BINDING_MIN_SIMILARITY, BINDING_MAX_GAP_MS,
            NO_STREAM_POLICY, MODEL_MIN_VERSION, DIAGNOSTIC_ENABLE, METRICS_ENABLE, MDS_ALLOWED_CERTIFICATIONS,
            ANDROID_DEVICE, ANDROID_BLOCK_EMULATOR, ANDROID_OFFLINE_APPROVAL_ENABLE));

    private static final Set<String> LOCAL_OVERRIDE_ALLOWED = buildLocalOverrideAllowed();

    private LivenessConfigKeys() {
    }

    /**
     * Returns the policy key of a workflow, {@code mosip.registration.liveness.policy.<workflow>}.
     *
     * @param workflow the workflow
     * @return the key
     */
    public static String policyKey(Workflow workflow) {
        return PREFIX + "policy." + workflow.id();
    }

    /**
     * Returns a per-workflow override key, {@code mosip.registration.liveness.<workflow>.<suffix>}.
     *
     * @param workflow the workflow
     * @param suffix   one of the {@code WORKFLOW_*_SUFFIX} constants
     * @return the key
     */
    public static String workflowKey(Workflow workflow, String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        return PREFIX + workflow.id() + "." + suffix;
    }

    /**
     * Returns the key that replaces one message text in one language,
     * {@code mosip.registration.liveness.guidance.<messageKey>.<lang>}.
     *
     * @param messageKey a key from {@code io.mosip.liveness.core.ui.MessageKeys}
     * @param language   language code as used by the client, for example {@code "eng"}
     * @return the key
     */
    public static String guidanceKey(String messageKey, String language) {
        Objects.requireNonNull(messageKey, "messageKey");
        Objects.requireNonNull(language, "language");
        return PREFIX + "guidance." + messageKey + "." + language;
    }

    /**
     * Returns the default policy mode of a workflow.
     *
     * <p>Every workflow that checks liveness always runs the challenges. The scenario suite showed synthetic prints
     * and screen replays that the passive model scores 82-99, above both passive thresholds (80 and 95;
     * calibration notes, proposal P1), so a passive-first policy would accept them with no challenge. This holds
     * for resident registration, operator onboarding and login, and the supervisor workflows ({@code packet_auth},
     * {@code exception_auth}, {@code eod_auth}) alike. The exception photo is the only workflow without a
     * liveness check.
     *
     * @param workflow the workflow
     * @return {@code OFF} for {@code exception_photo}; {@code ALWAYS_ACTIVE} for every other workflow
     */
    public static PolicyMode defaultPolicyMode(Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        return workflow == Workflow.EXCEPTION_PHOTO ? PolicyMode.OFF : PolicyMode.ALWAYS_ACTIVE;
    }

    /**
     * Returns the default behaviour after the last failed attempt of a workflow.
     *
     * @param workflow the workflow
     * @return {@code BLOCK_AND_LOCK} for {@code login_auth}, {@code BLOCK} otherwise
     */
    public static OnMaxFailure defaultOnMaxFailure(Workflow workflow) {
        // Operator login also counts a failed login: that lock is kept in the client's database, so a restart does
        // not give a fresh set of attempts.
        return Objects.requireNonNull(workflow, "workflow") == Workflow.LOGIN_AUTH ? OnMaxFailure.BLOCK_AND_LOCK
                : DEFAULT_ON_MAX_FAILURE;
    }

    /**
     * Returns the default passive threshold of a workflow.
     *
     * @param workflow the workflow
     * @return 95 for authentication workflows, 80 otherwise
     */
    public static int defaultPassiveThreshold(Workflow workflow) {
        return workflow.isAuth() ? DEFAULT_AUTH_PASSIVE_THRESHOLD : DEFAULT_PASSIVE_THRESHOLD;
    }

    /**
     * Lists the global keys (all keys that do not contain a workflow or message key).
     *
     * @return an unmodifiable list in declaration order
     */
    public static List<String> globalKeys() {
        return GLOBAL_KEYS;
    }

    /**
     * Returns the shipped default of every key that has one, including the per-workflow policy keys and the
     * authentication passive thresholds. Clients write these into their built-in defaults (the desktop
     * {@code spring.properties} needs every key, because a missing key reads as {@code null}).
     *
     * @return an unmodifiable map from key to default value, in a stable order
     */
    public static Map<String, String> defaultValues() {
        Map<String, String> m = new LinkedHashMap<>();
        putMasterAndPassive(m);
        putActiveAndRetries(m);
        putStreamIntegrityBinding(m);
        putClientSpecific(m);
        for (Workflow w : Workflow.values()) {
            m.put(policyKey(w), defaultPolicyMode(w).name());
            if (w.isAuth()) {
                m.put(workflowKey(w, WORKFLOW_PASSIVE_THRESHOLD_SUFFIX), Integer.toString(DEFAULT_AUTH_PASSIVE_THRESHOLD));
            }
            if (defaultOnMaxFailure(w) != DEFAULT_ON_MAX_FAILURE) {
                m.put(workflowKey(w, WORKFLOW_ON_MAX_FAILURE_SUFFIX), defaultOnMaxFailure(w).name());
            }
        }
        return Collections.unmodifiableMap(m);
    }

    private static void putMasterAndPassive(Map<String, String> m) {
        m.put(ENABLE, DEFAULT_ENABLE);
        m.put(ENGINE, DEFAULT_ENGINE);
        m.put(ENGINE_EXECUTION_PROVIDER, DEFAULT_ENGINE_EXECUTION_PROVIDER);
        m.put(PASSIVE_THRESHOLD, Integer.toString(DEFAULT_PASSIVE_THRESHOLD));
        m.put(PAD_REJECT_THRESHOLD, Integer.toString(DEFAULT_PAD_REJECT_THRESHOLD));
        m.put(PASSIVE_MIN_FRAMES, Integer.toString(DEFAULT_PASSIVE_MIN_FRAMES));
        m.put(PASSIVE_TIMEOUT_MS, Long.toString(DEFAULT_PASSIVE_TIMEOUT_MS));
    }

    private static void putActiveAndRetries(Map<String, String> m) {
        m.put(ACTIVE_ENABLE, DEFAULT_ACTIVE_ENABLE);
        m.put(ACTIVE_MIN_CHALLENGES, Integer.toString(DEFAULT_MIN_CHALLENGES));
        m.put(ACTIVE_CHALLENGE_TYPES, DEFAULT_CHALLENGE_TYPES);
        m.put(ACTIVE_CHALLENGE_TIMEOUT_MS, Long.toString(DEFAULT_CHALLENGE_TIMEOUT_MS));
        m.put(ACTIVE_MIN_ONSET_MS, Long.toString(DEFAULT_MIN_ONSET_MS));
        m.put(ACTIVE_HOLD_MS, Long.toString(DEFAULT_HOLD_MS));
        m.put(MAX_RETRIES, Integer.toString(DEFAULT_MAX_RETRIES));
        m.put(ON_MAX_FAILURE, DEFAULT_ON_MAX_FAILURE.name());
    }

    private static void putStreamIntegrityBinding(Map<String, String> m) {
        m.put(STREAM_TIMEOUT_MS, Long.toString(DEFAULT_STREAM_TIMEOUT_MS));
        m.put(FRAME_SAMPLE_INTERVAL_MS, Long.toString(DEFAULT_FRAME_SAMPLE_INTERVAL_MS));
        m.put(DEVICE_RECONNECT_ATTEMPTS, Integer.toString(DEFAULT_DEVICE_RECONNECT_ATTEMPTS));
        m.put(QUALITY_MIN_IED_PX, Integer.toString(DEFAULT_QUALITY_MIN_IED_PX));
        m.put(QUALITY_MAX_YAW_DEG, Integer.toString(DEFAULT_QUALITY_MAX_YAW_DEG));
        m.put(INTEGRITY_MAX_FROZEN_FRAMES, Integer.toString(DEFAULT_INTEGRITY_MAX_FROZEN_FRAMES));
        m.put(INTEGRITY_MIN_FPS, Integer.toString(DEFAULT_INTEGRITY_MIN_FPS));
        m.put(BINDING_MIN_SIMILARITY, Integer.toString(DEFAULT_BINDING_MIN_SIMILARITY));
        m.put(BINDING_MAX_GAP_MS, Long.toString(DEFAULT_BINDING_MAX_GAP_MS));
        m.put(NO_STREAM_POLICY, DEFAULT_NO_STREAM_POLICY.name());
        m.put(MODEL_MIN_VERSION, DEFAULT_MODEL_MIN_VERSION);
        m.put(DIAGNOSTIC_ENABLE, DEFAULT_DIAGNOSTIC_ENABLE);
        m.put(METRICS_ENABLE, DEFAULT_METRICS_ENABLE);
    }

    private static void putClientSpecific(Map<String, String> m) {
        m.put(MDS_ALLOWED_CERTIFICATIONS, DEFAULT_MDS_ALLOWED_CERTIFICATIONS);
        m.put(ANDROID_DEVICE, DEFAULT_ANDROID_DEVICE);
        m.put(ANDROID_BLOCK_EMULATOR, DEFAULT_ANDROID_BLOCK_EMULATOR);
        m.put(ANDROID_OFFLINE_APPROVAL_ENABLE, DEFAULT_ANDROID_OFFLINE_APPROVAL_ENABLE);
    }

    /**
     * Tells whether a key may be overridden on the client machine ({@code PermittedLocalConfig}). Allowed: the
     * PAD thresholds (within floors), the challenge and stream timeouts, attempt limits and the diagnostic switch.
     * Never: {@code enable}, {@code policy.*}, {@code engine}, {@code model.min_version}, {@code on_max_failure},
     * and the keys that would weaken a check with no floor: {@code binding.min_similarity} (any value down to 0
     * would accept a different face), {@code active.challenge_types} (a pool of the easiest challenge only) and
     * {@code passive.timeout_ms} (a short limit lets the passive decision rest on as few as
     * {@code passive.timeout_min_frames} frames). The server can still change all of them through sync.
     *
     * @param key full configuration key
     * @return {@code true} if a local override is permitted
     */
    public static boolean isLocalOverrideAllowed(String key) {
        return key != null && LOCAL_OVERRIDE_ALLOWED.contains(key);
    }

    /**
     * Lists every key that may be overridden locally, including the per-workflow threshold and attempt keys.
     *
     * @return an unmodifiable set
     */
    public static Set<String> localOverrideAllowedKeys() {
        return LOCAL_OVERRIDE_ALLOWED;
    }

    private static Set<String> buildLocalOverrideAllowed() {
        Set<String> s = new LinkedHashSet<>(Arrays.asList(PASSIVE_THRESHOLD, PAD_REJECT_THRESHOLD,
                ACTIVE_CHALLENGE_TIMEOUT_MS, STREAM_TIMEOUT_MS, MAX_RETRIES, DIAGNOSTIC_ENABLE));
        for (Workflow w : Workflow.values()) {
            s.add(workflowKey(w, WORKFLOW_PASSIVE_THRESHOLD_SUFFIX));
            s.add(workflowKey(w, WORKFLOW_MAX_RETRIES_SUFFIX));
        }
        return Collections.unmodifiableSet(s);
    }
}
