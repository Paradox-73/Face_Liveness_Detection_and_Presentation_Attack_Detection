package io.mosip.liveness.eval;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.ChallengeOutcome;
import io.mosip.liveness.core.api.ChallengeRecord;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/**
 * A stand-in engine for runner tests: no models, and every frame is the text {@code "<pack>#<index>"}. After
 * {@link #promptAfter} frames it prompts {@link #challenge}; it reaches {@code CAPTURE_READY} once
 * {@link #framesToPass} frames of the pack {@link #answerPack} arrive, and fails with {@code CHALLENGE_TIMEOUT}
 * after {@link #timeoutFrames} frames without them. Three frames of a pack whose name starts with {@code blank}
 * fail the session with {@code QUALITY / NO_FACE}.
 */
final class ScriptedEngine implements LivenessEngine {

    ChallengeType challenge = ChallengeType.BLINK;
    String answerPack = "blink";
    int promptAfter = 3;
    int framesToPass = 2;
    int timeoutFrames = 20;
    int sessionsOpened;
    private final StageTimer timer;

    ScriptedEngine(StageTimer timer) {
        this.timer = timer;
    }

    static EngineInfo engineInfo() {
        return EngineInfo.builder("scripted", "1").vendor("test").build();
    }

    @Override
    public EngineInfo info() {
        return engineInfo();
    }

    @Override
    public LivenessSession open(SessionRequest request, LivenessListener listener) {
        sessionsOpened++;
        return new Session(request, listener);
    }

    /**
     * Returns a decoder for the simulated signed capture that turns any bytes into a 1x1 image.
     *
     * @return the decoder
     */
    static ImageDecoder decoder() {
        return new ImageDecoder() {
            @Override
            public ImageBuffer decode(FaceFrame frame) {
                return decode(frame.data());
            }

            @Override
            public ImageBuffer decode(byte[] encoded) {
                return ImageBuffer.ofBytes(1, 1, PixelFormat.BGR888, new byte[3]);
            }
        };
    }

    /**
     * Returns a pack whose frames are the text {@code "<id>#<index>"}.
     *
     * @param id   pack id
     * @param size number of frames
     * @return the pack
     */
    static FrameSequence pack(String id, int size) {
        return new FrameSequence() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int size() {
                return size;
            }

            @Override
            public byte[] frame(int index) {
                return (id + "#" + index).getBytes(StandardCharsets.US_ASCII);
            }
        };
    }

    private final class Session implements LivenessSession {
        private final SessionRequest request;
        private final LivenessListener listener;
        private final Instant started;
        private final String id;
        private SessionState state = SessionState.IDLE;
        private int frames;
        private int answered;
        private int blank;
        private long promptMs = -1;
        private LivenessResult result;

        Session(SessionRequest request, LivenessListener listener) {
            this.request = request;
            this.listener = listener;
            this.started = request.clock().now();
            this.id = "s" + sessionsOpened;
        }

        @Override
        public String sessionId() {
            return id;
        }

        @Override
        public SessionState state() {
            return state;
        }

        @Override
        public void start() {
            state = SessionState.PASSIVE;
        }

        @Override
        public void onFrame(FaceFrame frame) {
            if (state.isTerminal() || state == SessionState.CAPTURE_READY) {
                return;
            }
            timer.add(StageTimer.Stage.DECODE, 1_000);
            frames++;
            String pack = new String(frame.data(), StandardCharsets.US_ASCII).split("#")[0];
            if (pack.startsWith("blank") && ++blank >= 3) {
                finish(builder().fail(FailureReason.QUALITY).reason(ReasonCode.NO_FACE).method(LivenessMethod.NONE));
                return;
            }
            if (promptMs < 0 && frames >= promptAfter) {
                promptMs = now();
                state = SessionState.CHALLENGE_OBSERVE;
                listener.onAudit(LivenessAuditEvent.builder(AuditEventId.LIVENESS_CHALLENGE_ISSUED,
                        request.workflow(), id, request.clock().now())
                        .put(AuditAttributes.CHALLENGE_TYPE, challenge.name()).build());
                return;
            }
            if (promptMs >= 0) {
                if (pack.equals(answerPack) && ++answered >= framesToPass) {
                    state = SessionState.CAPTURE_READY;
                } else if (frames - promptAfter >= timeoutFrames) {
                    finish(builder().fail(FailureReason.ACTIVE).reason(ReasonCode.CHALLENGE_TIMEOUT)
                            .method(LivenessMethod.ACTIVE)
                            .challenge(ChallengeRecord.failed(challenge, 0, ChallengeOutcome.TIMEOUT, now() - promptMs)));
                }
            }
        }

        @Override
        public void onError(DeviceError error) {
            finish(builder().fail(FailureReason.DEVICE).deviceError(error.code()).method(LivenessMethod.NONE));
        }

        @Override
        public void onEnd(EndReason reason) {
            if (!state.isTerminal()) {
                finish(builder().fail(FailureReason.DEVICE).deviceError(DeviceErrorCode.DISCONNECTED)
                        .method(LivenessMethod.NONE));
            }
        }

        @Override
        public void tick() {
            // nothing time-based to do
        }

        @Override
        public Optional<LivenessPassToken> passToken() {
            return Optional.empty();
        }

        @Override
        public Optional<FaceFrame> captureFrame() {
            return Optional.empty();
        }

        @Override
        public void captureStarted() {
            state = SessionState.CAPTURING;
        }

        @Override
        public void captureFailed(DeviceError error) {
            finish(builder().fail(FailureReason.DEVICE).method(LivenessMethod.ACTIVE));
        }

        @Override
        public BindingVerdict bind(BindingRequest bindingRequest) {
            finish(builder().pass().method(LivenessMethod.ACTIVE).passiveScore(70)
                    .challenge(ChallengeRecord.passed(challenge, 0, 200, 300, Math.max(500, now() - promptMs))));
            return BindingVerdict.passed().build();
        }

        @Override
        public Optional<LivenessResult> result() {
            return Optional.ofNullable(result);
        }

        @Override
        public void cancel() {
            if (!state.isTerminal()) {
                finish(builder().fail(FailureReason.CANCELLED).method(LivenessMethod.NONE));
            }
        }

        @Override
        public void close() {
            // nothing to release
        }

        private long now() {
            return request.clock().monotonicNanos() / 1_000_000L;
        }

        private LivenessResult.Builder builder() {
            return LivenessResult.builder(id, request.workflow(), engineInfo(), request.device())
                    .attempt(request.attempt());
        }

        private void finish(LivenessResult.Builder b) {
            result = b.period(started, request.clock().now()).timings(0, 0, now()).build();
            if (result.isPass()) {
                state = SessionState.PASSED;
            } else {
                state = result.failureReason().orElse(FailureReason.ENGINE) == FailureReason.CANCELLED
                        ? SessionState.CANCELLED : SessionState.FAILED;
            }
        }
    }
}
