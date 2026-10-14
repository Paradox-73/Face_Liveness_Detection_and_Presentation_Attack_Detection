/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.ChallengeOutcome;
import io.mosip.liveness.core.api.ChallengeRecord;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.NoStreamPolicy;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DevicePadResult;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.stream.FrameRateMeter;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.FacePoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The liveness session state machine of docs/design/engine.md section 3, driven by stream frames and clock ticks.
 *
 * <p>Confined to one owning thread (the thread that calls {@link #start()}); {@link #sessionId()},
 * {@link #state()} and {@link #cancel()} are safe from any thread. Every step is deterministic given the
 * session's clock, random source and model outputs, which the scenario tests rely on.
 */
final class DefaultLivenessSession implements LivenessSession {

    private final SessionRequest request;
    private final LivenessPolicy policy;
    private final DefaultLivenessEngine engine;
    private final EngineParameters params;
    private final Clock clock;
    private final String sessionId;
    private final LivenessMetrics metrics;
    private final SessionReporter reporter;
    private final AuditPayloadBuilder audit;
    private final DiagnosticLog diagnostics;
    private final LivenessUiStateMapper ui;
    private final FrameIntegrityChecker integrity;
    private final QualityGate gate;
    private final FaceTracker tracker;
    private final IdentityTracker identity;
    private final CaptureBinder binder;
    private final PassivePadAggregator passiveScores;
    private final PassivePadAggregator activeRolling;
    private final PassivePadAggregator activeAll;
    private final FrameRateMeter analysisRate = new FrameRateMeter();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final Set<ReasonCode> qualityProblems = new LinkedHashSet<>();
    private final List<Double> passiveBrightness = new ArrayList<>();
    private final Set<ReasonCode> flags = EnumSet.noneOf(ReasonCode.class);

    private volatile SessionState state = SessionState.IDLE;
    private volatile Thread owner;
    private int depth;
    private ActiveStage active;
    private final PhaseTimings timings = new PhaseTimings();
    private final CaptureCandidateSelector candidate;
    private long acquireStartNanos = -1;
    private long lastArrivalNanos;
    private long lastProcessedFrameNanos = Long.MIN_VALUE;
    private Instant startedAt;
    private Instant captureStartedAt;
    private int consecutiveGood;
    private int goodFrames;
    private int slowInferences;
    private int activeFrames;
    private int multiFaceRun;
    private double lastIed = Double.NaN;
    private ReasonCode lastProblem;
    private DetectedFace lastFace;
    private LivenessMethod method = LivenessMethod.NONE;
    private int passiveScore = -1;
    private int activePadScore = -1;
    private int bindingSimilarity = -1;
    private double lastPlanarity = Double.NaN;
    private LivenessPassToken token;
    private boolean tokenConsumed;
    private DevicePadResult devicePad;
    private DeviceErrorCode deviceError;
    private EngineErrorCode engineError;
    private LivenessResult result;
    private boolean closed;

    DefaultLivenessSession(DefaultLivenessEngine engine, SessionRequest request, LivenessListener listener) {
        this.engine = engine;
        this.request = request;
        this.policy = request.policy();
        this.params = engine.parameters();
        this.candidate = new CaptureCandidateSelector(params);
        this.clock = request.clock();
        this.sessionId = SessionIds.sessionId(request.random());
        this.metrics = policy.isMetricsEnabled() ? engine.metrics() : null;
        this.reporter = new SessionReporter(listener);
        this.audit = new AuditPayloadBuilder(policy.workflow(), sessionId, clock);
        this.diagnostics = new DiagnosticLog(request.diagnosticSink(), policy.isDiagnosticEnabled(), sessionId,
                engine.info());
        this.ui = new LivenessUiStateMapper(policy, request.attempt(), request.priorSuspectedAttack());
        this.integrity = new FrameIntegrityChecker(policy, params);
        this.gate = new QualityGate(policy, params);
        this.tracker = new FaceTracker(params);
        this.identity = new IdentityTracker(engine.embedder(), params.getInt(EngineParameter.REFERENCE_FRAMES));
        this.binder = new CaptureBinder(engine.analyzer(), engine.pad(), identity, policy, params);
        double trim = params.get(EngineParameter.PASSIVE_TRIM_FRACTION);
        this.passiveScores = new PassivePadAggregator(policy.passiveMinFrames(), trim);
        this.activeRolling = new PassivePadAggregator(policy.passiveMinFrames(), trim);
        this.activeAll = new PassivePadAggregator(1_000, trim);
    }

    // ------------------------------------------------------------------ public contract

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public SessionState state() {
        return state;
    }

    @Override
    public void start() {
        if (state != SessionState.IDLE) {
            return;
        }
        owner = Thread.currentThread();
        depth++;
        try {
            begin();
        } finally {
            exit();
        }
    }

    private void begin() {
        timings.started(clock.monotonicNanos());
        startedAt = clock.now();
        lastArrivalNanos = timings.start();
        reporter.audit(audit.sessionStarted(request.attempt(), request.device(), engine.info()));
        if (metrics != null) {
            metrics.sessionStarted(request.attempt());
        }
        if (cancelRequested.get()) {
            return;
        }
        if (!policy.isEnabled()) {
            method = LivenessMethod.NONE;
            timings.passiveEnded(timings.start());
            enterCaptureReady();
        } else if (!request.device().isStreamCapable()) {
            if (policy.noStreamPolicy() == NoStreamPolicy.CAPTURE_ONLY) {
                method = LivenessMethod.CAPTURE_ONLY;
                timings.passiveEnded(timings.start());
                enterCaptureReady();
            } else {
                failDevice(DeviceErrorCode.NO_STREAM);
            }
        } else {
            // Passive detection always runs first; escalation changes the method later.
            method = LivenessMethod.PASSIVE;
            transition(SessionState.OPENING);
            reporter.ui(ui.starting());
        }
    }

    @Override
    public void onFrame(FaceFrame frame) {
        if (frame == null || !enter()) {
            return;
        }
        try {
            if (state.ordinal() < SessionState.OPENING.ordinal()
                    || state.ordinal() >= SessionState.CAPTURE_READY.ordinal()) {
                return;
            }
            long now = clock.monotonicNanos();
            lastArrivalNanos = now;
            ReasonCode r = integrity.onArrival(frame);
            if (r != null) {
                failIntegrity(r);
                return;
            }
            if (!isSampledOut(frame)) {
                processFrame(frame);
            }
            if (!state.isTerminal()) {
                checkTimers(clock.monotonicNanos());
            }
        } finally {
            exit();
        }
    }

    @Override
    public void onError(DeviceError error) {
        if (error == null || !enter()) {
            return;
        }
        try {
            if (state != SessionState.IDLE && state.ordinal() < SessionState.CAPTURE_READY.ordinal()) {
                failDevice(error.code());
            }
        } finally {
            exit();
        }
    }

    @Override
    public void onEnd(EndReason reason) {
        if (reason == null || !enter()) {
            return;
        }
        try {
            // After CAPTURE_READY the end is normal: authentication flows close the stream before the capture.
            if (state != SessionState.IDLE && state.ordinal() < SessionState.CAPTURE_READY.ordinal()) {
                failDevice(reason == EndReason.STREAM_TIMEOUT ? DeviceErrorCode.STALLED : DeviceErrorCode.DISCONNECTED);
            }
        } finally {
            exit();
        }
    }

    @Override
    public void tick() {
        if (!enter()) {
            return;
        }
        try {
            checkTimers(clock.monotonicNanos());
        } finally {
            exit();
        }
    }

    @Override
    public Optional<LivenessPassToken> passToken() {
        return isCaptureState() ? Optional.ofNullable(token) : Optional.<LivenessPassToken>empty();
    }

    @Override
    public Optional<FaceFrame> captureFrame() {
        return isCaptureState() && candidate.isPresent() ? Optional.of(candidate.frame())
                : Optional.<FaceFrame>empty();
    }

    private boolean isCaptureState() {
        return state == SessionState.CAPTURE_READY || state == SessionState.CAPTURING;
    }

    @Override
    public void captureStarted() {
        if (!enter()) {
            return;
        }
        try {
            if (state == SessionState.CAPTURE_READY) {
                captureStartedAt = clock.now();
                transition(SessionState.CAPTURING);
            }
        } finally {
            exit();
        }
    }

    @Override
    public void captureFailed(DeviceError error) {
        if (!enter()) {
            return;
        }
        try {
            if (isCaptureState()) {
                failDevice(error == null ? DeviceErrorCode.CAPTURE_FAILED : error.code());
            }
        } finally {
            exit();
        }
    }

    @Override
    public BindingVerdict bind(BindingRequest bindingRequest) {
        if (!enter()) {
            return BindingVerdict.failed(FailureReason.BINDING,
                    tokenConsumed ? ReasonCode.TOKEN_REUSED : ReasonCode.TOKEN_MISSING).build();
        }
        try {
            if (state != SessionState.CAPTURING) {
                return BindingVerdict.failed(FailureReason.BINDING,
                        token == null ? ReasonCode.TOKEN_MISSING : ReasonCode.TOKEN_MISMATCH).build();
            }
            return doBind(bindingRequest);
        } finally {
            exit();
        }
    }

    @Override
    public Optional<LivenessResult> result() {
        return Optional.ofNullable(result);
    }

    @Override
    public void cancel() {
        cancelRequested.set(true);
        if (Thread.currentThread() == owner && depth == 0 && !state.isTerminal()) {
            depth++;
            try {
                finishCancelled();
            } finally {
                depth--;
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (!state.isTerminal()) {
            cancelRequested.set(true);
            depth++;
            try {
                finishCancelled();
            } finally {
                depth--;
            }
        }
        closed = true;
        candidate.wipe();
        lastFace = null;
    }

    /**
     * Starts a public call on the owning thread: applies a pending cancel.
     *
     * @return {@code false} if the session has ended
     */
    private boolean enter() {
        depth++;
        if (cancelRequested.get() && !state.isTerminal()) {
            finishCancelled();
        }
        if (state.isTerminal()) {
            depth--;
            return false;
        }
        return true;
    }

    /** Ends a public call; a cancel requested from a listener callback takes effect here. */
    private void exit() {
        if (depth == 1 && cancelRequested.get() && !state.isTerminal()) {
            finishCancelled();
        }
        depth--;
    }

    // ------------------------------------------------------------------ frame processing

    private boolean isSampledOut(FaceFrame frame) {
        long interval = policy.frameSampleIntervalMs() * 1_000_000L;
        return interval > 0 && lastProcessedFrameNanos != Long.MIN_VALUE
                && frame.monotonicNanos() - lastProcessedFrameNanos < interval;
    }

    private void processFrame(FaceFrame frame) {
        long t0 = clock.monotonicNanos();
        ImageBuffer image;
        try {
            image = engine.decoder().decode(frame);
        } catch (ImageDecodeException | RuntimeException e) {
            ReasonCode r = integrity.onCorrupt();
            if (r != null) {
                failIntegrity(r);
            }
            return;
        }
        lastProcessedFrameNanos = frame.monotonicNanos();
        analysisRate.record(frame.monotonicNanos(), frame.sequence());
        if (state == SessionState.OPENING) {
            acquireStartNanos = clock.monotonicNanos();
            transition(SessionState.ACQUIRING);
            reporter.ui(ui.positioning(null));
        }
        ReasonCode r = integrity.onDecoded(frame, image);
        if (r != null) {
            failIntegrity(r);
            return;
        }
        try {
            FaceObservation obs = lastFace == null ? engine.analyzer().analyze(image)
                    : engine.analyzer().analyze(image, lastFace.box());
            Analysed a = new Analysed(frame, image, obs, gate.evaluate(obs));
            if (state == SessionState.ACQUIRING || state == SessionState.PASSIVE) {
                onPassiveFrame(a);
            } else {
                onActiveFrame(a);
            }
        } catch (EngineException e) {
            failEngine(e.getErrorCode(), e.getClass().getSimpleName());
            return;
        } catch (RuntimeException e) {
            failEngine(EngineErrorCode.INTERNAL, e.getClass().getSimpleName());
            return;
        }
        checkInferenceTime(t0);
    }

    private void checkInferenceTime(long t0) {
        if (state.isTerminal()) {
            return;
        }
        long ms = (clock.monotonicNanos() - t0) / 1_000_000L;
        if (metrics != null) {
            metrics.frameProcessed(ms);
        }
        slowInferences = ms > params.getLong(EngineParameter.INFERENCE_SLOW_MS) ? slowInferences + 1 : 0;
        if (slowInferences >= params.getInt(EngineParameter.INFERENCE_SLOW_COUNT)) {
            failEngine(EngineErrorCode.INFERENCE_TIMEOUT, null);
        }
    }

    private void onPassiveFrame(Analysed a) throws EngineException {
        ReasonCode problem = a.problem;
        if (problem == null) {
            DetectedFace face = a.observation.faces().get(0);
            if (!tracker.update(a.frame.monotonicNanos(), face.box(), analysisRate.meanIntervalMs())) {
                // A different face may have taken over: start collecting again.
                passiveScores.clear();
                passiveBrightness.clear();
                consecutiveGood = 0;
                flags.add(ReasonCode.TRACKING_LOST);
            }
            lastFace = face;
            consecutiveGood++;
            passiveScores.add(engine.pad().score(a.image, face.box()).liveProbability());
            if (face.quality().isPresent()) {
                passiveBrightness.add(face.quality().get().brightness());
            }
            FaceSignals s = FaceSignals.of(face);
            lastIed = s.interEyeDistance;
            boolean sampled = goodFrames++ % 2 == 0;
            if (isFrontal(s, params.get(EngineParameter.NEUTRAL_MAX_YAW_DEG))) {
                if (sampled) {
                    identity.addReference(a.image, face.box());
                }
            } else {
                // Admitted by the quality gate but outside the neutral window: kept in case no frame is neutral.
                identity.offerFallback(a.image, face.box(), Math.max(Math.abs(s.yaw), Math.abs(s.pitch)));
            }
            candidate.offer(a.frame, a.image, face, s, candidate.isStorable(s));
        } else {
            consecutiveGood = 0;
            lastProblem = problem;
            qualityProblems.add(problem);
        }
        if (state == SessionState.ACQUIRING) {
            if (consecutiveGood < params.getInt(EngineParameter.ACQUIRE_CONSECUTIVE_FRAMES)) {
                reporter.ui(ui.positioning(problem));
                return;
            }
            transition(SessionState.PASSIVE);
        }
        if (passiveScores.count() >= policy.passiveMinFrames()) {
            identity.completeReference();
            decidePassive();
        } else {
            reporter.ui(ui.checking((double) passiveScores.count() / policy.passiveMinFrames(), problem));
        }
    }

    private void decidePassive() {
        long now = clock.monotonicNanos();
        passiveScore = passiveScores.score();
        timings.passiveEnded(now);
        List<ChallengeType> eligible = ChallengeScheduler.eligible(policy.challengeTypes(),
                engine.analyzer().features(), engine.info().supportedChallenges(), analysisRate.fps(), lastIed);
        DecisionPolicy d = DecisionPolicy.decide(policy, passiveScore, false, !eligible.isEmpty());
        if (d.outcome == DecisionPolicy.Outcome.CAPTURE_READY) {
            method = d.method;
            long ms = timings.sinceStartMs(now);
            reporter.audit(audit.passivePassed(passiveScore, passiveScores.count(), ms));
            if (metrics != null) {
                metrics.passivePassed(ms);
            }
            enterCaptureReady();
        } else if (d.outcome == DecisionPolicy.Outcome.CHALLENGE) {
            method = d.method;
            reporter.audit(audit.activeTriggered(passiveScore, d.reason));
            if (metrics != null) {
                metrics.activeTriggered();
            }
            startActive(eligible, now);
        } else if (d.failureReason == FailureReason.ENGINE) {
            failEngine(EngineErrorCode.UNSUPPORTED, null);
        } else if (d.failureReason == FailureReason.PAD && isLowLightRetry()) {
            // Not an attack verdict on this evidence: the face is lit too weakly for the PAD models. Not counted.
            fail(FailureReason.QUALITY, ReasonCode.LOW_LIGHT);
        } else {
            fail(d.failureReason, d.reason);
        }
    }

    /**
     * Tells whether a passive PAD failure ends uncounted for low light: the caller tracks low-light results, fewer
     * than {@code passive.low_light_max_uncounted} came since its last counted failure, and the median brightness of
     * the scored faces is below {@code quality.low_light_brightness}.
     */
    private boolean isLowLightRetry() {
        int used = request.lowLightRetries();
        if (used < 0 || used >= params.getInt(EngineParameter.LOW_LIGHT_MAX_UNCOUNTED) || passiveBrightness.isEmpty()) {
            return false;
        }
        List<Double> sorted = new ArrayList<>(passiveBrightness);
        Collections.sort(sorted);
        int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        return median < params.get(EngineParameter.LOW_LIGHT_BRIGHTNESS);
    }

    private void startActive(List<ChallengeType> eligible, long now) {
        double fps = analysisRate.fps();
        boolean requireTurn = params.isOn(EngineParameter.PLANARITY_ENABLED)
                && fps >= params.get(EngineParameter.HEAD_TURN_MIN_FPS);
        List<ChallengeType> sequence = new ChallengeScheduler(request.random()).draw(eligible, policy.minChallenges(),
                requireTurn);
        List<ChallengeType> detectable = ChallengeScheduler.eligible(EnumSet.allOf(ChallengeType.class),
                engine.analyzer().features(), Collections.<ChallengeType>emptySet(), fps, lastIed);
        active = new ActiveStage(sequence, detectable, policy, params, request.random(), now);
        timings.activeStarted(now);
        transition(SessionState.CHALLENGE_WAIT);
        reporter.ui(ui.challengeWaiting(0, active.count(), false));
    }

    private void onActiveFrame(Analysed a) throws EngineException {
        long nanos = a.frame.monotonicNanos();
        if (a.observation.faceCount() > 1) {
            lastProblem = ReasonCode.MULTI_FACE;
            qualityProblems.add(ReasonCode.MULTI_FACE);
            if (++multiFaceRun >= params.getInt(EngineParameter.ACTIVE_MULTI_FACE_FRAMES)) {
                // A second person in view: "More than one face detected" now instead of at the timeout; finish() counts it.
                fail(FailureReason.QUALITY, ReasonCode.MULTI_FACE);
                return;
            }
        } else {
            multiFaceRun = 0;
        }
        DetectedFace face = a.observation.faceCount() == 1 ? a.observation.faces().get(0) : null;
        FaceSignals s = null;
        List<FacePoint> points = Collections.emptyList();
        if (face != null) {
            s = FaceSignals.of(face);
            points = PlanarityChecker.rigidPoints(face);
            if (!tracker.update(nanos, face.box(), analysisRate.meanIntervalMs()) && !identityHolds(a.image, face)) {
                return;
            }
            lastFace = face;
            if (scoreActivePad(a.image, face, s, nanos)) {
                return;
            }
        }
        ActiveStage.Event event = active.onFrame(nanos, s, points, a.problem == null);
        if (face != null && active.isCapturePoseFrame()) {
            // Only the capture pose after the last challenge gives the stored frame, never a challenge frame.
            candidate.offer(a.frame, a.image, face, s, true);
        }
        if (active.isWaitingForCapturePose()) {
            reporter.ui(ui.lookStraight(a.problem));
        }
        onActiveEvent(event, a, face, nanos);
    }

    /**
     * After the track broke during the challenges, checks that the face is still the reference person.
     *
     * @return {@code true} if the session may continue
     */
    private boolean identityHolds(ImageBuffer image, DetectedFace face) throws EngineException {
        flags.add(ReasonCode.TRACKING_LOST);
        if (!identity.hasReference()) {
            failIntegrity(ReasonCode.TRACKING_LOST);
            return false;
        }
        int sim = identity.similarity(image, face.box());
        if (sim >= 0 && sim < policy.bindingMinSimilarity()) {
            failIntegrity(ReasonCode.FACE_SWITCH);
            return false;
        }
        if (sim < 0) {
            failIntegrity(ReasonCode.TRACKING_LOST);
            return false;
        }
        return true;
    }

    /**
     * Runs PAD on every n-th active frame and fails the session when the recent median drops below the reject
     * threshold. Frames turned further than {@code active.pad_max_yaw_deg} are not scored, because the PAD models
     * score a genuine face in profile as an attack.
     *
     * @return {@code true} if the session failed
     */
    private boolean scoreActivePad(ImageBuffer image, DetectedFace face, FaceSignals s, long nanos)
            throws EngineException {
        if (!Double.isNaN(s.yaw) && Math.abs(s.yaw) > params.get(EngineParameter.ACTIVE_PAD_MAX_YAW_DEG)) {
            return false;
        }
        if (activeFrames++ % params.getInt(EngineParameter.ACTIVE_PAD_STRIDE) != 0) {
            return false;
        }
        double p = engine.pad().score(image, face.box()).liveProbability();
        activeRolling.add(p);
        activeAll.add(p);
        if (activeRolling.isFull() && activeRolling.score() < policy.padRejectThreshold()) {
            active.abort(nanos, ChallengeOutcome.PAD_FAILED);
            fail(FailureReason.PAD, ReasonCode.PAD_SCORE);
            return true;
        }
        return false;
    }

    private void onActiveEvent(ActiveStage.Event event, Analysed a, DetectedFace face, long nanos)
            throws EngineException {
        switch (event) {
            case NEUTRAL_READY:
                prompt(a.image, face, nanos);
                break;
            case CHALLENGE_PASSED:
                ChallengeRecord rec = active.records().get(active.records().size() - 1);
                reporter.audit(audit.challengePassed(rec.type(), rec.onsetMs().orElse(0), rec.holdMs().orElse(0)));
                if (metrics != null) {
                    metrics.challengeCompleted(rec.durationMs());
                }
                transition(SessionState.CHALLENGE_PASSED);
                reporter.ui(ui.actionDetected(active.index(), active.count()));
                break;
            case NEXT_CHALLENGE:
                transition(SessionState.CHALLENGE_WAIT);
                reporter.ui(ui.challengeWaiting(active.index(), active.count(), false));
                break;
            case ALL_DONE:
                finishChallenges(nanos);
                break;
            case CAPTURE_POSE_READY:
                finishCapturePose();
                break;
            case CAPTURE_POSE_TIMEOUT:
                reporter.audit(audit.timeout(state));
                fail(FailureReason.TIMEOUT, ReasonCode.NEUTRAL_TIMEOUT);
                break;
            case FAILED:
                challengeFailed();
                break;
            default:
                refreshActiveUi(nanos);
        }
    }

    private void prompt(ImageBuffer image, DetectedFace face, long nanos) throws EngineException {
        if (identity.hasReference()) {
            int sim = identity.similarity(image, face.box());
            if (sim >= 0 && sim < policy.bindingMinSimilarity()) {
                failIntegrity(ReasonCode.FACE_SWITCH);
                return;
            }
        }
        ChallengeType type = active.prompt(nanos);
        reporter.audit(audit.challengeIssued(type, active.index(), SessionIds.nonce(request.random())));
        transition(SessionState.CHALLENGE_OBSERVE);
        reporter.ui(ui.challenge(type, active.index(), active.count(), false));
    }

    private void refreshActiveUi(long nanos) {
        if (state == SessionState.CHALLENGE_WAIT) {
            reporter.ui(ui.challengeWaiting(active.index(), active.count(), active.isNeutralNow()));
        } else if (state == SessionState.CHALLENGE_OBSERVE && active.observer() != null) {
            boolean remind = nanos - active.observer().promptNanos() > policy.challengeTimeoutMs() * 500_000L;
            reporter.ui(ui.challenge(active.current(), active.index(), active.count(), remind));
        }
    }

    private void challengeFailed() {
        ChallengeObserver o = active.observer();
        if (o == null || o.status() != ChallengeObserver.Status.FAILED) {
            reporter.audit(audit.timeout(SessionState.CHALLENGE_WAIT));
            fail(FailureReason.TIMEOUT, ReasonCode.NEUTRAL_TIMEOUT);
            return;
        }
        reporter.audit(audit.challengeFailed(o.target(), o.reason()));
        if (o.failureReason() == FailureReason.TIMEOUT) {
            reporter.audit(audit.timeout(SessionState.CHALLENGE_OBSERVE));
        }
        fail(o.failureReason(), o.reason());
    }

    /**
     * Final checks after the last challenge (docs/design/decision-flow.md section 2), then the wait for the capture
     * pose: "Look directly at the camera and hold still" until a neutral, frontal face gives the stored frame.
     */
    private void finishChallenges(long nanos) {
        timings.activeEnded(clock.monotonicNanos());
        activePadScore = activeAll.count() > 0 ? activeAll.score() : passiveScore;
        if (activePadScore < policy.padRejectThreshold()) {
            fail(FailureReason.PAD, ReasonCode.PAD_SCORE);
            return;
        }
        double minResidual = params.get(EngineParameter.PLANARITY_MIN_RESIDUAL);
        for (double residual : active.planarityResiduals()) {
            if (!Double.isNaN(residual)) {
                lastPlanarity = residual;
                if (params.isOn(EngineParameter.PLANARITY_ENABLED) && residual < minResidual) {
                    failIntegrity(ReasonCode.PLANAR);
                    return;
                }
            }
        }
        candidate.reset();
        active.startCapturePose(nanos);
        reporter.ui(ui.lookStraight(null));
    }

    /** The capture pose was held: checks that the chosen frame shows the reference person and issues the token. */
    private void finishCapturePose() throws EngineException {
        timings.activeEnded(clock.monotonicNanos());
        if (identity.hasReference() && candidate.isPresent()) {
            int sim = identity.similarity(candidate.image(), candidate.face().box());
            if (sim >= 0 && sim < policy.bindingMinSimilarity()) {
                failIntegrity(ReasonCode.FACE_SWITCH);
                return;
            }
        }
        enterCaptureReady();
    }

    private static boolean isFrontal(FaceSignals s, double maxDeg) {
        return !s.hasPose() || (Math.abs(s.yaw) <= maxDeg && Math.abs(s.pitch) <= maxDeg);
    }

    // ------------------------------------------------------------------ timers

    private void checkTimers(long now) {
        switch (state) {
            case OPENING:
                if (now - timings.start() > params.getLong(EngineParameter.FIRST_FRAME_TIMEOUT_MS) * 1_000_000L) {
                    failDevice(DeviceErrorCode.FIRST_FRAME_TIMEOUT);
                }
                break;
            case ACQUIRING:
            case PASSIVE:
                if (isStalled(now)) {
                    failDevice(DeviceErrorCode.STALLED);
                } else if (now - acquireStartNanos > policy.passiveTimeoutMs() * 1_000_000L) {
                    onPassiveTimeout();
                }
                break;
            case CHALLENGE_WAIT:
            case CHALLENGE_OBSERVE:
            case CHALLENGE_PASSED:
                if (isStalled(now)) {
                    failDevice(DeviceErrorCode.STALLED);
                } else {
                    onActiveTimer(now);
                }
                break;
            case CAPTURE_READY:
            case CAPTURING:
                if (token != null && clock.now().isAfter(token.expiresAt())) {
                    failDevice(DeviceErrorCode.CAPTURE_TIMEOUT);
                }
                break;
            default:
                break;
        }
    }

    private void onActiveTimer(long now) {
        try {
            onActiveEvent(active.onTime(now), null, null, now);
        } catch (EngineException e) {
            failEngine(e.getErrorCode(), e.getClass().getSimpleName());
        }
    }

    private boolean isStalled(long now) {
        return now - lastArrivalNanos > params.getLong(EngineParameter.NO_FRAME_TIMEOUT_MS) * 1_000_000L;
    }

    /**
     * At the end of {@code passive.timeout_ms}: a slow device that collected at least
     * {@code passive.timeout_min_frames} good frames, but not {@code passive.min_frames}, gets its passive decision
     * on those frames; otherwise the session fails with {@code PASSIVE_TIMEOUT}.
     */
    private void onPassiveTimeout() {
        if (state == SessionState.PASSIVE
                && passiveScores.count() >= params.getInt(EngineParameter.PASSIVE_TIMEOUT_MIN_FRAMES)) {
            decidePassive();
        } else {
            failPassiveTimeout();
        }
    }

    private void failPassiveTimeout() {
        reporter.audit(audit.timeout(state));
        if (lastProblem != null) {
            fail(FailureReason.QUALITY, ReasonCode.PASSIVE_TIMEOUT, lastProblem);
        } else {
            fail(FailureReason.QUALITY, ReasonCode.PASSIVE_TIMEOUT);
        }
    }

    // ------------------------------------------------------------------ capture and binding

    private void enterCaptureReady() {
        Instant now = clock.now();
        long lifetimeMs = policy.bindingMaxGapMs() + params.getLong(EngineParameter.CAPTURE_TIMEOUT_MS);
        token = LivenessPassToken.builder().sessionId(sessionId).workflow(policy.workflow())
                .subjectRef(request.subjectRef()).deviceSerial(request.device().serialNumber())
                .nonce(SessionIds.nonce(request.random())).issuedAt(now).expiresAt(now.plusMillis(lifetimeMs)).build();
        transition(SessionState.CAPTURE_READY);
        reporter.ui(ui.holdStill());
    }

    private BindingVerdict doBind(BindingRequest bindingRequest) {
        transition(SessionState.BINDING);
        reporter.ui(ui.verifying());
        Instant windowStart = policy.workflow().isAuth() && captureStartedAt != null ? captureStartedAt
                : token.issuedAt();
        boolean checks = method != LivenessMethod.NONE && method != LivenessMethod.CAPTURE_ONLY;
        CaptureBinder.Outcome o;
        try {
            o = binder.bind(bindingRequest, token, tokenConsumed, request.device().isPadCapable(), clock.now(),
                    windowStart, checks);
        } catch (RuntimeException e) {
            tokenConsumed = true;
            failEngine(EngineErrorCode.INTERNAL, e.getClass().getSimpleName());
            return BindingVerdict.failed(FailureReason.BINDING, ReasonCode.ENGINE_ERROR).build();
        }
        tokenConsumed = true;
        devicePad = bindingRequest.devicePad().orElse(null);
        BindingVerdict v = o.verdict;
        if (o.engineError != null) {
            failEngine(o.engineError.getErrorCode(), o.engineError.getClass().getSimpleName());
            return v;
        }
        if (v.similarity().isPresent()) {
            bindingSimilarity = v.similarity().getAsInt();
        }
        if (v.isPassed()) {
            finish(null, Collections.<ReasonCode>emptySet());
            return v;
        }
        reporter.audit(audit.bindingFailed(v));
        FailureReason reason = v.failureReason().get();
        ReasonCode primary = reason == FailureReason.PAD
                ? (v.reasonCodes().contains(ReasonCode.DEVICE_PAD) ? ReasonCode.DEVICE_PAD : ReasonCode.CAPTURE_PAD_SCORE)
                : v.reasonCodes().iterator().next();
        Set<ReasonCode> codes = EnumSet.copyOf(v.reasonCodes());
        if (reason == FailureReason.PAD) {
            reporter.audit(audit.padAttackSuspected(primary));
        }
        finish(reason, codes);
        return v;
    }

    // ------------------------------------------------------------------ endings

    private void failIntegrity(ReasonCode code) {
        flags.add(code);
        fail(FailureReason.INTEGRITY, code);
    }

    private void failDevice(DeviceErrorCode code) {
        deviceError = code;
        fail(FailureReason.DEVICE, ReasonCode.DEVICE_ERROR);
    }

    private void failEngine(EngineErrorCode code, String errorClass) {
        engineError = code;
        reporter.audit(audit.engineError(code, errorClass));
        if (code.isModelIntegrityFailure()) {
            reporter.audit(audit.modelIntegrityFailed(engine.info().id()));
        }
        fail(FailureReason.ENGINE, ReasonCode.ENGINE_ERROR);
    }

    /**
     * Ends the session with a failure: records an interrupted challenge, writes the reason's audit event and
     * builds the result.
     */
    private void fail(FailureReason reason, ReasonCode primary, ReasonCode... more) {
        if (state.isTerminal()) {
            return;
        }
        long now = clock.monotonicNanos();
        if (active != null) {
            boolean attack = reason == FailureReason.PAD || reason == FailureReason.INTEGRITY;
            active.abort(now, attack ? ChallengeOutcome.PAD_FAILED : ChallengeOutcome.CANCELLED);
        }
        if (reason == FailureReason.PAD || reason == FailureReason.INTEGRITY) {
            reporter.audit(audit.padAttackSuspected(primary));
        } else if (reason == FailureReason.DEVICE) {
            reporter.audit(audit.deviceError(deviceError));
        }
        Set<ReasonCode> codes = EnumSet.of(primary, more);
        finish(reason, codes);
    }

    private void finishCancelled() {
        if (state.isTerminal()) {
            return;
        }
        if (startedAt == null) {
            timings.started(clock.monotonicNanos());
            startedAt = clock.now();
        }
        if (active != null) {
            active.abort(clock.monotonicNanos(), ChallengeOutcome.CANCELLED);
        }
        finish(FailureReason.CANCELLED, EnumSet.of(ReasonCode.CANCELLED_BY_USER));
    }

    /**
     * Builds the result, moves to the terminal state and reports it.
     *
     * @param reason failure reason, or {@code null} for a pass
     * @param codes  reason codes
     */
    private void finish(FailureReason reason, Set<ReasonCode> codes) {
        if (active != null && (reason == FailureReason.CANCELLED || reason == FailureReason.DEVICE
                || reason == FailureReason.QUALITY)) {
            // A challenge is already on screen: a free ending would let an attacker re-draw challenges until one
            // suits a prepared video. It costs an attempt like a timeout; the reason codes keep the real cause.
            reason = FailureReason.TIMEOUT;
        }
        long end = clock.monotonicNanos();
        LivenessResult r = buildResult(reason, codes, end);
        SessionState terminal = reason == null ? SessionState.PASSED
                : reason == FailureReason.CANCELLED ? SessionState.CANCELLED : SessionState.FAILED;
        if (reason == null) {
            reporter.audit(audit.passed(method, r.totalMs()));
        } else if (reason.countsAsAttempt() && request.attempt() >= policy.maxRetries()) {
            reporter.audit(audit.maxRetries(request.attempt(), policy.onMaxFailure()));
        }
        flags.addAll(codes);
        result = r;
        SessionState previous = state;
        state = terminal;
        diagnostics.stateChanged(terminal, r.totalMs(), currentPadScore(), flags, lastPlanarity, currentSimilarity(),
                analysisRate.fps(), reason == null ? "" : "reason=" + reason);
        if (metrics != null) {
            metrics.sessionEnded(r);
        }
        reporter.stateChanged(previous, terminal);
        reporter.ui(ui.forResult(r, qualityProblems));
        reporter.result(r);
    }

    private LivenessResult buildResult(FailureReason reason, Set<ReasonCode> codes, long end) {
        long[] ms = timings.durationsMs(end);
        Instant endedAt = clock.now();
        LivenessResult.Builder b = LivenessResult.builder(sessionId, policy.workflow(), engine.info(), request.device())
                .attempt(request.attempt()).method(method)
                .period(startedAt, endedAt.isBefore(startedAt) ? startedAt : endedAt)
                .timings(ms[0], ms[1], ms[2])
                .devicePad(devicePad).deviceError(deviceError).engineError(engineError);
        if (reason == null) {
            b.pass();
        } else {
            b.fail(reason);
        }
        for (ReasonCode c : codes) {
            b.reason(c);
        }
        if (passiveScore >= 0) {
            b.passiveScore(passiveScore);
        }
        if (activePadScore >= 0) {
            b.activePadScore(activePadScore);
        }
        if (bindingSimilarity >= 0) {
            b.bindingSimilarity(bindingSimilarity);
        }
        if (active != null) {
            for (ChallengeRecord rec : active.records()) {
                b.challenge(rec);
            }
        }
        return b.build();
    }

    private void transition(SessionState next) {
        SessionState previous = state;
        state = next;
        diagnostics.stateChanged(next, timings.sinceStartMs(clock.monotonicNanos()), currentPadScore(), flags,
                lastPlanarity, currentSimilarity(), analysisRate.fps(), "");
        reporter.stateChanged(previous, next);
    }

    private int currentPadScore() {
        if (activeAll.count() > 0) {
            return activeAll.score();
        }
        return passiveScore >= 0 ? passiveScore : passiveScores.score();
    }

    private int currentSimilarity() {
        return bindingSimilarity >= 0 ? bindingSimilarity : identity.lastSimilarity();
    }

    // ------------------------------------------------------------------ small holders

    /** One analysed frame. */
    private static final class Analysed {
        final FaceFrame frame;
        final ImageBuffer image;
        final FaceObservation observation;
        final ReasonCode problem;

        Analysed(FaceFrame frame, ImageBuffer image, FaceObservation observation, ReasonCode problem) {
            this.frame = frame;
            this.image = image;
            this.observation = observation;
            this.problem = problem;
        }
    }
}
