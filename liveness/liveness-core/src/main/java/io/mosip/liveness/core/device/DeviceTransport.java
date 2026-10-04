/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

/**
 * How the client reaches a face capture device. The same {@link FaceCaptureDevice} contract covers all of them.
 */
public enum DeviceTransport {

    /** SBI device service over HTTP on 127.0.0.1 (desktop; MockMDS or a vendor service). */
    SBI_HTTP,
    /** SBI device app called through Android intents. */
    SBI_INTENT,
    /** Camera of the machine or tablet used in-process (Android CameraX), acting as an L0 device. */
    BUILTIN_CAMERA,
    /** Recorded frames played back (tests, demonstrations, evaluation). */
    REPLAY
}
