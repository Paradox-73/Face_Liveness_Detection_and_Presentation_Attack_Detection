package io.mosip.registration.service.bio.liveness;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.engine.AuditPayloadBuilder;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.DeviceStatus;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.device.StreamStats;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.mdm.dto.LivenessContext;

/**
 * Test doubles for the desktop liveness services: an engine whose sessions follow a script, a device that streams
 * synthetic JPEG frames, a recording audit sink and listener. No image of a real person is used.
 */
final class LivenessFakes {

	static final String SERIAL = "SN-TEST-1";

	private LivenessFakes() {
	}

	/** Draws a synthetic JPEG frame: a grey background with a coloured disc. */
	static byte[] jpeg(int width, int height, int seed) {
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(120, 120, 120));
		g.fillRect(0, 0, width, height);
		g.setColor(new Color((seed * 40) % 255, 160, 90));
		g.fillOval(width / 4 + seed % 5, height / 4, width / 2, height / 2);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(img, "jpg", out);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return out.toByteArray();
	}

	static DeviceDescriptor descriptor() {
		return DeviceDescriptor.builder().deviceId("dev-1").serialNumber(SERIAL).make("Mock").model("Face")
				.providerId("MOCK").certification(CertificationLevel.L0).specVersion("0.9.5")
				.purpose(DevicePurpose.REGISTRATION).transport(DeviceTransport.SBI_HTTP).streamCapable(true)
				.padCapable(false).build();
	}

	static EngineInfo engineInfo() {
		return EngineInfo.builder("fake-engine", "1.0.0").vendor("test").build();
	}

	/** A clock that follows real monotonic time but can be moved forward. */
	static final class TestClock implements Clock {
		private volatile long offsetMs;

		void advance(long ms) {
			offsetMs += ms;
		}

		@Override
		public long monotonicNanos() {
			return System.nanoTime() + offsetMs * 1_000_000L;
		}

		@Override
		public Instant now() {
			return Instant.now().plusMillis(offsetMs);
		}
	}

	/** Collects audit events. */
	static final class RecordingAudit implements AuditSink {
		final List<LivenessAuditEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void emit(LivenessAuditEvent event) {
			events.add(event);
		}

		boolean has(String id) {
			for (LivenessAuditEvent e : events) {
				if (e.eventId().id().equals(id)) {
					return true;
				}
			}
			return false;
		}
	}

	/** Collects run callbacks and lets a test wait for them. */
	static final class RecordingListener implements LivenessRunListener {
		final List<LivenessUiState> states = new CopyOnWriteArrayList<>();
		final List<LivenessOutcome> outcomes = new CopyOnWriteArrayList<>();
		final AtomicInteger previews = new AtomicInteger();
		volatile LivenessContext captureContext;
		volatile CountDownLatch ready = new CountDownLatch(1);
		volatile CountDownLatch ended = new CountDownLatch(1);

		@Override
		public void onUiState(LivenessUiState state) {
			states.add(state);
		}

		@Override
		public void onPreviewFrame(FaceFrame frame) {
			previews.incrementAndGet();
		}

		@Override
		public void onCaptureReady(LivenessContext context) {
			captureContext = context;
			ready.countDown();
		}

		@Override
		public void onSessionEnded(LivenessOutcome outcome) {
			outcomes.add(outcome);
			ended.countDown();
		}

		boolean awaitReady() throws InterruptedException {
			return ready.await(5, TimeUnit.SECONDS);
		}

		boolean awaitEnded() throws InterruptedException {
			return ended.await(5, TimeUnit.SECONDS);
		}

		void reset() {
			ready = new CountDownLatch(1);
			ended = new CountDownLatch(1);
			captureContext = null;
		}

		LivenessOutcome last() {
			return outcomes.get(outcomes.size() - 1);
		}
	}

	/** What a scripted session does after it received enough frames. */
	enum Script {
		PASS, FAIL_PAD, FAIL_TIMEOUT
	}

	/** Engine whose sessions follow {@link #scripts} in order (the last one repeats). */
	static final class ScriptedEngine implements LivenessEngine {
		final List<Script> scripts = new CopyOnWriteArrayList<>();
		final List<ScriptedSession> sessions = new CopyOnWriteArrayList<>();
		final Clock clock;
		int framesToDecide = 3;
		/** Sessions write REG-EVT-LV11 on the last counted attempt, as the real engine does. */
		boolean auditsMaxRetries;

		ScriptedEngine(Clock clock, Script... scripts) {
			this.clock = clock;
			Collections.addAll(this.scripts, scripts);
		}

		@Override
		public EngineInfo info() {
			return engineInfo();
		}

		@Override
		public LivenessSession open(SessionRequest request, LivenessListener listener) {
			Script s = scripts.get(Math.min(sessions.size(), scripts.size() - 1));
			ScriptedSession session = new ScriptedSession(request, listener, s, framesToDecide, clock);
			session.auditsMaxRetries = auditsMaxRetries;
			sessions.add(session);
			return session;
		}
	}

	/** A session that decides after a number of frames, then binds any capture presenting its token. */
	static final class ScriptedSession implements LivenessSession {
		private static final AtomicInteger IDS = new AtomicInteger();

		final String id = "session-" + IDS.incrementAndGet();
		final SessionRequest request;
		final LivenessListener listener;
		final Script script;
		final int framesToDecide;
		final Clock clock;
		final List<Long> sequences = new ArrayList<>();
		volatile SessionState state = SessionState.IDLE;
		LivenessPassToken token;
		LivenessResult result;
		Instant started;
		volatile boolean closed;
		boolean auditsMaxRetries;

		ScriptedSession(SessionRequest request, LivenessListener listener, Script script, int framesToDecide,
				Clock clock) {
			this.request = request;
			this.listener = listener;
			this.script = script;
			this.framesToDecide = framesToDecide;
			this.clock = clock;
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
			started = clock.now();
			state = SessionState.ACQUIRING;
			listener.onUi(LivenessUiState.builder(UiPhase.CHECKING, MessageKeys.STATUS_CHECKING).build());
		}

		@Override
		public void tick() {
		}

		@Override
		public void onFrame(FaceFrame frame) {
			if (state != SessionState.ACQUIRING) {
				return;
			}
			sequences.add(frame.sequence());
			if (sequences.size() < framesToDecide) {
				return;
			}
			switch (script) {
			case PASS:
				token = LivenessPassToken.builder().sessionId(id).workflow(request.workflow())
						.subjectRef(request.subjectRef()).deviceSerial(request.device().serialNumber())
						.nonce("nonce-0123456789-" + id).issuedAt(clock.now()).expiresAt(clock.now().plusMillis(10_000)).build();
				state = SessionState.CAPTURE_READY;
				break;
			case FAIL_PAD:
				end(FailureReason.PAD);
				break;
			default:
				end(FailureReason.TIMEOUT);
			}
		}

		@Override
		public void onError(DeviceError error) {
			if (!state.isTerminal() && state != SessionState.CAPTURE_READY && state != SessionState.CAPTURING) {
				end(FailureReason.DEVICE);
			}
		}

		@Override
		public void onEnd(EndReason reason) {
			if (reason != EndReason.CLOSED && state == SessionState.ACQUIRING) {
				end(FailureReason.DEVICE);
			}
		}

		@Override
		public Optional<LivenessPassToken> passToken() {
			return state == SessionState.CAPTURE_READY || state == SessionState.CAPTURING ? Optional.of(token)
					: Optional.<LivenessPassToken>empty();
		}

		@Override
		public Optional<FaceFrame> captureFrame() {
			return Optional.empty();
		}

		@Override
		public void captureStarted() {
			if (state == SessionState.CAPTURE_READY) {
				state = SessionState.CAPTURING;
			}
		}

		@Override
		public void captureFailed(DeviceError error) {
			if (state == SessionState.CAPTURING || state == SessionState.CAPTURE_READY) {
				end(FailureReason.DEVICE);
			}
		}

		@Override
		public BindingVerdict bind(BindingRequest bindingRequest) {
			if (state != SessionState.CAPTURING || !bindingRequest.token().isPresent()
					|| !bindingRequest.token().get().equals(token)) {
				return BindingVerdict.failed(FailureReason.BINDING, ReasonCode.TOKEN_MISMATCH).build();
			}
			result = base().pass().method(LivenessMethod.PASSIVE).passiveScore(91).bindingSimilarity(88).build();
			state = SessionState.PASSED;
			return BindingVerdict.passed().similarity(88).build();
		}

		@Override
		public Optional<LivenessResult> result() {
			return Optional.ofNullable(result);
		}

		@Override
		public void cancel() {
			if (!state.isTerminal()) {
				end(FailureReason.CANCELLED);
			}
		}

		@Override
		public void close() {
			closed = true;
			cancel();
		}

		private void end(FailureReason reason) {
			result = base().fail(reason).method(LivenessMethod.PASSIVE).build();
			state = reason == FailureReason.CANCELLED ? SessionState.CANCELLED : SessionState.FAILED;
			// Like DefaultLivenessSession: the session itself reports the last counted attempt (REG-EVT-LV11).
			if (auditsMaxRetries && reason.countsAsAttempt() && request.attempt() >= request.policy().maxRetries()) {
				listener.onAudit(new AuditPayloadBuilder(request.workflow(), id, clock).maxRetries(request.attempt(),
						request.policy().onMaxFailure()));
			}
		}

		private LivenessResult.Builder base() {
			Instant now = clock.now();
			return LivenessResult.builder(id, request.workflow(), engineInfo(), request.device())
					.attempt(request.attempt()).period(started == null ? now : started, now);
		}
	}

	/** A device that streams synthetic JPEG frames every 20 ms from its own thread. */
	static final class SyntheticDevice implements FaceCaptureDevice {
		final AtomicInteger streamsOpened = new AtomicInteger();
		final AtomicInteger streamsClosed = new AtomicInteger();
		volatile DeviceStatus status = DeviceStatus.READY;
		volatile boolean failOpen;
		volatile int framesBeforeEnd = Integer.MAX_VALUE;

		@Override
		public DeviceDescriptor describe() {
			return descriptor();
		}

		@Override
		public DeviceStatus status() {
			return status;
		}

		@Override
		public FaceFrameSource openStream(StreamRequest request) throws DeviceException {
			if (failOpen) {
				throw new DeviceException(DeviceErrorCode.STREAM_OPEN_FAILED, "device refused the stream");
			}
			streamsOpened.incrementAndGet();
			return new FaceFrameSource() {
				private volatile boolean stop;

				@Override
				public void start(FrameSink sink) {
					Thread t = new Thread(() -> {
						long seq = 0;
						while (!stop && seq < framesBeforeEnd) {
							byte[] data = jpeg(64, 48, (int) seq);
							sink.onFrame(FaceFrame.builder().data(data).format(PixelFormat.JPEG).size(64, 48)
									.monotonicNanos(System.nanoTime()).sequence(seq++).sourceId(SERIAL).build());
							try {
								Thread.sleep(20);
							} catch (InterruptedException e) {
								return;
							}
						}
						sink.onEnd(stop ? EndReason.CLOSED : EndReason.END_OF_STREAM);
					}, "synthetic-stream");
					t.setDaemon(true);
					t.start();
				}

				@Override
				public StreamStats stats() {
					return StreamStats.EMPTY;
				}

				@Override
				public void close() {
					if (!stop) {
						stop = true;
						streamsClosed.incrementAndGet();
					}
				}
			};
		}

		@Override
		public SignedFaceCapture capture(CaptureRequest request) throws DeviceException {
			throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "not used");
		}
	}
}
