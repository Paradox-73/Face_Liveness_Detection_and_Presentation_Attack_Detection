/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

/**
 * Which checks decided a session, recorded as packet evidence {@code LIVENESS_METHOD}.
 */
public enum LivenessMethod {

    /** Policy {@code OFF}: no liveness check ran. */
    NONE,
    /** The passive check alone accepted the face. */
    PASSIVE,
    /** The passive score was not enough and the challenges decided ({@code PASSIVE_THEN_ACTIVE} escalation). */
    PASSIVE_ACTIVE,
    /** Policy {@code ALWAYS_ACTIVE}: the passive score was recorded, the challenges decided. */
    ACTIVE,
    /** The device could not stream and {@code no_stream_policy=CAPTURE_ONLY}: signed capture without liveness. */
    CAPTURE_ONLY
}
