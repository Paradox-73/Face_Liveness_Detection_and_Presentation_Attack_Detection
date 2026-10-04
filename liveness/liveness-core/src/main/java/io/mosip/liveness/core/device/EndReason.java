/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

/**
 * Why a stream ended normally (without a {@link DeviceError}).
 */
public enum EndReason {

    /** The device closed the stream (for example a replay source ran out of frames). */
    END_OF_STREAM,
    /** The requested stream timeout elapsed. */
    STREAM_TIMEOUT,
    /** The consumer closed the source (for example after the pass in an authentication flow). */
    CLOSED
}
