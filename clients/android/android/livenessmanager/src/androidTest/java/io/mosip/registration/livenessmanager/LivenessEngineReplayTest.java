/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.onnx.OnnxModelSet;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;
import io.mosip.registration.livenessmanager.device.BuiltInCameraL0Device;
import io.mosip.registration.livenessmanager.device.DeviceSigner;
import io.mosip.registration.livenessmanager.device.ReplayFrameSource;
import io.mosip.registration.livenessmanager.engine.AssetModelSource;
import io.mosip.registration.livenessmanager.engine.EngineLoader;
import io.mosip.registration.livenessmanager.image.AndroidImageDecoder;
import io.mosip.registration.livenessmanager.session.LivenessRunner;

/**
 * Runs the real engine (ONNX Runtime and the signed model bundle from the APK assets) on the device or emulator,
 * over the bundled replay frames {@code liveness-replay/mockmds-still} (the MockMDS stream image, MPL-2.0).
 *
 * <p>A still image replayed as a stream is the "injected still" attack of the evaluation suite: the session must
 * end with a failure, never with a pass and never with an engine error. Run with
 * {@code gradlew :livenessmanager:connectedDebugAndroidTest} while an emulator or device is attached.
 */
@RunWith(AndroidJUnit4.class)
public class LivenessEngineReplayTest {

    private static final String TAG = "LivenessTest";
    private static final String REPLAY_PACK = "liveness-replay/mockmds-still";
    private static final long SESSION_TIMEOUT_S = 120;

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final AndroidImageDecoder decoder = new AndroidImageDecoder();

    @Test
    public void bundledModelsVerifyAgainstTheSignedManifest() throws Exception {
        ModelRegistry registry = ModelRegistry.open(new AssetModelSource(context.getAssets()));
        registry.verifyAll();
        assertEquals(5, registry.models().size());
        assertTrue(registry.contains("yunet"));
        assertTrue(registry.contains("minifasnet-v2"));
    }

    @Test
    public void detectorFindsOneFaceInTheReplayFrame() throws Exception {
        List<byte[]> frames = ReplayFrameSource.readAssets(context.getAssets(), REPLAY_PACK);
        ImageBuffer image = decoder.decode(FaceFrame.builder().data(frames.get(0)).format(PixelFormat.JPEG)
                .size(0, 0).monotonicNanos(0).sequence(1).sourceId("test").build());
        ModelRegistry registry = ModelRegistry.open(new AssetModelSource(context.getAssets()));
        try (OnnxModelSet models = OnnxModelSet.load(registry, OnnxRuntimeOptions.defaults())) {
            FaceObservation observation = models.detectorOnlyAnalyzer().analyze(image);
            Log.i(TAG, "replay frame " + image.width() + "x" + image.height() + ": " + observation);
            assertEquals(1, observation.faceCount());
        } finally {
            image.wipe();
        }
    }

    @Test
    public void injectedStillIsRejectedByTheRealEngine() throws Exception {
        Map<String, String> values = new HashMap<>();
        values.put(LivenessConfigKeys.MAX_RETRIES, "1");
        ConfigSource config = ConfigSource.of(values);
        LivenessPolicy policy = new PolicyResolver(config).resolve(Workflow.REGISTRATION).policy();
        assertTrue(policy.isEnabled());

        EngineLoader engines = EngineLoader.onnx(context.getAssets(), config, decoder);
        LivenessEngine engine = engines.await(TimeUnit.SECONDS.toMillis(90));
        assertTrue(engine.isReady());
        Log.i(TAG, "engine " + engine.info().tag());

        Clock clock = Clock.system();
        List<byte[]> frames = ReplayFrameSource.readAssets(context.getAssets(), REPLAY_PACK);
        BuiltInCameraL0Device device = new BuiltInCameraL0Device("TEST-SERIAL", DevicePurpose.REGISTRATION,
                request -> new ReplayFrameSource(frames, 15, true, clock, "TEST-SERIAL", null), () -> true, decoder,
                new InMemorySigner(), clock, "Developer");

        List<LivenessUiState> states = new CopyOnWriteArrayList<>();
        List<LivenessAuditEvent> audits = new CopyOnWriteArrayList<>();
        List<LivenessCaptureResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        LivenessRunner runner = new LivenessRunner(policy, "test-subject", engines, device, decoder, audits::add,
                DiagnosticSink.noop(), clock, null, new LivenessRunner.Listener() {
            @Override
            public void onUiState(LivenessUiState state) {
                states.add(state);
            }

            @Override
            public void onAttemptEnded(LivenessCaptureResult result) {
                results.add(result);
                ended.countDown();
            }
        });
        try {
            runner.start();
            assertTrue("session did not end within " + SESSION_TIMEOUT_S + " s",
                    ended.await(SESSION_TIMEOUT_S, TimeUnit.SECONDS));
        } finally {
            runner.close();
            engines.close();
        }

        LivenessCaptureResult result = results.get(0);
        Log.i(TAG, "result " + result + " reasons=" + result.reasonCodes() + " audits=" + auditIds(audits));
        assertFalse("a replayed still image must not pass", result.isPass());
        assertNotEquals("the engine ran the session", Decision.ERROR, result.decision());
        assertNotEquals(Decision.CANCELLED, result.decision());
        assertNotNull(result.failureReason());
        assertFalse("reason codes explain the failure", result.reasonCodes().isEmpty());
        assertEquals(1, result.attemptsUsed());
        List<String> ids = auditIds(audits);
        assertEquals("first audit is LV01", AuditEventId.LIVENESS_SESSION_STARTED.id(), ids.get(0));
        assertEquals("LV11 once after the only attempt", 1,
                Collections.frequency(ids, AuditEventId.LIVENESS_MAX_RETRIES.id()));
        assertFalse(states.isEmpty());
        UiPhase last = states.get(states.size() - 1).phase();
        assertTrue("session ended in a failure state: " + last, last == UiPhase.FAILED || last == UiPhase.MAX_ATTEMPTS);
    }

    private static List<String> auditIds(List<LivenessAuditEvent> events) {
        List<String> ids = new ArrayList<>();
        for (LivenessAuditEvent e : events) {
            ids.add(e.eventId().id());
        }
        return ids;
    }

    /** An RSA key that lives only in this test process; the app uses the Android Keystore instead. */
    private static final class InMemorySigner implements DeviceSigner {

        private final KeyPair keys;

        InMemorySigner() throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            keys = g.generateKeyPair();
        }

        @Override
        public byte[] sign(byte[] data) throws java.security.GeneralSecurityException {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(keys.getPrivate());
            s.update(data);
            return s.sign();
        }

        @Override
        public List<X509Certificate> certificateChain() {
            return Collections.emptyList();
        }

        @Override
        public boolean isProvisioned() {
            return false;
        }
    }
}
