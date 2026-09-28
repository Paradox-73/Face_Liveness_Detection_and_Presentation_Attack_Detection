package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.vision.DetectedFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Drives one session with synthetic frames on a fake clock: 20 fps GRAY8 noise frames (so frames are never
 * identical unless a test wants them to be), scripted model outputs and a seeded random source.
 */
final class SessionHarness {

    static final String SERIAL = "MOCK-FACE-0001";
    static final String SUBJECT = "10001100010001220260928100000";
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    final FakeClock clock = new FakeClock();
    final ScriptedModels.Analyzer analyzer = new ScriptedModels.Analyzer();
    final ScriptedModels.Pad pad = new ScriptedModels.Pad();
    final ScriptedModels.Embedder embedder = new ScriptedModels.Embedder();
    final RecordingListener listener = new RecordingListener();
    final List<DiagnosticRecord> diagnostics = new ArrayList<>();
    final LivenessMetrics metrics = new LivenessMetrics();
    LivenessPolicy policy;
    DeviceDescriptor device = device(false, true);
    EngineParameters params = EngineParameters.defaults();
    boolean useEmbedder = true;
    long seed = 42;
    int attempt = 1;
    int frameIntervalMs = 50;
    DefaultLivenessEngine engine;
    LivenessSession session;
    private long sequence;
    private final Random pixels = new Random(7);

    SessionHarness(LivenessPolicy policy) {
        this.policy = policy;
        analyzer.clock = clock;
    }

    static DeviceDescriptor device(boolean padCapable, boolean streamCapable) {
        return DeviceDescriptor.builder().serialNumber(SERIAL).certification(CertificationLevel.L0).specVersion("0.9.5")
                .transport(DeviceTransport.SBI_HTTP).padCapable(padCapable).streamCapable(streamCapable).build();
    }

    static EngineInfo engineInfo() {
        return EngineInfo.builder("mosip-onnx-default", "1.0.0").model(ModelInfo.of("minifasnet_v2", "1.0.0", SHA))
                .build();
    }

    SessionHarness open() {
        engine = DefaultLivenessEngine.builder(engineInfo()).faceAnalyzer(analyzer).passivePad(pad)
                .faceEmbedder(useEmbedder ? embedder : null).imageDecoder(new ScriptedModels.Decoder())
                .parameters(params).metrics(metrics).build();
        SessionRequest request = SessionRequest.builder(policy, SUBJECT, device).attempt(attempt).clock(clock)
                .random(new SeededRandom(seed)).diagnosticSink(diagnostics::add).build();
        try {
            session = engine.open(request, listener);
        } catch (EngineException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    SessionHarness start() {
        if (session == null) {
            open();
        }
        session.start();
        return this;
    }

    SessionState state() {
        return session.state();
    }

    LivenessResult result() {
        return session.result().get();
    }

    void frame(TestFaces face) {
        frameWith(face.build());
    }

    void frameWith(DetectedFace... faces) {
        clock.advanceMs(frameIntervalMs);
        analyzer.faces(faces);
        session.onFrame(grayFrame(noise()));
    }

    void frames(int n, TestFaces face) {
        for (int i = 0; i < n && !state().isTerminal(); i++) {
            frame(face);
        }
    }

    byte[] noise() {
        byte[] data = new byte[64 * 48];
        pixels.nextBytes(data);
        return data;
    }

    FaceFrame grayFrame(byte[] data) {
        return FaceFrame.builder().data(data).format(PixelFormat.GRAY8).size(64, 48)
                .monotonicNanos(clock.monotonicNanos()).sequence(sequence++).sourceId(SERIAL).build();
    }

    void tick(long ms) {
        clock.advanceMs(ms);
        session.tick();
    }

    /** Feeds a face until the passive phase has decided. */
    void runPassive(TestFaces face) {
        for (int i = 0; i < 200 && isPassivePhase(); i++) {
            frame(face);
        }
    }

    private boolean isPassivePhase() {
        SessionState s = state();
        return s == SessionState.OPENING || s == SessionState.ACQUIRING || s == SessionState.PASSIVE;
    }

    /**
     * Feeds neutral frames until a challenge is prompted.
     *
     * @return the prompted challenge, or {@code null} if none was prompted
     */
    ChallengeType awaitPrompt() {
        for (int i = 0; i < 200 && state() == SessionState.CHALLENGE_WAIT; i++) {
            frame(TestFaces.neutral());
        }
        if (state() != SessionState.CHALLENGE_OBSERVE) {
            return null;
        }
        String type = listener.lastAudit(AuditEventId.LIVENESS_CHALLENGE_ISSUED).attributes()
                .get(AuditAttributes.CHALLENGE_TYPE);
        return ChallengeType.valueOf(type);
    }

    /** Performs a challenge: 400 ms still, then the action for 500 ms, then neutral until the next step. */
    void perform(ChallengeType type, boolean planar) {
        frames(8, TestFaces.neutral());
        if (type == ChallengeType.BLINK) {
            frames(3, TestFaces.acting(type));
            frames(4, TestFaces.neutral());
        } else {
            TestFaces acting = TestFaces.acting(type);
            frames(10, planar ? acting.planar() : acting);
        }
        for (int i = 0; i < 40 && state() == SessionState.CHALLENGE_PASSED; i++) {
            frame(TestFaces.neutral());
        }
    }

    /** Runs every challenge of the active phase correctly. */
    List<ChallengeType> runActive(boolean planar) {
        List<ChallengeType> done = new ArrayList<>();
        while (state() == SessionState.CHALLENGE_WAIT) {
            ChallengeType t = awaitPrompt();
            if (t == null) {
                break;
            }
            perform(t, planar);
            done.add(t);
        }
        return done;
    }

    ImageBuffer captureImage() {
        byte[] px = new byte[32 * 32];
        pixels.nextBytes(px);
        return ImageBuffer.ofBytes(32, 32, PixelFormat.GRAY8, px);
    }

    BindingRequest.Builder bindingRequest(LivenessPassToken token) {
        return BindingRequest.builder(captureImage(), clock.now(), SERIAL).token(token);
    }

    /** Starts the capture and binds a matching capture of the same neutral face. */
    BindingVerdict captureAndBind() {
        LivenessPassToken token = session.passToken().get();
        session.captureStarted();
        clock.advanceMs(300);
        analyzer.faces(TestFaces.neutral().build());
        return session.bind(bindingRequest(token).build());
    }
}
