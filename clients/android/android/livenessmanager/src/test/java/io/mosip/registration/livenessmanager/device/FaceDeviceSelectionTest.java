/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.PolicyKey;
import io.mosip.registration.livenessmanager.device.FaceDeviceSelection.Kind;
import io.mosip.registration.livenessmanager.device.FaceDeviceSelection.Route;

/** {@code mosip.registration.liveness.android.device} and the route of a face capture. */
@RunWith(RobolectricTestRunner.class)
public class FaceDeviceSelectionTest {

    @Test
    public void builtInCameraIsTheDefault() {
        assertEquals(Kind.BUILTIN, FaceDeviceSelection.kind(ConfigSource.empty()));
        assertEquals(Kind.BUILTIN, FaceDeviceSelection.kind(config("")));
        assertEquals(Kind.BUILTIN, FaceDeviceSelection.kind(config("   ")));
        assertEquals(Kind.BUILTIN, FaceDeviceSelection.kind(config("BUILTIN")));
        assertEquals(PolicyKey.ANDROID_DEVICE.defaultValue(), Kind.BUILTIN.name());
    }

    @Test
    public void sbiSelectsTheVendorAppIgnoringCaseAndSpaces() {
        assertEquals(Kind.SBI, FaceDeviceSelection.kind(config("SBI")));
        assertEquals(Kind.SBI, FaceDeviceSelection.kind(config(" sbi ")));
        assertEquals(Kind.BUILTIN, FaceDeviceSelection.kind(config("builtin")));
    }

    @Test
    public void unknownValueFallsBackToTheBuiltInCameraWithALogLine() {
        ShadowLog.clear();
        assertEquals(Kind.BUILTIN, FaceDeviceSelection.kind(config("USB-WEBCAM")));
        boolean logged = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("Liveness")) {
            logged |= item.msg.contains(LivenessConfigKeys.ANDROID_DEVICE) && item.msg.contains("USB-WEBCAM")
                    && item.msg.contains("BUILTIN");
        }
        assertTrue("an unknown value is logged", logged);
    }

    @Test
    public void builtInCameraTakesLivenessCapturesAndPhotosWhenLivenessIsOff() {
        for (boolean sbiApp : new boolean[] {false, true}) {
            assertEquals(Route.BUILT_IN_LIVENESS, FaceDeviceSelection.route(Kind.BUILTIN, true, sbiApp));
            assertEquals(Route.BUILT_IN_PHOTO, FaceDeviceSelection.route(Kind.BUILTIN, false, sbiApp));
        }
    }

    @Test
    public void vendorAppIsTheFaceDeviceWhenInstalled() {
        assertEquals(Route.SBI_LIVENESS, FaceDeviceSelection.route(Kind.SBI, true, true));
        assertEquals("liveness off: ARC's upstream SBI capture", Route.UPSTREAM_SBI,
                FaceDeviceSelection.route(Kind.SBI, false, true));
    }

    @Test
    public void vendorAppMissingFallsBackToTheBuiltInCamera() {
        ShadowLog.clear();
        assertEquals(Route.BUILT_IN_LIVENESS, FaceDeviceSelection.route(Kind.SBI, true, false));
        assertEquals("a tablet without a face SBI app keeps a face device", Route.BUILT_IN_PHOTO,
                FaceDeviceSelection.route(Kind.SBI, false, false));
        assertTrue(ShadowLog.getLogsForTag("Liveness").size() >= 2);
    }

    private static ConfigSource config(String device) {
        Map<String, String> values = new HashMap<>(Collections.singletonMap(LivenessConfigKeys.ANDROID_DEVICE,
                device));
        return ConfigSource.of(values);
    }
}
