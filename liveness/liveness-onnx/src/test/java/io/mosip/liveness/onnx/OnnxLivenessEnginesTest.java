package io.mosip.liveness.onnx;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.engine.EngineParameter;
import io.mosip.liveness.core.engine.EngineParameters;
import io.mosip.liveness.core.engine.LivenessMetrics;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.vision.AnalyzerFeature;
import io.mosip.liveness.onnx.model.ManifestSignature;
import io.mosip.liveness.onnx.model.ModelManifest;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.model.ModelSources;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine factory: wiring of the real ONNX adapters into the core engine, signed engine parameters, overrides
 * and ownership of the native sessions.
 */
class OnnxLivenessEnginesTest {

    private static KeyPair testKey;

    @BeforeAll
    static void key() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        testKey = g.generateKeyPair();
    }

    private static ModelSource repositoryBundle() {
        return ModelSources.directory(TestSupport.modelsDir());
    }

    @Test
    void buildsAReadyEngineFromTheSignedBundle() throws Exception {
        ModelRegistry registry = TestSupport.registry();
        OnnxLivenessEngine engine = OnnxLivenessEngines.create(repositoryBundle(), new TestImageDecoder());
        try {
            assertTrue(engine.isReady());
            assertEquals("mosip-onnx-default", engine.info().id());
            assertEquals("mosip-onnx-default@1.0.0", engine.info().tag());
            assertEquals(OnnxLivenessEngines.VENDOR, engine.info().vendor());
            assertEquals(registry.bundleVersion(), engine.bundleVersion());
            assertEquals(ids(registry.models()), ids(engine.info().models()));
            assertEquals(EnumSet.allOf(ChallengeType.class), engine.info().supportedChallenges());
            assertEquals(EngineParameters.defaults(), engine.parameters(), "the repository bundle has no engineParams");
            assertSame(engine.parameters(), engine.engine().parameters());
            assertSame(engine.metrics(), engine.engine().metrics());
            assertTrue(engine.toString().contains("mosip-onnx-default@1.0.0"));
        } finally {
            engine.close();
        }
        assertFalse(engine.isReady());
        EngineException e = assertThrows(EngineException.class, () -> engine.open(request(), new Listener()));
        assertEquals(EngineErrorCode.NOT_READY, e.getErrorCode());
        engine.close();
    }

    @Test
    void runsASessionWithTheRealModels() throws Exception {
        LivenessMetrics metrics = new LivenessMetrics();
        try (OnnxLivenessEngine engine = OnnxLivenessEngines.builder(TestSupport.registry())
                .imageDecoder(new TestImageDecoder()).metrics(metrics).build()) {
            Listener listener = new Listener();
            LivenessSession session = engine.open(request(), listener);
            session.start();
            ImageBuffer still = TestSupport.face("mockmds-stream-0.jpeg");
            Random noise = new Random(3);
            for (int i = 0; i < 40 && !session.state().isTerminal() && session.state() != SessionState.CAPTURE_READY;
                 i++) {
                session.onFrame(noisyFrame(still, i, noise));
                session.tick();
            }
            assertTrue(metrics.snapshot().counter(LivenessMetrics.FRAMES_ANALYSED) >= 3,
                    "frames analysed: " + metrics.snapshot().counters());
            assertFalse(listener.ui.isEmpty());
            assertNotEquals(SessionState.IDLE, session.state());
            Optional<LivenessResult> result = session.result();
            LivenessMetrics.Snapshot s = metrics.snapshot();
            System.out.println("[engine] still photo with noise: state=" + session.state() + ", reason="
                    + result.flatMap(LivenessResult::failureReason).map(Enum::name).orElse("-") + ", codes="
                    + result.map(LivenessResult::reasonCodes).orElse(Collections.emptySet()) + ", frames="
                    + s.counter(LivenessMetrics.FRAMES_ANALYSED) + ", avg frame ms=" + s.averageFrameMs());
            if (result.isPresent()) {
                assertNotEquals(Optional.of(FailureReason.ENGINE), result.get().failureReason(),
                        "the models must run inside the session: " + result.get().reasonCodes());
            }
            session.close();
        }
    }

    @Test
    void signedEngineParamsReplaceTheDefaultsAndOverridesComeLast() throws Exception {
        ModelSource source = resigned(Collections.singletonMap("planarity.min_residual", "0.03"));
        try (OnnxLivenessEngine engine = OnnxLivenessEngines.builder(source).trustAnchor(testKey.getPublic())
                .imageDecoder(new TestImageDecoder()).withEmbedder(false).build()) {
            assertEquals(0.03, engine.parameters().get(EngineParameter.PLANARITY_MIN_RESIDUAL), 1e-12);
            assertEquals(EngineParameter.FROZEN_MEAN_ABS_DIFF.defaultValue(),
                    engine.parameters().get(EngineParameter.FROZEN_MEAN_ABS_DIFF), 1e-12);
            assertEquals(4, engine.info().models().size(), "SFace is not loaded");
        }
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("planarity.min_residual", 0.05);
        overrides.put("integrity.frozen_mean_abs_diff", "1.5");
        EngineParameters p = OnnxLivenessEngines.resolveParameters(
                Collections.singletonMap("planarity.min_residual", "0.03"), overrides);
        assertEquals(0.05, p.get(EngineParameter.PLANARITY_MIN_RESIDUAL), 1e-12);
        assertEquals(1.5, p.get(EngineParameter.FROZEN_MEAN_ABS_DIFF), 1e-12);
    }

    @Test
    void invalidSignedParamsFailClosedAsModelIntegrity() throws Exception {
        ModelSource source = resigned(Collections.singletonMap("planarity.no_such_key", "1"));
        EngineException e = assertThrows(EngineException.class, () -> OnnxLivenessEngines.builder(source)
                .trustAnchor(testKey.getPublic()).imageDecoder(new TestImageDecoder()).build());
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, e.getErrorCode());
        assertTrue(e.getErrorCode().isModelIntegrityFailure());

        EngineException range = assertThrows(EngineException.class, () -> OnnxLivenessEngines.resolveParameters(
                Collections.singletonMap("planarity.min_residual", "7"), Collections.<String, Object>emptyMap()));
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, range.getErrorCode());
    }

    @Test
    void invalidOverridesAreAConfigurationError() {
        EngineException e = assertThrows(EngineException.class, () -> OnnxLivenessEngines.builder(TestSupport.registry())
                .imageDecoder(new TestImageDecoder())
                .parameterOverrides(Collections.singletonMap("quality.min_brightness", "bright")).build());
        assertEquals(EngineErrorCode.CONFIG_INVALID, e.getErrorCode());
    }

    @Test
    void theBundleIsVerifiedBeforeAnythingLoads() {
        EngineException tooOld = assertThrows(EngineException.class, () -> OnnxLivenessEngines
                .builder(repositoryBundle()).minimumVersion("2.0.0").imageDecoder(new TestImageDecoder()).build());
        assertEquals(EngineErrorCode.MODEL_VERSION, tooOld.getErrorCode());

        EngineException untrusted = assertThrows(EngineException.class, () -> OnnxLivenessEngines
                .builder(repositoryBundle()).trustAnchor(testKey.getPublic()).imageDecoder(new TestImageDecoder())
                .build());
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, untrusted.getErrorCode());

        ModelSource tampered = path -> {
            byte[] bytes = repositoryBundle().read(path);
            if (path.endsWith(".onnx") && path.startsWith("MiniFASNetV2")) {
                bytes[bytes.length / 2] ^= 1;
            }
            return bytes;
        };
        EngineException modified = assertThrows(EngineException.class,
                () -> OnnxLivenessEngines.create(tampered, new TestImageDecoder()));
        assertEquals(EngineErrorCode.MODEL_INTEGRITY, modified.getErrorCode());
    }

    @Test
    void builderChecksItsArguments() throws Exception {
        ModelRegistry registry = TestSupport.registry();
        assertThrows(IllegalStateException.class,
                () -> OnnxLivenessEngines.builder(registry).trustAnchor(testKey.getPublic()));
        assertThrows(IllegalStateException.class, () -> OnnxLivenessEngines.builder(registry).minimumVersion("1.0.0"));
        assertThrows(NullPointerException.class, () -> OnnxLivenessEngines.builder(registry).build());
        assertThrows(NullPointerException.class, () -> OnnxLivenessEngines.builder((ModelSource) null));
    }

    @Test
    void challengesFollowTheAnalyserFeatures() {
        assertEquals(EnumSet.of(ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT, ChallengeType.LOOK_UP,
                ChallengeType.LOOK_DOWN), OnnxLivenessEngines.supportedChallenges(
                EnumSet.of(AnalyzerFeature.LANDMARKS_5, AnalyzerFeature.HEAD_POSE, AnalyzerFeature.QUALITY)));
        assertEquals(EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE), OnnxLivenessEngines.supportedChallenges(
                EnumSet.of(AnalyzerFeature.EYE_OPENNESS, AnalyzerFeature.MOUTH, AnalyzerFeature.GAZE)));
        assertTrue(OnnxLivenessEngines.supportedChallenges(EnumSet.noneOf(AnalyzerFeature.class)).isEmpty());
    }

    /** The repository bundle with its manifest rewritten to carry {@code engineParams}, signed by the test key. */
    private static ModelSource resigned(Map<String, String> engineParams) throws Exception {
        ModelSource repo = repositoryBundle();
        ModelManifest original = ModelManifest.parse(repo.read(ModelRegistry.MANIFEST_FILE));
        byte[] manifest = ModelManifest.of(original.bundle(), original.version().toString(), original.models(),
                engineParams).toJson().getBytes(StandardCharsets.UTF_8);
        byte[] signature = ManifestSignature.sign(manifest, testKey.getPrivate(), testKey.getPublic())
                .getBytes(StandardCharsets.US_ASCII);
        return path -> {
            if (ModelRegistry.MANIFEST_FILE.equals(path)) {
                return manifest.clone();
            }
            if (ModelRegistry.SIGNATURE_FILE.equals(path)) {
                return signature.clone();
            }
            return repo.read(path);
        };
    }

    private static List<String> ids(List<ModelInfo> models) {
        List<String> ids = new ArrayList<>();
        for (ModelInfo m : models) {
            ids.add(m.id() + "@" + m.version() + "#" + m.sha256());
        }
        return ids;
    }

    private static SessionRequest request() {
        DeviceDescriptor device = DeviceDescriptor.builder().serialNumber("MOCK-FACE-0001")
                .certification(CertificationLevel.L0).specVersion("0.9.5").transport(DeviceTransport.SBI_HTTP)
                .streamCapable(true).build();
        return SessionRequest.builder(LivenessPolicy.defaults(Workflow.REGISTRATION), "10001100010001220260928100000",
                device).build();
    }

    /** The still image with a little noise, so frames differ as camera frames do; the sequence and time advance. */
    private static FaceFrame noisyFrame(ImageBuffer still, int index, Random noise) {
        byte[] pixels = still.bytes().clone();
        for (int i = 0; i < pixels.length; i += 7) {
            pixels[i] = (byte) Math.max(0, Math.min(255, (pixels[i] & 0xff) + noise.nextInt(7) - 3));
        }
        return FaceFrame.builder().data(pixels).format(PixelFormat.BGR888).size(still.width(), still.height())
                .monotonicNanos(System.nanoTime()).sequence(index).sourceId("MOCK-FACE-0001").build();
    }

    /** Records UI states and audit events. */
    private static final class Listener implements LivenessListener {

        final List<LivenessUiState> ui = new ArrayList<>();
        final List<LivenessAuditEvent> audits = new ArrayList<>();

        @Override
        public void onUi(LivenessUiState state) {
            ui.add(state);
        }

        @Override
        public void onAudit(LivenessAuditEvent event) {
            audits.add(event);
        }
    }
}
