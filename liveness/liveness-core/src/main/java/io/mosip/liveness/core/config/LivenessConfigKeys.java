/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.config;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Every liveness configuration key name (docs/design/configuration.md). Defaults and allowed ranges of the global
 * keys are in {@link PolicyKey}; the per-workflow defaults are here.
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

    /** Identifier of the engine to use, looked up in the engine registry. */
    public static final String ENGINE = PREFIX + "engine";

    /**
     * Inference back end of the default ONNX engine: {@code CPU}, {@code XNNPACK} or {@code NNAPI} (Android only).
     * A back end that the installed ONNX Runtime does not include falls back to {@code CPU}. Read when the engine
     * loads, so a change needs a client restart.
     */
    public static final String ENGINE_EXECUTION_PROVIDER = PREFIX + "engine.execution_provider";

    // ---- Passive ----

    /** Passive score (0-100) at or above which a face passes without a challenge. */
    public static final String PASSIVE_THRESHOLD = PREFIX + "passive.threshold";
    /** Shipped value of {@code <workflow>.passive.threshold} for the five authentication workflows. */
    public static final int DEFAULT_AUTH_PASSIVE_THRESHOLD = 95;

    /** Passive score (0-100) below which the face is treated as a presentation attack and fails at once. */
    public static final String PAD_REJECT_THRESHOLD = PREFIX + "pad.reject_threshold";

    /** Number of good frames needed for the passive decision. */
    public static final String PASSIVE_MIN_FRAMES = PREFIX + "passive.min_frames";

    /** Maximum time for acquisition plus the passive phase, in milliseconds. */
    public static final String PASSIVE_TIMEOUT_MS = PREFIX + "passive.timeout_ms";

    // ---- Active ----

    /** Whether escalation to challenges is allowed, {@code Y}/{@code N}. */
    public static final String ACTIVE_ENABLE = PREFIX + "active.enable";

    /** Minimum number of challenges in the active phase. */
    public static final String ACTIVE_MIN_CHALLENGES = PREFIX + "active.min_challenges";

    /** Challenge pool as comma-separated {@link ChallengeType} names. */
    public static final String ACTIVE_CHALLENGE_TYPES = PREFIX + "active.challenge_types";

    /** Time allowed per challenge, in milliseconds. */
    public static final String ACTIVE_CHALLENGE_TIMEOUT_MS = PREFIX + "active.challenge_timeout_ms";

    /** Shortest accepted reaction time after a prompt, in milliseconds; faster onsets are rejected. */
    public static final String ACTIVE_MIN_ONSET_MS = PREFIX + "active.min_onset_ms";

    /** Time an action must be held, in milliseconds. */
    public static final String ACTIVE_HOLD_MS = PREFIX + "active.hold_ms";

    // ---- Retries ----

    /** Liveness attempts per transaction (one workflow for one subject). */
    public static final String MAX_RETRIES = PREFIX + "max_retries";

    /** Global behaviour after the last failed attempt; see {@link OnMaxFailure}. */
    public static final String ON_MAX_FAILURE = PREFIX + "on_max_failure";

    // ---- Stream, device and integrity ----

    /** STREAM timeout for liveness sessions, in milliseconds (mosip-config {@code capture_time_out} is 10000). */
    public static final String STREAM_TIMEOUT_MS = PREFIX + "stream.timeout_ms";

    /** 0 processes every frame; a positive value processes at most one frame per interval, in milliseconds. */
    public static final String FRAME_SAMPLE_INTERVAL_MS = PREFIX + "frame.sample_interval_ms";

    /** Automatic stream reconnects before a device error. */
    public static final String DEVICE_RECONNECT_ATTEMPTS = PREFIX + "device.reconnect_attempts";

    /** Minimum inter-eye distance in pixels during acquisition. */
    public static final String QUALITY_MIN_IED_PX = PREFIX + "quality.min_ied_px";

    /** Largest head turn in degrees accepted during acquisition (not the stored-image limit). */
    public static final String QUALITY_MAX_YAW_DEG = PREFIX + "quality.max_yaw_deg";

    /** Identical frames allowed in a row before the stream counts as frozen. */
    public static final String INTEGRITY_MAX_FROZEN_FRAMES = PREFIX + "integrity.max_frozen_frames";

    /** Minimum stream frame rate (the SBI minimum). */
    public static final String INTEGRITY_MIN_FPS = PREFIX + "integrity.min_fps";

    // ---- Binding ----

    /** Minimum similarity (0-100) between the captured face and the session face. */
    public static final String BINDING_MIN_SIMILARITY = PREFIX + "binding.min_similarity";

    /** Time limit from pass to capture in milliseconds (authentication flows: from device Ready). */
    public static final String BINDING_MAX_GAP_MS = PREFIX + "binding.max_gap_ms";

    // ---- Devices without a stream, models, diagnostics ----

    /** Behaviour for devices that cannot stream; see {@link NoStreamPolicy}. */
    public static final String NO_STREAM_POLICY = PREFIX + "no_stream_policy";

    /** Minimum model bundle version (semantic version); blocks rollback. */
    public static final String MODEL_MIN_VERSION = PREFIX + "model.min_version";

    /**
     * Online model update: {@code https://} URL of a signed model bundle ({@code .zip}); {@code http://} only to a
     * loopback host. Empty (the default) turns the online update off. The downloaded bundle goes through the same
     * checks as an offline import.
     */
    public static final String MODEL_UPDATE_URL = PREFIX + "model.update_url";

    /**
     * Model-signing public key(s) a model bundle must be signed with, as PEM {@code PUBLIC KEY} blocks (RSA). Unset:
     * no model loads ({@code CONFIG_INVALID}). Synced only; never a local value.
     */
    public static final String MODEL_TRUST_ANCHOR_PEM = PREFIX + "model.trust_anchor_pem";

    /** Diagnostic log lines and supervisor overlay, {@code Y}/{@code N}. */
    public static final String DIAGNOSTIC_ENABLE = PREFIX + "diagnostic.enable";

    /** Anonymised metrics, {@code Y}/{@code N}. */
    public static final String METRICS_ENABLE = PREFIX + "metrics.enable";

    // ---- Client-specific keys ----

    /** Android device check: accepted SBI certification levels as CSV (change N7). */
    public static final String MDS_ALLOWED_CERTIFICATIONS = "mosip.registration.mds.allowed_certifications";

    /** Android face device adapter: {@code BUILTIN} (in-process camera) or {@code SBI} (device app). */
    public static final String ANDROID_DEVICE = PREFIX + "android.device";

    /** Android: block liveness on an emulator, {@code Y}/{@code N} ({@code Y} recommended in production). */
    public static final String ANDROID_BLOCK_EMULATOR = PREFIX + "android.block_emulator";

    /** Android: allow supervisor approval without network, {@code Y}/{@code N} (change N12). */
    public static final String ANDROID_OFFLINE_APPROVAL_ENABLE = "mosip.registration.android.offline_approval.enable";

    // ---- Per-workflow key suffixes ----

    /** Suffix of the per-workflow passive threshold: {@code <PREFIX><workflow>.passive.threshold}. */
    public static final String WORKFLOW_PASSIVE_THRESHOLD_SUFFIX = "passive.threshold";
    /** Suffix of the per-workflow challenge count: {@code <PREFIX><workflow>.min_challenges}. */
    public static final String WORKFLOW_MIN_CHALLENGES_SUFFIX = "min_challenges";
    /** Suffix of the per-workflow attempt limit: {@code <PREFIX><workflow>.max_retries}. */
    public static final String WORKFLOW_MAX_RETRIES_SUFFIX = "max_retries";
    /** Suffix of the per-workflow failure behaviour: {@code <PREFIX><workflow>.on_max_failure}. */
    public static final String WORKFLOW_ON_MAX_FAILURE_SUFFIX = "on_max_failure";

    private static final List<String> GLOBAL_KEYS = buildGlobalKeys();

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
                : OnMaxFailure.valueOf(PolicyKey.ON_MAX_FAILURE.defaultValue());
    }

    /**
     * Returns the default passive threshold of a workflow.
     *
     * @param workflow the workflow
     * @return 95 for authentication workflows, 80 otherwise
     */
    public static int defaultPassiveThreshold(Workflow workflow) {
        return workflow.isAuth() ? DEFAULT_AUTH_PASSIVE_THRESHOLD : PolicyKey.PASSIVE_THRESHOLD.defaultInt();
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
        for (PolicyKey k : PolicyKey.values()) {
            m.put(k.key(), k.defaultValue());
        }
        OnMaxFailure globalOnMaxFailure = OnMaxFailure.valueOf(PolicyKey.ON_MAX_FAILURE.defaultValue());
        for (Workflow w : Workflow.values()) {
            m.put(policyKey(w), defaultPolicyMode(w).name());
            if (w.isAuth()) {
                m.put(workflowKey(w, WORKFLOW_PASSIVE_THRESHOLD_SUFFIX), Integer.toString(DEFAULT_AUTH_PASSIVE_THRESHOLD));
            }
            if (defaultOnMaxFailure(w) != globalOnMaxFailure) {
                m.put(workflowKey(w, WORKFLOW_ON_MAX_FAILURE_SUFFIX), defaultOnMaxFailure(w).name());
            }
        }
        return Collections.unmodifiableMap(m);
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

    private static List<String> buildGlobalKeys() {
        List<String> keys = new ArrayList<>();
        for (PolicyKey k : PolicyKey.values()) {
            keys.add(k.key());
        }
        return Collections.unmodifiableList(keys);
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
