package io.mosip.liveness.core.run;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.engine.LivenessTransaction;

import java.util.Objects;
import java.util.Optional;

/**
 * What one attempt of a {@link LivenessSessionDriver} ended with: the session result (when a session opened), the
 * transaction counters after recording it (attempts used, suspected-attack mark, low-light count), and what the client may offer next (Retry, the supervisor override or
 * the lock).
 *
 * <p>An attempt that could not open a session (engine not ready, device missing, liveness blocked on this device)
 * has no result and no session ID; {@link #failureReason()} is then {@link FailureReason#ENGINE} or
 * {@link FailureReason#DEVICE} and {@link #engineError()} or {@link #deviceError()} says why. Such attempts, like
 * every device, engine, quality and cancellation failure, never use an attempt. Immutable.
 */
public final class AttemptOutcome {

    private final Workflow workflow;
    private final String sessionId;
    private final LivenessResult result;
    private final Decision decision;
    private final FailureReason failureReason;
    private final DeviceErrorCode deviceError;
    private final EngineErrorCode engineError;
    private final int attempt;
    private final int attemptsUsed;
    private final int maxRetries;
    private final LivenessTransaction.Status transactionStatus;
    private final boolean retryAllowed;
    private final boolean overrideAllowed;
    private final boolean lockRequired;
    private final boolean suspectedAttack;
    private final int lowLightRetries;
    private final OnMaxFailure onMaxFailure;
    private final String messageKey;
    private final SignedFaceCapture capture;
    private final String tokenNonce;

    private AttemptOutcome(Builder b) {
        this.workflow = b.workflow;
        this.sessionId = b.sessionId;
        this.result = b.result;
        this.decision = b.decision;
        this.failureReason = b.failureReason;
        this.deviceError = b.deviceError;
        this.engineError = b.engineError;
        this.attempt = b.attempt;
        this.attemptsUsed = b.attemptsUsed;
        this.maxRetries = b.maxRetries;
        this.transactionStatus = b.transactionStatus;
        this.retryAllowed = b.retryAllowed;
        this.overrideAllowed = b.overrideAllowed;
        this.lockRequired = b.lockRequired;
        this.suspectedAttack = b.suspectedAttack;
        this.lowLightRetries = b.lowLightRetries;
        this.onMaxFailure = b.onMaxFailure;
        this.messageKey = b.messageKey;
        this.capture = b.capture;
        this.tokenNonce = b.tokenNonce;
    }

    /**
     * Builds the outcome of an attempt from the transaction after it recorded the result.
     *
     * @param workflow    the workflow
     * @param transaction the transaction, already updated
     * @param maxRetries  attempt limit of the workflow
     * @param attempt     attempt number the session ran as (or would have run as)
     * @return a builder
     */
    static Builder builder(Workflow workflow, LivenessTransaction transaction, int maxRetries, int attempt) {
        return new Builder(workflow, transaction, maxRetries, attempt);
    }

    /**
     * Returns the workflow.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return workflow;
    }

    /**
     * Tells whether the attempt passed: the session reached {@code PASSED}, which needs a bound signed capture.
     *
     * @return {@code true} on a pass
     */
    public boolean isPass() {
        return decision == Decision.PASS;
    }

    /**
     * Returns the coarse outcome.
     *
     * @return the decision; {@link Decision#CANCELLED} when the attempt was cancelled before it ended
     */
    public Decision decision() {
        return decision;
    }

    /**
     * Returns why the attempt failed.
     *
     * @return the reason; empty on a pass
     */
    public Optional<FailureReason> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /**
     * Returns the session identifier.
     *
     * @return the identifier; empty when no session opened
     */
    public Optional<String> sessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * Returns the session result.
     *
     * @return the result; empty when no session opened or the session ended without one
     */
    public Optional<LivenessResult> result() {
        return Optional.ofNullable(result);
    }

    /**
     * Returns the device error behind a {@code DEVICE} failure, from the session result or from the failed device
     * start.
     *
     * @return the code, if known
     */
    public Optional<DeviceErrorCode> deviceError() {
        return Optional.ofNullable(deviceError);
    }

    /**
     * Returns the engine error behind an {@code ENGINE} failure, from the session result or from the failed engine
     * start.
     *
     * @return the code, if known
     */
    public Optional<EngineErrorCode> engineError() {
        return Optional.ofNullable(engineError);
    }

    /**
     * Returns the attempt number the session ran as, earlier screens included.
     *
     * @return 1 to {@code max_retries}
     */
    public int attempt() {
        return attempt;
    }

    /**
     * Returns the counted failures of the transaction after this attempt, earlier screens included.
     *
     * @return attempts used
     */
    public int attemptsUsed() {
        return attemptsUsed;
    }

    /**
     * Returns the attempt limit of the workflow.
     *
     * @return {@code max_retries}
     */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Returns the transaction status after this attempt.
     *
     * @return the status
     */
    public LivenessTransaction.Status transactionStatus() {
        return transactionStatus;
    }

    /**
     * Tells whether the client may offer Retry ({@link LivenessSessionDriver#retry()}).
     *
     * @return {@code true} when the attempt failed, an attempt is left, the failure allows a retry and the driver is
     *         open
     */
    public boolean isRetryAllowed() {
        return retryAllowed;
    }

    /**
     * Tells whether a supervisor override may be offered ({@link LivenessTransaction#isOverrideAllowed()}).
     *
     * @return {@code true} only after the last attempt of a resident workflow with {@code SUPERVISOR_OVERRIDE} and
     *         no suspected attack in the transaction or the screens it continues
     */
    public boolean isOverrideAllowed() {
        return overrideAllowed;
    }

    /**
     * Tells whether the client must count a failed login ({@code BLOCK_AND_LOCK}).
     *
     * @return {@code true} after the last attempt of a workflow configured to lock
     */
    public boolean isLockRequired() {
        return lockRequired;
    }

    /**
     * Tells whether the transaction, or an earlier screen it continues, saw a PAD, integrity or binding failure. The
     * client keeps this with the counted failures so the next screen continues it.
     *
     * @return {@code true} after a suspected attack
     */
    public boolean isSuspectedAttack() {
        return suspectedAttack;
    }

    /**
     * Returns the uncounted low-light results of the transaction after this attempt
     * ({@link LivenessTransaction#lowLightRetries()}): the sessions that ended {@code LOW_LIGHT} since the last
     * counted failure, earlier screens included. The client keeps this with the counted failures and passes it to
     * {@link LivenessSessionDriver.Builder#priorFailures(int, boolean, int)} when it reopens the window for the
     * same subject.
     *
     * @return 0 or more; 0 after a counted failure
     */
    public int lowLightRetries() {
        return lowLightRetries;
    }

    /**
     * Returns the behaviour after the last failed attempt.
     *
     * @return the policy's {@code on_max_failure}
     */
    public OnMaxFailure onMaxFailure() {
        return onMaxFailure;
    }

    /**
     * Returns the message key of the last UI state of the attempt.
     *
     * @return the key; empty if the attempt showed none
     */
    public Optional<String> messageKey() {
        return Optional.ofNullable(messageKey);
    }

    /**
     * Returns the signed capture the client attached ({@link CaptureHandOff#attachCapture}).
     *
     * @return the capture on a pass when one was attached; empty otherwise
     */
    public Optional<SignedFaceCapture> capture() {
        return Optional.ofNullable(capture);
    }

    /**
     * Returns the nonce of the pass token the capture was bound with.
     *
     * @return the nonce on a pass; empty otherwise
     */
    public Optional<String> tokenNonce() {
        return Optional.ofNullable(tokenNonce);
    }

    /**
     * Describes the outcome without subject data or images.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "AttemptOutcome[" + workflow.id() + ", " + decision + (failureReason == null ? "" : "(" + failureReason
                + ")") + ", attempt " + attempt + ", used " + attemptsUsed + "/" + maxRetries + ", " + transactionStatus
                + ", retry=" + retryAllowed + ", override=" + overrideAllowed + ", lock=" + lockRequired
                + ", attack=" + suspectedAttack + ", lowLight=" + lowLightRetries + (sessionId == null ? ", no session" : ", session " + sessionId)
                + (deviceError == null ? "" : ", device " + deviceError)
                + (engineError == null ? "" : ", engine " + engineError) + "]";
    }

    /** Builder used by the driver. */
    static final class Builder {

        private final Workflow workflow;
        private final int attempt;
        private final int attemptsUsed;
        private final int maxRetries;
        private final LivenessTransaction.Status transactionStatus;
        private final boolean suspectedAttack;
        private final int lowLightRetries;
        private final OnMaxFailure onMaxFailure;
        private final boolean overrideAllowed;
        private final boolean lockRequired;
        private String sessionId;
        private LivenessResult result;
        private Decision decision = Decision.CANCELLED;
        private FailureReason failureReason = FailureReason.CANCELLED;
        private DeviceErrorCode deviceError;
        private EngineErrorCode engineError;
        private boolean retryAllowed;
        private String messageKey;
        private SignedFaceCapture capture;
        private String tokenNonce;

        private Builder(Workflow workflow, LivenessTransaction transaction, int maxRetries, int attempt) {
            this.workflow = Objects.requireNonNull(workflow, "workflow");
            this.attempt = attempt;
            this.attemptsUsed = transaction.attemptsUsed();
            this.transactionStatus = transaction.status();
            this.suspectedAttack = transaction.isSuspectedAttack();
            this.lowLightRetries = transaction.lowLightRetries();
            this.onMaxFailure = transaction.onMaxFailure();
            this.overrideAllowed = transaction.isOverrideAllowed();
            this.lockRequired = transaction.isLockRequired();
            this.maxRetries = maxRetries;
        }

        Builder result(String sessionId, LivenessResult result) {
            this.sessionId = sessionId;
            this.result = result;
            if (result != null) {
                this.decision = result.decision();
                this.failureReason = result.failureReason().orElse(null);
                this.deviceError = result.deviceError().orElse(null);
                this.engineError = result.engineError().orElse(null);
            }
            return this;
        }

        Builder noSession(FailureReason reason, DeviceErrorCode device, EngineErrorCode engine) {
            this.decision = reason.decision();
            this.failureReason = reason;
            this.deviceError = device;
            this.engineError = engine;
            return this;
        }

        Builder retryAllowed(boolean retryAllowed) {
            this.retryAllowed = retryAllowed;
            return this;
        }

        Builder messageKey(String messageKey) {
            this.messageKey = messageKey;
            return this;
        }

        Builder pass(SignedFaceCapture capture, String tokenNonce) {
            this.capture = capture;
            this.tokenNonce = tokenNonce;
            return this;
        }

        AttemptOutcome build() {
            boolean pass = decision == Decision.PASS;
            if (pass) {
                retryAllowed = false;
            } else {
                capture = null;
                tokenNonce = null;
            }
            return new AttemptOutcome(this);
        }
    }
}
