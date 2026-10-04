/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.mosip.registration.livenessmanager.LivenessRuntime;

/**
 * Runs one in-app provisioning step of the built-in face device and closes (no screen of its own):
 * {@link AndroidKeystoreSigner#provision} creates the Keystore key when missing, imports a waiting certificate
 * chain after checking it, and writes a new certificate request and the status to
 * {@code Android/data/<package>/files/liveness/}.
 *
 * <p>Started over adb by {@code scripts/android-liveness-device-cert.sh --csr}, on release and debug builds:
 * <pre>
 * adb shell am start -f 0x18000000 -n io.mosip.registration_client/io.mosip.registration.livenessmanager.device.DeviceProvisioningActivity \
 *     --es request ID [--ez replace_ca true]
 * </pre>
 * The manifest requires {@code android.permission.DUMP} from the caller: the adb shell holds it, ordinary apps
 * cannot get it.
 */
public final class DeviceProvisioningActivity extends Activity {

    /** Intent extra: identifier echoed in the status file. */
    public static final String EXTRA_REQUEST = "request";
    /** Intent extra: {@code true} to accept a chain from another CA than the current one. */
    public static final String EXTRA_REPLACE_CA = "replace_ca";

    private static final String TAG = "Liveness";

    /**
     * Request IDs already handled in this process. The system may create the screen again for the same request
     * (a relaunch, or a start that reaches an instance still closing); a second run would overwrite the status of
     * the first with "nothing to import".
     */
    private static final Set<String> HANDLED = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 32;
        }
    });

    /** Steps run one after the other, off the main thread: Keystore key generation can take seconds. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Steps started and not yet done; the screen closes when none is left. */
    private int pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        start(getIntent());
        if (pending == 0) {
            finish();
        }
    }

    /** A second request while this one is still open is delivered here instead of to a new instance. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        start(intent);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private void start(Intent intent) {
        String requestId = intent.getStringExtra(EXTRA_REQUEST);
        boolean replaceCa = intent.getBooleanExtra(EXTRA_REPLACE_CA, false);
        if (requestId != null && !requestId.isEmpty() && !firstTime(requestId)) {
            Log.i(TAG, "Device provisioning request " + requestId + " already handled");
            return;
        }
        if (worker.isShutdown()) {
            Log.w(TAG, "Device provisioning request " + requestId + " arrived after the screen closed");
            return;
        }
        pending++;
        worker.execute(() -> {
            try {
                provision(requestId, replaceCa);
            } finally {
                runOnUiThread(this::stepDone);
            }
        });
    }

    private static boolean firstTime(String requestId) {
        synchronized (HANDLED) {
            return HANDLED.add(requestId);
        }
    }

    private void stepDone() {
        pending--;
        if (pending == 0) {
            finish();
        }
    }

    private void provision(String requestId, boolean replaceCa) {
        try {
            DeviceSigner signer = LivenessRuntime.get(this).deviceSigner();
            if (!(signer instanceof AndroidKeystoreSigner)) {
                throw new IOException("the built-in device signer is not the Android Keystore signer");
            }
            DeviceProvisioning.Outcome outcome = ((AndroidKeystoreSigner) signer).provision(requestId, replaceCa);
            Log.i(TAG, "Device provisioning request " + requestId + ": " + outcome.kind());
        } catch (Exception e) {
            Log.w(TAG, "Device provisioning request " + requestId + " failed: " + e.getMessage());
            File folder = DeviceProvisioningInitializer.requestFolder(this);
            if (folder != null) {
                try {
                    DeviceProvisioning.writeErrorStatus(folder, requestId, String.valueOf(e.getMessage()),
                            Clock.systemUTC());
                } catch (IOException io) {
                    Log.w(TAG, "Device provisioning status not written: " + io.getMessage());
                }
            }
        }
    }
}
