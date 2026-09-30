package io.mosip.registration.livenessmanager.session;

import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.liveness.core.ui.Severity;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.SupervisorOverride;
import io.mosip.registration.livenessmanager.device.BuiltInCameraL0Device;

/**
 * Takes a signed photo with the built-in camera when the workflow's liveness policy is {@code OFF} and the caller
 * still needs a face image from this device: the exception photo of a resident whose fingers or irises cannot be
 * captured ({@code mosip.registration.liveness.policy.exception_photo}, default {@code OFF}, as on the desktop
 * client). Without it a tablet whose face device is the built-in camera could not finish a registration with
 * biometric exceptions.
 *
 * <p>It streams for {@value #SETTLE_MS} ms (exposure and focus settle, the subject faces the camera), then the
 * built-in device signs the newest frame exactly as it signs a liveness capture (ISO 19794-5 record, SBI 0.9.5
 * response, device key). No liveness check runs and no liveness evidence is attached: the result carries
 * {@link LivenessCaptureResult#REASON_LIVENESS_NOT_REQUIRED}. A camera that delivers no frame gives the usual
 * camera error with Retry.
 *
 * <p>The same photo is taken for a resident face accepted under a {@link SupervisorOverride}: the result then
 * carries {@link LivenessCaptureResult#REASON_SUPERVISOR_OVERRIDE} and the override's packet evidence
 * ({@code LIVENESS_DECISION=OVERRIDE}).
 */
public final class PhotoCaptureRunner implements CaptureRunner {

    /** How long the stream runs before the photo is taken. */
    static final long SETTLE_MS = 1_500L;
    /** Longest wait for the first frame. */
    static final long FIRST_FRAME_TIMEOUT_MS = 10_000L;

    private static final String TAG = "Liveness";

    private final Workflow workflow;
    private final BuiltInCameraL0Device device;
    private final LivenessRunner.Listener listener;
    private final SupervisorOverride override;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "liveness-photo");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<FaceFrameSource> source = new AtomicReference<>();
    private volatile boolean cancelled;
    private volatile boolean closed;
    private volatile boolean running;

    /**
     * Creates the runner; nothing happens until {@link #start()}.
     *
     * @param workflow the workflow (its liveness policy is off)
     * @param device   the built-in camera
     * @param listener receiver of states and the outcome (same contract as {@link LivenessRunner})
     */
    public PhotoCaptureRunner(Workflow workflow, BuiltInCameraL0Device device, LivenessRunner.Listener listener) {
        this(workflow, device, listener, null);
    }

    /**
     * Creates the runner of a resident photo accepted under a supervisor override; nothing happens until
     * {@link #start()}.
     *
     * @param workflow the resident workflow
     * @param device   the built-in camera
     * @param listener receiver of states and the outcome (same contract as {@link LivenessRunner})
     * @param override the override, or {@code null} for a photo whose workflow's liveness is off
     */
    public PhotoCaptureRunner(Workflow workflow, BuiltInCameraL0Device device, LivenessRunner.Listener listener,
                              SupervisorOverride override) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.device = Objects.requireNonNull(device, "device");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.override = override;
    }

    @Override
    public void start() {
        submit();
    }

    @Override
    public boolean retry() {
        if (closed || running) {
            return false;
        }
        submit();
        return true;
    }

    @Override
    public void cancel() {
        cancelled = true;
        closeSource();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cancel();
            worker.shutdownNow();
        }
    }

    private void submit() {
        cancelled = false;
        running = true;
        try {
            worker.execute(this::takePhoto);
        } catch (RejectedExecutionException e) {
            running = false;
        }
    }

    private void takePhoto() {
        listener.onUiState(LivenessUiState.builder(UiPhase.POSITIONING, MessageKeys.GUIDE_LOOK_CAMERA)
                .promptIcon(PromptIcon.FACE_OVAL).severity(Severity.INFO).build());
        AtomicReference<FaceFrame> latest = new AtomicReference<>();
        AtomicReference<DeviceError> error = new AtomicReference<>();
        FaceFrame frame = null;
        try {
            FaceFrameSource s = device.openStream(StreamRequest.builder().build());
            source.set(s);
            s.start(new LatestFrame(latest, error));
            long start = System.nanoTime();
            while (!cancelled && error.get() == null && elapsedMs(start) < FIRST_FRAME_TIMEOUT_MS
                    && (latest.get() == null || elapsedMs(start) < SETTLE_MS)) {
                Thread.sleep(50L);
            }
            closeSource();
            frame = latest.getAndSet(null);
            if (cancelled) {
                finish(LivenessCaptureResult.builder(workflow, Decision.CANCELLED)
                        .failureReason(FailureReason.CANCELLED).build());
            } else if (frame == null) {
                deviceError();
            } else {
                signed(frame);
            }
        } catch (DeviceException e) {
            Log.w(TAG, "Photo capture: " + e.getError().code());
            deviceError();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSource();
            if (frame != null) {
                frame.wipe();
            }
            running = false;
        }
    }

    private void signed(FaceFrame frame) throws DeviceException {
        CaptureRequest request = CaptureRequest.builder().transactionId(UUID.randomUUID().toString())
                .deviceSubId(StreamRequest.FACE_DEVICE_SUB_ID).sourceFrame(frame).build();
        SignedFaceCapture capture = device.capture(request, null, null);
        listener.onUiState(LivenessUiState.builder(UiPhase.SUCCESS, MessageKeys.FEEDBACK_SUCCESS)
                .promptIcon(PromptIcon.CHECK).severity(Severity.SUCCESS).progress(1.0).build());
        finish(passResult(workflow, override)
                .capture(capture.transactionId(), capture.isoRecord(), device.lastResponseJson(),
                        device.isProvisioned())
                .build());
    }

    /**
     * Starts the result of a photo taken without a liveness check.
     *
     * @param workflow the workflow
     * @param override the supervisor override, or {@code null}
     * @return a pass marked {@code LIVENESS_NOT_REQUIRED} without evidence, or marked {@code SUPERVISOR_OVERRIDE}
     *         with the override's evidence
     */
    static LivenessCaptureResult.Builder passResult(Workflow workflow, SupervisorOverride override) {
        LivenessCaptureResult.Builder b = LivenessCaptureResult.builder(workflow, Decision.PASS)
                .messageKey(MessageKeys.FEEDBACK_SUCCESS);
        if (override == null) {
            return b.reasonCodes(Collections.singletonList(LivenessCaptureResult.REASON_LIVENESS_NOT_REQUIRED));
        }
        return b.reasonCodes(Collections.singletonList(LivenessCaptureResult.REASON_SUPERVISOR_OVERRIDE))
                .evidence(override.evidence());
    }

    private void deviceError() {
        listener.onUiState(LivenessUiState.builder(UiPhase.DEVICE_ERROR, MessageKeys.ERROR_CAMERA_UNAVAILABLE)
                .promptIcon(PromptIcon.CAMERA_OFF).severity(Severity.ERROR).retryAvailable(true).build());
        finish(LivenessCaptureResult.builder(workflow, Decision.ERROR).failureReason(FailureReason.DEVICE)
                .messageKey(MessageKeys.ERROR_CAMERA_UNAVAILABLE)
                .reasonCodes(Arrays.asList("DEVICE_ERROR")).retryAllowed(!closed).build());
    }

    private void finish(LivenessCaptureResult result) {
        Log.i(TAG, "Photo capture ended: " + result);
        listener.onAttemptEnded(result);
    }

    private void closeSource() {
        FaceFrameSource s = source.getAndSet(null);
        if (s != null) {
            s.close();
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Keeps the newest frame; the previous one is wiped. */
    private static final class LatestFrame implements FrameSink {
        private final AtomicReference<FaceFrame> latest;
        private final AtomicReference<DeviceError> error;

        LatestFrame(AtomicReference<FaceFrame> latest, AtomicReference<DeviceError> error) {
            this.latest = latest;
            this.error = error;
        }

        @Override
        public void onFrame(FaceFrame frame) {
            FaceFrame old = latest.getAndSet(frame);
            if (old != null) {
                old.wipe();
            }
        }

        @Override
        public void onError(DeviceError e) {
            error.compareAndSet(null, e);
        }

        @Override
        public void onEnd(EndReason reason) {
            // The newest frame stays usable after the stream ends.
        }
    }
}
