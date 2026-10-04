/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

/**
 * What went wrong with a face capture device. Any of these ends a session with {@code FAILED(DEVICE)}, which
 * does not use up an attempt, and is audited as {@code REG-EVT-LV12} with the constant name as error code.
 */
public enum DeviceErrorCode {

    /** No face device answered discovery. */
    NOT_FOUND,
    /** The device did not report {@code Ready} in time (auth flows allow 1,500 ms after the stream closes). */
    NOT_READY,
    /** The device cannot stream and {@code no_stream_policy} is {@code BLOCK}. */
    NO_STREAM,
    /** The stream request was refused or the connection could not be opened. */
    STREAM_OPEN_FAILED,
    /** No valid frame within the first-frame deadline (3 s). */
    FIRST_FRAME_TIMEOUT,
    /** The stream went silent for longer than the socket timeout (2 s) or the no-frame watchdog. */
    STALLED,
    /** The connection was reset or closed unexpectedly. */
    DISCONNECTED,
    /** The stream carried too many invalid parts to continue (wrong length, non-JPEG, truncated). */
    INVALID_STREAM,
    /** The signed capture call failed. */
    CAPTURE_FAILED,
    /** The signed capture did not arrive within the capture timeout. */
    CAPTURE_TIMEOUT,
    /** The capture signature or the device certificate did not verify against the DEVICE trust store. */
    SIGNATURE_INVALID,
    /** Android: the app has no camera permission. */
    CAMERA_PERMISSION_DENIED,
    /** Android: the camera is used by another app or could not be opened. */
    CAMERA_IN_USE,
    /** Android: running on an emulator while {@code android.block_emulator} is on. */
    EMULATOR_BLOCKED,
    /** Any other device failure. */
    UNKNOWN
}
