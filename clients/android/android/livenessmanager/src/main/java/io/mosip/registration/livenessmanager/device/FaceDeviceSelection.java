/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import android.util.Log;

import java.util.Locale;
import java.util.Objects;

import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;

/**
 * Chooses the tablet's face device from {@code mosip.registration.liveness.android.device}, and how a face capture
 * reaches it.
 *
 * <ul>
 *   <li>{@code BUILTIN} (the default): the tablet's own camera ({@link BuiltInCameraL0Device}) on the liveness
 *       capture screen.</li>
 *   <li>{@code SBI}: a vendor SBI face app reached through Android intents ({@link SbiIntentDevice}).</li>
 *   <li>Any other value: logged, then {@code BUILTIN}.</li>
 * </ul>
 *
 * <p>When liveness does not run for a capture ({@code mosip.registration.liveness.enable=N}, or the workflow's
 * policy is {@code OFF}), the face is still taken without a liveness check: through ARC's own SBI intents when the
 * face device is {@code SBI} and a device app is installed, else with the built-in camera, so a tablet without a
 * face SBI app keeps a face device.
 */
public final class FaceDeviceSelection {

    private static final String TAG = "Liveness";

    /** The configured kind of face device. */
    public enum Kind {
        /** The tablet's own camera. */
        BUILTIN,
        /** A vendor SBI face app. */
        SBI
    }

    /** How one face capture is taken. */
    public enum Route {
        /** Liveness capture screen with the built-in camera. */
        BUILT_IN_LIVENESS,
        /** Built-in camera photo without a liveness check (capture screen in photo mode). */
        BUILT_IN_PHOTO,
        /** Vendor SBI face app as the liveness face device ({@link SbiIntentDevice}). */
        SBI_LIVENESS,
        /** ARC's upstream SBI intents, without a liveness check. */
        UPSTREAM_SBI
    }

    private FaceDeviceSelection() {
    }

    /**
     * Reads the configured face device.
     *
     * @param config liveness configuration
     * @return the kind; {@code BUILTIN} when the key is missing, blank or unknown (an unknown value is logged)
     */
    public static Kind kind(ConfigSource config) {
        Objects.requireNonNull(config, "config");
        return parse(config.get(LivenessConfigKeys.ANDROID_DEVICE).orElse(null));
    }

    /**
     * Parses a value of {@code mosip.registration.liveness.android.device}, ignoring case and surrounding spaces.
     *
     * @param value the configured value; may be {@code null}
     * @return the kind; {@code BUILTIN} when the value is missing, blank or unknown (an unknown value is logged)
     */
    public static Kind parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Kind.BUILTIN;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        for (Kind k : Kind.values()) {
            if (k.name().equals(v)) {
                return k;
            }
        }
        Log.w(TAG, "Unknown " + LivenessConfigKeys.ANDROID_DEVICE + " value '" + value.trim()
                + "'; using the built-in camera (BUILTIN)");
        return Kind.BUILTIN;
    }

    /**
     * Chooses how a face capture is taken.
     *
     * @param kind             the configured face device
     * @param livenessRequired whether the capture must pass liveness (its workflow's policy is enforced)
     * @param sbiAppInstalled  whether an app answers ARC's SBI discovery intent
     * @return the route
     */
    public static Route route(Kind kind, boolean livenessRequired, boolean sbiAppInstalled) {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.SBI && sbiAppInstalled) {
            return livenessRequired ? Route.SBI_LIVENESS : Route.UPSTREAM_SBI;
        }
        if (kind == Kind.SBI) {
            Log.w(TAG, LivenessConfigKeys.ANDROID_DEVICE + "=SBI but no SBI device app is installed; using the "
                    + "built-in camera");
        }
        return livenessRequired ? Route.BUILT_IN_LIVENESS : Route.BUILT_IN_PHOTO;
    }
}
