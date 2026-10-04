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

import androidx.test.core.app.ApplicationProvider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.interop.ParityCase;
import io.mosip.liveness.core.interop.ParityCheck;
import io.mosip.liveness.core.interop.ParityFiles;
import io.mosip.liveness.core.interop.ParityReplay;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.livenessmanager.config.GlobalParamConfigSource;
import io.mosip.registration.livenessmanager.engine.EngineLoader;
import io.mosip.registration.livenessmanager.image.AndroidImageDecoder;

/**
 * Cross-client parity (bonus B4): replays the cases of {@code eval/interop/liveness-parity.json}, which the desktop
 * client recorded, through this client's own engine loader ({@link EngineLoader}, models from the APK assets), its
 * configuration source ({@link GlobalParamConfigSource}) and its image decoder ({@link AndroidImageDecoder} on
 * Android's own bitmap decoder, through Robolectric's native graphics), and checks that decisions, reason codes and
 * challenges are identical and scores within the stated tolerance.
 *
 * <p>The test runs on the build machine's JVM, so ONNX Runtime is the JVM package of the same version as
 * onnxruntime-android (see build.gradle). Committed cases always run; frame-pack cases run where
 * {@code run/frame-packs} is present (or {@code LIVENESS_FRAME_PACKS} names it).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LivenessParityTest {

    @Test
    public void androidDecisionsMatchTheDesktopRecording() throws Exception {
        ParityFiles files = ParityFiles.locate();
        Map<String, Object> golden = new ObjectMapper().readValue(files.golden(),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        int tolerance = ((Number) golden.get("scoreTolerance")).intValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) golden.get("cases");

        Context context = ApplicationProvider.getApplicationContext();
        AndroidImageDecoder decoder = new AndroidImageDecoder();
        EngineLoader engines = EngineLoader.onnx(context.getAssets(), config(new LinkedHashMap<>()), decoder);
        List<String> failures = new ArrayList<>();
        int committed = 0;
        int ran = 0;
        try {
            LivenessEngine engine = engines.await(TimeUnit.MINUTES.toMillis(2));
            for (Map<String, Object> json : cases) {
                ParityCase c = ParityCase.fromJson(json);
                if (!files.available(c)) {
                    System.out.println("parity " + c.id() + ": skipped, frame packs not found in " + files.packs());
                    continue;
                }
                if (c.expected() == null) {
                    failures.add(c.id() + ": no expected result recorded");
                    continue;
                }
                LivenessPolicy policy = new PolicyResolver(config(c.config())).resolve(c.workflow()).policy();
                Map<String, Object> actual = ParityReplay.run(c, files, engine, policy, decoder);
                ran++;
                committed += c.isCommitted() ? 1 : 0;
                ParityCheck check = ParityCheck.compare(c.expected(), actual, tolerance);
                System.out.println("parity " + c.id() + ": " + actual.get("decision") + " "
                        + actual.get("reasonCodes") + ", max score difference " + check.maxScoreDifference()
                        + (check.matches() ? "" : ", MISMATCH"));
                for (String m : check.mismatches()) {
                    failures.add(c.id() + ": " + m);
                }
            }
        } finally {
            engines.close();
        }
        assertTrue("the committed cases always run", committed >= 1);
        System.out.println("parity android: " + ran + " of " + cases.size() + " cases run");
        if (!failures.isEmpty()) {
            fail("Android results differ from " + ParityFiles.GOLDEN_PATH + ":\n" + String.join("\n", failures));
        }
    }

    /** The case's configuration as synced global parameters, read the way the app reads them. */
    private static GlobalParamConfigSource config(Map<String, String> values) {
        return new GlobalParamConfigSource(new GlobalParamConfigSource.Lookup() {
            @Override
            public String merged(String key) {
                return values.get(key);
            }

            @Override
            public String synced(String key) {
                return values.get(key);
            }
        });
    }
}
