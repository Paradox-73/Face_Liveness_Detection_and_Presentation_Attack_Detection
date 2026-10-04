/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.interop;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.RandomSource;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.ChallengeRecord;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.ui.LivenessUiState;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeSet;

/**
 * Replays the frames of one {@link ParityCase} through a client's engine, one session, on stream time: a manual clock
 * advances by one frame interval per frame and the session's random source is seeded, so the same engine, decoder
 * and frames give the same decision on every run and on every platform. The flow is the scenario runner's
 * ({@code tools/eval/scenario-runner} {@code AttemptDriver} and {@code Playlist}): frame, {@code tick()}, prompts
 * answered from the case's action clips, and at {@code CAPTURE_READY} a capture of the last streamed frame decoded
 * with the client's decoder and bound to the session.
 *
 * <p>What each client supplies is exactly what differs between the clients: the engine (built from the signed model
 * bundle by the client's own loader, on the client's ONNX Runtime), the policy (resolved through the client's own
 * configuration classes) and the image decoder.
 */
public final class ParityReplay {

    /** Serial number of the simulated L0 face device (the same on both clients, so bindings compare). */
    public static final String DEVICE_SERIAL = "PARITY-FACE-0001";
    /** Subject reference bound into the pass token. */
    public static final String SUBJECT = "parity-subject";
    /** Stream time between the start of the signed capture and its image. */
    public static final long CAPTURE_DELAY_MS = 300;

    private ParityReplay() {
    }

    /**
     * Runs one case and describes the result.
     *
     * @param c              the case
     * @param files          the frames
     * @param engine         the client's engine
     * @param policy         the policy the client resolved for the case's workflow and configuration
     * @param captureDecoder the client's decoder, for the signed capture
     * @return the description that {@link ParityCheck} compares: decision, failure reason, reason codes, method,
     *         engine error, the three scores, challenges, frames streamed and the per-frame PAD scores
     * @throws EngineException if the engine refuses to open a session
     */
    public static Map<String, Object> run(ParityCase c, ParityFiles files, LivenessEngine engine, LivenessPolicy policy,
                                          ImageDecoder captureDecoder) throws EngineException {
        Objects.requireNonNull(engine, "engine");
        ManualClock clock = new ManualClock();
        Listener listener = new Listener();
        List<DiagnosticRecord> diagnostics = new ArrayList<>();
        SessionRequest request = SessionRequest.builder(policy, SUBJECT, device()).attempt(1).clock(clock)
                .random(new SeededRandom(c.seed())).diagnosticSink(diagnostics::add).build();
        Stream stream = new Stream(c, files);
        Map<byte[], ImageBuffer> decoded = new IdentityHashMap<>();
        Random noise = new Random(c.seed());
        int streamed = 0;
        LivenessSession session = engine.open(request, listener);
        try {
            session.start();
            long frameNo = 0;
            byte[] last = null;
            while (!session.state().isTerminal()) {
                if (clock.millis() >= c.maxStreamMs()) {
                    session.cancel();
                    break;
                }
                frameNo++;
                clock.advanceNanos(frameNo * 1_000_000_000L / c.fps() - (frameNo - 1) * 1_000_000_000L / c.fps());
                byte[] f = stream.next(clock.monotonicNanos());
                if (f == null) {
                    session.onEnd(EndReason.CLOSED);
                    break;
                }
                last = f;
                streamed++;
                FaceFrame.Builder frame = c.noise() > 0 ? noisy(decoded, f, captureDecoder, noise, c.noise())
                        : FaceFrame.builder().data(f.clone()).format(PixelFormat.JPEG);
                session.onFrame(frame.monotonicNanos(clock.monotonicNanos()).sequence(frameNo)
                        .sourceId(DEVICE_SERIAL).build());
                session.tick();
                while (!listener.prompts.isEmpty()) {
                    stream.onPrompt(listener.prompts.poll(), clock.monotonicNanos());
                }
                if (session.state() == SessionState.CAPTURE_READY) {
                    captureAndBind(session, last, clock, captureDecoder);
                }
            }
            LivenessResult result = session.result().orElseThrow(
                    () -> new IllegalStateException(c.id() + ": session ended without a result in " + session.state()));
            return describe(result, streamed, diagnostics);
        } finally {
            session.close();
        }
    }

    /**
     * Decodes an encoded frame once with the client's decoder and returns a raw copy with seeded noise: the same
     * shift in all three channels of a pixel, so BGR (desktop) and RGB (Android) buffers get the same change.
     */
    private static FaceFrame.Builder noisy(Map<byte[], ImageBuffer> decoded, byte[] encoded, ImageDecoder decoder,
                                           Random noise, int amplitude) {
        ImageBuffer image = decoded.computeIfAbsent(encoded, e -> {
            try {
                return decoder.decode(e.clone());
            } catch (ImageDecodeException x) {
                throw new IllegalStateException("parity frame could not be decoded", x);
            }
        });
        byte[] pixels = image.bytes().clone();
        for (int i = 0; i + 2 < pixels.length; i += 3) {
            int d = noise.nextInt(2 * amplitude + 1) - amplitude;
            for (int k = i; k < i + 3; k++) {
                pixels[k] = (byte) Math.max(0, Math.min(255, (pixels[k] & 0xff) + d));
            }
        }
        return FaceFrame.builder().data(pixels).format(image.format()).size(image.width(), image.height());
    }

    private static void captureAndBind(LivenessSession session, byte[] last, ManualClock clock, ImageDecoder decoder) {
        LivenessPassToken token = session.passToken().orElse(null);
        session.captureStarted();
        clock.advanceNanos(CAPTURE_DELAY_MS * 1_000_000L);
        ImageBuffer image;
        try {
            image = decoder.decode(last.clone());
        } catch (ImageDecodeException e) {
            session.captureFailed(null);
            return;
        }
        BindingRequest.Builder req = BindingRequest.builder(image, clock.now(), DEVICE_SERIAL);
        if (token != null) {
            req.token(token);
        }
        session.bind(req.build());
    }

    /**
     * Returns the simulated device: an L0 SBI face camera that streams and has no device-side PAD.
     *
     * @return the descriptor
     */
    public static DeviceDescriptor device() {
        return DeviceDescriptor.builder().serialNumber(DEVICE_SERIAL).certification(CertificationLevel.L0)
                .specVersion("0.9.5").transport(DeviceTransport.SBI_HTTP).padCapable(false).streamCapable(true)
                .build();
    }

    private static Map<String, Object> describe(LivenessResult r, int streamed, List<DiagnosticRecord> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", r.decision().name());
        out.put("failureReason", r.failureReason().map(Enum::name).orElse(null));
        List<String> codes = new ArrayList<>();
        for (ReasonCode code : new TreeSet<>(r.reasonCodes())) {
            codes.add(code.name());
        }
        out.put("reasonCodes", codes);
        out.put("method", r.method() == null ? null : r.method().name());
        out.put("engineError", r.engineError().map(Enum::name).orElse(null));
        out.put("passiveScore", r.passiveScore().isPresent() ? r.passiveScore().getAsInt() : null);
        out.put("activePadScore", r.activePadScore().isPresent() ? r.activePadScore().getAsInt() : null);
        out.put("bindingSimilarity", r.bindingSimilarity().isPresent() ? r.bindingSimilarity().getAsInt() : null);
        List<String> challenges = new ArrayList<>();
        for (ChallengeRecord ch : r.challenges()) {
            challenges.add(ch.type().name() + ":" + ch.outcome().name());
        }
        out.put("challenges", challenges);
        out.put("framesStreamed", streamed);
        List<Integer> pad = new ArrayList<>();
        for (DiagnosticRecord d : diagnostics) {
            if (d.padScore().isPresent()) {
                pad.add(d.padScore().getAsInt());
            }
        }
        out.put("padScores", pad);
        return out;
    }

    /** The main stream and the action clips, switched on prompts the way the scenario runner's playlist does. */
    private static final class Stream {
        private final ParityCase c;
        private final ParityFiles files;
        private final List<byte[]> main;
        private int mainPos;
        private int mainDir = 1;
        private boolean ended;
        private List<byte[]> overlay;
        private int overlayPos;
        private List<byte[]> pending;
        private long switchAtNanos;

        Stream(ParityCase c, ParityFiles files) {
            this.c = c;
            this.files = files;
            this.main = files.frames(c.main());
        }

        byte[] next(long nowNanos) {
            if (pending != null && nowNanos >= switchAtNanos) {
                overlay = pending;
                overlayPos = 0;
                pending = null;
            }
            if (overlay != null) {
                if (overlayPos < overlay.size()) {
                    return overlay.get(overlayPos++);
                }
                overlay = null;
            }
            if (ended) {
                return null;
            }
            byte[] f = main.get(mainPos);
            advance();
            return f;
        }

        private void advance() {
            int size = main.size();
            mainPos += mainDir;
            if (mainPos >= 0 && mainPos < size) {
                return;
            }
            switch (c.atEnd()) {
                case LOOP:
                    mainPos = 0;
                    break;
                case PINGPONG:
                    if (size == 1) {
                        mainPos = 0;
                    } else if (mainPos >= size) {
                        mainDir = -1;
                        mainPos = size - 2;
                    } else {
                        mainDir = 1;
                        mainPos = 1;
                    }
                    break;
                case END:
                default:
                    ended = true;
                    break;
            }
        }

        void onPrompt(ChallengeType type, long nowNanos) {
            String pack = null;
            switch (c.onPrompt()) {
                case MATCH:
                    pack = c.actions().get(type);
                    break;
                case MISMATCH:
                    for (Map.Entry<ChallengeType, String> e : c.actions().entrySet()) {
                        if (e.getKey() != type) {
                            pack = e.getValue();
                            break;
                        }
                    }
                    break;
                case IGNORE:
                default:
                    break;
            }
            if (pack != null) {
                pending = files.frames(pack);
                switchAtNanos = nowNanos + c.reactionMs() * 1_000_000L;
            }
        }
    }

    /** Collects the prompts from the session's audit events. */
    private static final class Listener implements LivenessListener {
        final Deque<ChallengeType> prompts = new ArrayDeque<>();

        @Override
        public void onUi(LivenessUiState state) {
            // Not compared.
        }

        @Override
        public void onAudit(LivenessAuditEvent event) {
            if (event.eventId() == AuditEventId.LIVENESS_CHALLENGE_ISSUED) {
                String type = event.attributes().get(AuditAttributes.CHALLENGE_TYPE);
                if (type != null) {
                    prompts.add(ChallengeType.valueOf(type));
                }
            }
        }
    }

    /** Stream time, advanced by the replay only. */
    private static final class ManualClock implements Clock {
        private static final Instant ORIGIN = Instant.parse("2026-01-01T00:00:00Z");
        private long nanos;

        @Override
        public long monotonicNanos() {
            return nanos;
        }

        @Override
        public Instant now() {
            return ORIGIN.plusNanos(nanos);
        }

        void advanceNanos(long delta) {
            nanos += delta;
        }

        long millis() {
            return nanos / 1_000_000L;
        }
    }

    /** {@link java.util.Random} with a fixed seed: the same challenge draws on every platform. */
    private static final class SeededRandom implements RandomSource {
        private final Random random;

        SeededRandom(long seed) {
            this.random = new Random(seed);
        }

        @Override
        public int nextInt(int bound) {
            return random.nextInt(bound);
        }

        @Override
        public void nextBytes(byte[] bytes) {
            random.nextBytes(bytes);
        }
    }
}
