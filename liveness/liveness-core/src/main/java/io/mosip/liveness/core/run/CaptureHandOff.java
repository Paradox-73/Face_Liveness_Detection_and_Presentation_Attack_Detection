package io.mosip.liveness.core.run;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingRequest;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.LivenessPassToken;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.SignedFaceCapture;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * The capture step of one attempt, handed to the client's {@link LivenessSessionDriver.CaptureHandler} when the
 * session reaches {@code CAPTURE_READY}.
 *
 * <p>The client takes the signed capture and calls {@link #bind}; only a bound capture can pass. It may do so inside
 * {@code onCaptureReady} on the driver's worker thread (the Android built-in camera signs the session's own frame) or
 * later from another thread (the desktop liveness gate binds when the registration screen takes its capture). Calls
 * from another thread are moved to the worker, which owns the session. While the hand-off waits the driver keeps
 * calling {@code tick()}, so the pass token still expires on time. Methods are safe from any thread unless stated.
 */
public final class CaptureHandOff {

    private final LivenessSessionDriver driver;
    private final LivenessSessionDriver.Attempt attempt;
    private final LivenessPassToken token;

    CaptureHandOff(LivenessSessionDriver driver, LivenessSessionDriver.Attempt attempt, LivenessPassToken token) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.token = Objects.requireNonNull(token, "token");
    }

    /**
     * Returns the single-use pass token the capture must be bound with; its nonce goes into the device's PAD request.
     *
     * @return the token
     */
    public LivenessPassToken passToken() {
        return token;
    }

    /**
     * Returns the session identifier.
     *
     * @return the identifier
     */
    public String sessionId() {
        return token.sessionId();
    }

    /**
     * Returns the workflow.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return token.workflow();
    }

    /**
     * Returns the attempt number of the session, earlier screens included.
     *
     * @return 1 to {@code max_retries}
     */
    public int attempt() {
        return attempt.number;
    }

    /**
     * Returns a copy of the frame the session selected for storage; an in-process device signs this frame instead of
     * taking a new picture. The copy belongs to the caller, who wipes it after use.
     *
     * @return the copy; empty once the session has ended
     * @throws IllegalStateException if called outside {@code onCaptureReady} (the session belongs to the worker)
     */
    public Optional<FaceFrame> captureFrame() {
        return driver.captureFrame(attempt);
    }

    /**
     * Tells the session that the signed capture has started ({@code CAPTURE_READY -> CAPTURING}). Optional:
     * {@link #bind} does it too.
     */
    public void captureStarted() {
        driver.captureStarted(attempt);
    }

    /**
     * Ends the attempt with a device failure because the signed capture failed; it does not use an attempt.
     *
     * @param error the device error
     */
    public void captureFailed(DeviceError error) {
        driver.captureFailed(attempt, Objects.requireNonNull(error, "error"));
    }

    /**
     * Keeps the signed capture for the attempt's outcome ({@link AttemptOutcome#capture()} on a pass).
     *
     * @param capture the capture
     */
    public void attachCapture(SignedFaceCapture capture) {
        attempt.capture = Objects.requireNonNull(capture, "capture");
    }

    /**
     * Checks the signed capture against the live session ({@code LivenessSession.bind}); the attempt then ends with
     * a pass or a binding or PAD failure. The token is consumed whatever the verdict.
     *
     * @param request   the capture and the presented token
     * @param timeoutMs longest wait for the worker when called from another thread
     * @return the verdict and the session result; a failed verdict ({@code BINDING}, {@code TOKEN_REUSED}) when the
     *         attempt has already ended
     * @throws TimeoutException if the worker did not answer in time or the driver is closed
     */
    public BindOutcome bind(BindingRequest request, long timeoutMs) throws TimeoutException {
        return driver.bind(attempt, Objects.requireNonNull(request, "request"), timeoutMs);
    }

    /**
     * Tells whether the attempt has ended.
     *
     * @return {@code true} once the outcome was built
     */
    public boolean isDone() {
        return attempt.finished;
    }

    /** Verdict of a {@link #bind} call and the session result it produced. */
    public static final class BindOutcome {

        private final BindingVerdict verdict;
        private final LivenessResult result;

        BindOutcome(BindingVerdict verdict, LivenessResult result) {
            this.verdict = Objects.requireNonNull(verdict, "verdict");
            this.result = result;
        }

        /**
         * Returns the binding verdict.
         *
         * @return the verdict
         */
        public BindingVerdict verdict() {
            return verdict;
        }

        /**
         * Returns the session result after binding.
         *
         * @return the result; empty if the session has none
         */
        public Optional<LivenessResult> result() {
            return Optional.ofNullable(result);
        }
    }
}
