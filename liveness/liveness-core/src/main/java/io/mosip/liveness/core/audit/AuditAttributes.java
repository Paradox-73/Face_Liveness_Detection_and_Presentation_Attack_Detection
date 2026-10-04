/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.audit;

/**
 * Attribute names used in liveness audit payloads, so both clients write identical JSON
 * (docs/design/audit-and-evidence.md section 1).
 *
 * <p>Values are short strings. Scores appear only as 10-point buckets ({@link #bucket(int)}), never raw.
 */
public final class AuditAttributes {

    /** Workflow identifier, for example {@code registration}. Always present. */
    public static final String WORKFLOW = "workflow";
    /** Session identifier. Always present. */
    public static final String SESSION_ID = "sessionId";
    /** Attempt number within the transaction, starting at 1. */
    public static final String ATTEMPT = "attempt";
    /** Serial number of the face device. */
    public static final String DEVICE_SERIAL = "deviceSerial";
    /** SBI certification level, {@code L0} or {@code L1}. */
    public static final String CERTIFICATION = "certification";
    /** Engine identifier and version, {@code id@version}. */
    public static final String ENGINE = "engine";
    /** A score as a 10-point band, see {@link #bucket(int)}. */
    public static final String SCORE_BUCKET = "scoreBucket";
    /** Number of frames used. */
    public static final String FRAMES = "frames";
    /** Elapsed time of the phase, in milliseconds. */
    public static final String ELAPSED_MS = "ms";
    /** A reason code name. */
    public static final String REASON = "reason";
    /** Challenge type name. */
    public static final String CHALLENGE_TYPE = "type";
    /** Challenge index within the session, starting at 0. */
    public static final String CHALLENGE_INDEX = "index";
    /** First 8 hex digits of the SHA-256 of the nonce. */
    public static final String NONCE_PREFIX = "nonceSha256Prefix";
    /** Time from prompt to action onset, in milliseconds. */
    public static final String ONSET_MS = "onsetMs";
    /** Time the action was held, in milliseconds. */
    public static final String HOLD_MS = "holdMs";
    /** Session phase or state name. */
    public static final String PHASE = "phase";
    /** Capture-to-session similarity as a 10-point band. */
    public static final String SIMILARITY_BUCKET = "similarityBucket";
    /** Pass-to-capture gap, in milliseconds. */
    public static final String GAP_MS = "gapMs";
    /** Whether the device echoed the PAD challenge, {@code true}, {@code false} or {@code n/a}. */
    public static final String CHALLENGE_ECHO_OK = "challengeEchoOk";
    /** Liveness method, for example {@code PASSIVE_ACTIVE}. */
    public static final String METHOD = "method";
    /** Total session time, in milliseconds. */
    public static final String TOTAL_MS = "totalMs";
    /** Number of attempts used. */
    public static final String ATTEMPTS = "attempts";
    /** Configured behaviour after the last failure. */
    public static final String ON_MAX_FAILURE = "onMaxFailure";
    /** Device or engine error code name. */
    public static final String ERROR_CODE = "errorCode";
    /** Simple class name of an engine exception (never its message). */
    public static final String ERROR_CLASS = "errorClass";
    /** Model identifier. */
    public static final String MODEL_ID = "modelId";
    /** Changed configuration keys or model versions, comma-separated. */
    public static final String CHANGED = "changed";
    /** Supervisor user ID (override event only). */
    public static final String SUPERVISOR_ID = "supervisorId";
    /** User ID (diagnostic-mode event only). */
    public static final String USER_ID = "userId";

    private AuditAttributes() {
    }

    /**
     * Converts a 0-100 score to its 10-point band, so audits never carry raw scores.
     *
     * @param score value 0-100
     * @return {@code "0-9"}, {@code "10-19"}, ... {@code "80-89"}, or {@code "90-100"}
     * @throws IllegalArgumentException if the score is outside 0-100
     */
    public static String bucket(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be 0-100");
        }
        if (score >= 90) {
            return "90-100";
        }
        int low = score / 10 * 10;
        return low + "-" + (low + 9);
    }
}
