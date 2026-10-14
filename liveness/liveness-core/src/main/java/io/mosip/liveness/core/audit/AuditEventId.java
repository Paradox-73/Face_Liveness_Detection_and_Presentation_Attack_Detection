/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.audit;

import java.util.Optional;

/**
 * Liveness audit events, with the same identifiers on the desktop and Android clients
 * (docs/design/audit-and-evidence.md section 1).
 *
 * <p>Identifiers contain {@code REG-EVT}, so the desktop audit service replaces the reference ID with the
 * registration ID during a registration. The {@code LV} infix keeps them clear of the Android client's
 * {@code REG-EVT-104..114}. All identifiers fit the {@code EVENT_ID VARCHAR(64)} column. Audit payloads never
 * contain images, embeddings or raw scores.
 */
public enum AuditEventId {

    /** A session opened. Payload: workflow, sessionId, attempt, device serial, certification, engine@version. */
    LIVENESS_SESSION_STARTED("REG-EVT-LV01", "Liveness session started"),
    /** Passive check accepted the face. Payload: score bucket, frames, elapsed ms. */
    LIVENESS_PASSIVE_PASSED("REG-EVT-LV02", "Liveness passive check passed"),
    /** Escalation to active checks. Payload: score bucket, reason ({@code BELOW_THRESHOLD} or {@code POLICY}). */
    LIVENESS_ACTIVE_TRIGGERED("REG-EVT-LV03", "Liveness active check triggered"),
    /** A challenge prompt was shown. Payload: challenge type, index, SHA-256 prefix of the nonce. */
    LIVENESS_CHALLENGE_ISSUED("REG-EVT-LV04", "Liveness challenge issued"),
    /** A challenge action was verified. Payload: challenge type, onset ms, hold ms. */
    LIVENESS_CHALLENGE_PASSED("REG-EVT-LV05", "Liveness challenge passed"),
    /** A challenge failed (wrong or no action). Payload: challenge type, reason. */
    LIVENESS_CHALLENGE_FAILED("REG-EVT-LV06", "Liveness challenge failed"),
    /** The passive phase or a challenge timed out. Payload: phase. */
    LIVENESS_TIMEOUT("REG-EVT-LV07", "Liveness timeout"),
    /**
     * PAD or integrity failure. Payload: reason ({@code PAD_SCORE}, {@code FROZEN}, {@code LOOP}, {@code PLANAR},
     * {@code FACE_SWITCH}, {@code DEVICE_PAD}).
     */
    LIVENESS_PAD_ATTACK_SUSPECTED("REG-EVT-LV08", "Liveness presentation attack suspected"),
    /** The signed capture did not match the session. Payload: similarity bucket, gap ms, challenge echo ok. */
    LIVENESS_BINDING_FAILED("REG-EVT-LV09", "Liveness capture binding failed"),
    /** The session passed. Payload: method, total ms. */
    LIVENESS_PASSED("REG-EVT-LV10", "Liveness passed"),
    /** The attempt limit was reached. Payload: attempts, on_max_failure. */
    LIVENESS_MAX_RETRIES("REG-EVT-LV11", "Liveness maximum attempts reached"),
    /** Device unavailable, disconnected, invalid frames, or Ready not reached. Payload: error code. */
    LIVENESS_DEVICE_ERROR("REG-EVT-LV12", "Liveness device error"),
    /** Model load or inference error. Payload: error class. */
    LIVENESS_ENGINE_ERROR("REG-EVT-LV13", "Liveness engine error"),
    /** Model hash, signature or version failure. Payload: model ID. */
    LIVENESS_MODEL_INTEGRITY_FAILED("REG-EVT-LV14", "Liveness model integrity check failed"),
    /** Configuration change, floor clamp or model version change. Payload: changed keys or versions. */
    LIVENESS_CONFIG_APPLIED("REG-EVT-LV15", "Liveness configuration applied"),
    /** A supervisor override was used. Payload: supervisor ID. */
    LIVENESS_OVERRIDE_USED("REG-EVT-LV16", "Liveness supervisor override used"),
    /** Diagnostic mode was switched. Payload: user ID. */
    LIVENESS_DIAGNOSTIC_MODE("REG-EVT-LV17", "Liveness diagnostic mode changed");

    private final String id;
    private final String description;

    AuditEventId(String id, String description) {
        this.id = id;
        this.description = description;
    }

    /**
     * Returns the event identifier written to the audit table.
     *
     * @return for example {@code "REG-EVT-LV08"}
     */
    public String id() {
        return id;
    }

    /**
     * Returns the event name; the constant name.
     *
     * @return for example {@code "LIVENESS_PAD_ATTACK_SUSPECTED"}
     */
    public String eventName() {
        return name();
    }

    /**
     * Returns a short English description of the event type.
     *
     * @return the description
     */
    public String description() {
        return description;
    }

    /**
     * Finds an event by its identifier.
     *
     * @param id identifier such as {@code "REG-EVT-LV01"}; may be {@code null}
     * @return the event, or empty if unknown
     */
    public static Optional<AuditEventId> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (AuditEventId e : values()) {
            if (e.id.equals(id.trim())) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }
}
