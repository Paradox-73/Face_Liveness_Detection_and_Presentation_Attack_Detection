package io.mosip.registration.service.bio.liveness;

import java.util.Objects;
import java.util.Optional;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.engine.LivenessTransaction;

/**
 * End of one liveness session within a {@link LivenessRun}, with what the retry policy allows next.
 *
 * <p>Detailed reasons and scores stay in the {@link #result()} for audits, diagnostics and packet evidence; a
 * screen shows only the {@code LivenessUiState} it already received.
 */
public final class LivenessOutcome {

	private final Workflow workflow;
	private final LivenessResult result;
	private final FailureReason failureReason;
	private final String errorCode;
	private final LivenessTransaction.Status status;
	private final int attemptsUsed;
	private final int maxAttempts;
	private final boolean retryAllowed;
	private final boolean overrideAllowed;
	private final boolean lockRequired;

	private LivenessOutcome(Builder b) {
		this.workflow = b.workflow;
		this.result = b.result;
		this.failureReason = b.failureReason;
		this.errorCode = b.errorCode;
		this.status = b.status;
		this.attemptsUsed = b.attemptsUsed;
		this.maxAttempts = b.maxAttempts;
		this.retryAllowed = b.retryAllowed;
		this.overrideAllowed = b.overrideAllowed;
		this.lockRequired = b.lockRequired;
	}

	static Builder builder(Workflow workflow, LivenessTransaction transaction, int maxAttempts) {
		return new Builder(workflow, transaction, maxAttempts);
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
	 * Returns the session result.
	 *
	 * @return the result; empty when no session could be opened (engine or device unavailable)
	 */
	public Optional<LivenessResult> result() {
		return Optional.ofNullable(result);
	}

	/**
	 * Tells whether liveness passed and the capture was accepted.
	 *
	 * @return {@code true} for a pass
	 */
	public boolean isPassed() {
		return failureReason == null;
	}

	/**
	 * Returns why the session did not pass.
	 *
	 * @return the failure reason; empty for a pass
	 */
	public Optional<FailureReason> failureReason() {
		return Optional.ofNullable(failureReason);
	}

	/**
	 * Returns the client error code of a failure that happened before a session existed.
	 *
	 * @return a {@code REG-LIV-*} code, if any
	 */
	public Optional<String> errorCode() {
		return Optional.ofNullable(errorCode);
	}

	/**
	 * Returns the state of the retry transaction.
	 *
	 * @return in progress, passed or exhausted
	 */
	public LivenessTransaction.Status status() {
		return status;
	}

	/**
	 * Returns the attempts used so far (failures that count against {@code max_retries}).
	 *
	 * @return used attempts
	 */
	public int attemptsUsed() {
		return attemptsUsed;
	}

	/**
	 * Returns the configured maximum number of attempts.
	 *
	 * @return {@code max_retries} of the workflow
	 */
	public int maxAttempts() {
		return maxAttempts;
	}

	/**
	 * Tells whether {@link LivenessRun#retry()} may be called.
	 *
	 * @return {@code true} if another session may be opened
	 */
	public boolean isRetryAllowed() {
		return retryAllowed;
	}

	/**
	 * Tells whether a supervisor may accept the resident's face without a pass ({@code SUPERVISOR_OVERRIDE}).
	 *
	 * @return {@code true} only for resident workflows, after the last attempt, and never after a suspected attack
	 */
	public boolean isOverrideAllowed() {
		return overrideAllowed;
	}

	/**
	 * Tells whether the operator account must be locked ({@code BLOCK_AND_LOCK} after the last attempt).
	 *
	 * @return {@code true} if the invalid-login rule applies
	 */
	public boolean isLockRequired() {
		return lockRequired;
	}

	@Override
	public String toString() {
		return "LivenessOutcome[" + workflow.id() + ", " + (isPassed() ? "PASSED" : "FAILED(" + failureReason + ")")
				+ ", " + status + ", attempts=" + attemptsUsed + "/" + maxAttempts + ", retry=" + retryAllowed
				+ ", override=" + overrideAllowed + ", lock=" + lockRequired + "]";
	}

	static final class Builder {

		private final Workflow workflow;
		private final int maxAttempts;
		private LivenessResult result;
		private FailureReason failureReason;
		private String errorCode;
		private LivenessTransaction.Status status;
		private int attemptsUsed;
		private boolean retryAllowed;
		private boolean overrideAllowed;
		private boolean lockRequired;

		private Builder(Workflow workflow, LivenessTransaction transaction, int maxAttempts) {
			this.workflow = Objects.requireNonNull(workflow, "workflow");
			this.maxAttempts = maxAttempts;
			this.status = transaction.status();
			this.attemptsUsed = transaction.attemptsUsed();
			this.retryAllowed = transaction.canRetry();
			this.overrideAllowed = transaction.isOverrideAllowed();
			this.lockRequired = transaction.isLockRequired();
		}

		Builder result(LivenessResult result) {
			this.result = result;
			this.failureReason = result.failureReason().orElse(null);
			return this;
		}

		Builder failure(FailureReason reason, String errorCode) {
			this.failureReason = reason;
			this.errorCode = errorCode;
			return this;
		}

		Builder retryAllowed(boolean retryAllowed) {
			this.retryAllowed = retryAllowed;
			return this;
		}

		LivenessOutcome build() {
			return new LivenessOutcome(this);
		}
	}
}
