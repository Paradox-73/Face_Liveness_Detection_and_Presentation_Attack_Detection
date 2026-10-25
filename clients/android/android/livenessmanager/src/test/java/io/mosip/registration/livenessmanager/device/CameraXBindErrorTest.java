/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.ExecutionException;

import io.mosip.liveness.core.device.DeviceErrorCode;

/**
 * A camera that does not start is reported by cause, so the screen says "allow the camera" or "no camera" instead of
 * "camera in use" for every failure.
 */
public class CameraXBindErrorTest {

    @Test
    public void bindFailuresAreReportedByCause() {
        assertEquals(DeviceErrorCode.CAMERA_PERMISSION_DENIED,
                CameraXFrameSource.bindError(new SecurityException("no CAMERA permission")));
        assertEquals(DeviceErrorCode.NOT_FOUND,
                CameraXFrameSource.bindError(new IllegalArgumentException("No available camera can be found")));
        assertEquals(DeviceErrorCode.CAMERA_IN_USE,
                CameraXFrameSource.bindError(new IllegalStateException("camera closed by another app")));
        assertEquals("the provider future's cause counts", DeviceErrorCode.CAMERA_PERMISSION_DENIED,
                CameraXFrameSource.bindError(new ExecutionException(new SecurityException("denied"))));
    }
}
