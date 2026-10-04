/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

/**
 * Decision carried in a device-side PAD block (MOSIP issue {@code #1117}; an ISO/IEC 39794-5 PAD data block).
 */
public enum DevicePadDecision {

    /** The device found no attack ({@code NO_ATTACK}). Recorded as evidence; local checks still run. */
    PASS,
    /** The device detected an attack. The session fails with {@code FAILED(PAD)}, reason {@code DEVICE_PAD}. */
    FAIL,
    /** The device could not decide. Treated like an absent block. */
    UNDETERMINED
}
