/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.interop.ParityCase;
import io.mosip.liveness.core.interop.ParityCheck;
import io.mosip.liveness.core.interop.ParityFiles;
import io.mosip.liveness.core.interop.ParityReplay;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.livenessmanager.engine.EngineLoader;
import io.mosip.registration.livenessmanager.image.AndroidImageDecoder;

/**
 * Cross-client parity on a device or emulator: the committed cases of {@code eval/interop/liveness-parity.json}
 * (packed into the test APK's assets with the MockMDS images, see build.gradle) through the engine on
 * onnxruntime-android's own native library, checked against the desktop recording. The unit test
 * {@code LivenessParityTest} runs all cases on the build machine's JVM. Run with
 * {@code gradlew :livenessmanager:connectedDebugAndroidTest} while an emulator or device is attached.
 */
@RunWith(AndroidJUnit4.class)
public class LivenessParityDeviceTest {

    private static final String TAG = "LivenessParity";

    @Test
    public void committedCasesMatchTheDesktopRecording() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AssetManager testAssets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        JSONObject golden = new JSONObject(new String(read(testAssets, "liveness-parity.json"),
                StandardCharsets.UTF_8));
        int tolerance = golden.getInt("scoreTolerance");
        ParityFiles files = ParityFiles.committedOnly(ref -> read(testAssets, ref));
        AndroidImageDecoder decoder = new AndroidImageDecoder();
        EngineLoader engines = EngineLoader.onnx(target.getAssets(), ConfigSource.of(Collections.emptyMap()),
                decoder);
        List<String> failures = new ArrayList<>();
        int ran = 0;
        try {
            LivenessEngine engine = engines.await(TimeUnit.SECONDS.toMillis(90));
            JSONArray cases = golden.getJSONArray("cases");
            for (int i = 0; i < cases.length(); i++) {
                ParityCase c = ParityCase.fromJson(toMap(cases.getJSONObject(i)));
                if (!files.available(c)) {
                    continue;
                }
                LivenessPolicy policy = new PolicyResolver(ConfigSource.of(c.config())).resolve(c.workflow())
                        .policy();
                Map<String, Object> actual = ParityReplay.run(c, files, engine, policy, decoder);
                ran++;
                ParityCheck check = ParityCheck.compare(c.expected(), actual, tolerance);
                Log.i(TAG, c.id() + ": " + actual.get("decision") + " " + actual.get("reasonCodes")
                        + ", max score difference " + check.maxScoreDifference());
                for (String m : check.mismatches()) {
                    failures.add(c.id() + ": " + m);
                }
            }
        } finally {
            engines.close();
        }
        Log.i(TAG, ran + " committed cases run");
        assertTrue("the committed cases run", ran >= 1);
        if (!failures.isEmpty()) {
            fail("results on this device differ from the desktop recording:\n" + String.join("\n", failures));
        }
    }

    private static byte[] read(AssetManager assets, String name) throws IOException {
        try (InputStream in = assets.open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static Map<String, Object> toMap(JSONObject o) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            m.put(k, toJava(o.get(k)));
        }
        return m;
    }

    private static Object toJava(Object v) throws Exception {
        if (v == JSONObject.NULL) {
            return null;
        }
        if (v instanceof JSONObject) {
            return toMap((JSONObject) v);
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<Object> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                l.add(toJava(a.get(i)));
            }
            return l;
        }
        return v;
    }
}
