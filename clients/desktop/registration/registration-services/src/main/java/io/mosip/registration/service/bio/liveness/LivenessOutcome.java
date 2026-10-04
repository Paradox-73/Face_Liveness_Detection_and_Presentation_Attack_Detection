/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.util.Objects;
import java.util.Optional;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.engine.LivenessTransaction;
import io.mosip.liveness.core.run.AttemptOutcome;

/**
 * End of one liveness session within a {@link LivenessRun}, with what the retry policy allows next: the
 * {@link AttemptOutcome} of the shared session driver plus the client's {@code REG-LIV-*} error code.
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
	private final int attempt;
	private final int attemptsUsed;
	private final int maxAttempts;
	private final boolean retryAllowed;
	private final boolean overrideAllowed;
	private final boolean lockRequired;
	private final boolean suspectedAttack;
	private final int lowLightRetries;

	private LivenessOutcome(AttemptOutcome o, String errorCode) {
		this.workflow = o.workflow();
		this.result = o.result().orElse(null);
		this.failureReason = o.failureReason().orElse(null);
		this.errorCode = errorCode;
		this.status = o.transactionStatus();
		this.attempt = o.attempt();
		this.attemptsUsed = o.attemptsUsed();
		this.maxAttempts = o.maxRetries();
		this.retryAllowed = o.isRetryAllowed();
		this.overrideAllowed = o.isOverrideAllowed();
		this.lockRequired = o.isLockRequired();
		this.suspectedAttack = o.isSuspectedAttack();
		this.lowLightRetries = o.lowLightRetries();
	}

	/**
	 * Wraps the outcome of an attempt of the shared session driver.
	 *
	 * @param attempt   the driver's outcome
	 * @param errorCode {@code REG-LIV-*} code of a failure before a session existed, or {@code null}
	 * @return the outcome
	 */
	static LivenessOutcome of(AttemptOutcome attempt, String errorCode) {
		return new LivenessOutcome(Objects.requireNonNull(attempt, "attempt"), errorCode);
	}

	/**
	 * Returns the attempt number the session ran as, earlier liveness windows of the same workflow and person
	 * included.
	 *
	 * @return 1 to {@link #maxAttempts()}
	 */
	public int attempt() {
		return attempt;
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
	 * Returns the attempts used so far (failures that count against {@code max_retries}), including those of earlier
	 * liveness windows for the same workflow and person.
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

	/**
	 * Tells whether a counted failure of this transaction, or of an earlier liveness window for the same workflow and
	 * person, was a suspected attack (PAD, integrity or binding). While it is set no supervisor override is offered.
	 *
	 * @return {@code true} after a suspected attack
	 */
	public boolean isSuspectedAttack() {
		return suspectedAttack;
	}

	/**
	 * Tells whether the session ended for low light without using up an attempt ({@code QUALITY} with
	 * {@code LOW_LIGHT}): the screen shows "Improve lighting." and the next try keeps the attempt number.
	 *
	 * @return {@code true} for an uncounted low-light result
	 */
	public boolean isLowLightRetry() {
		return result != null && result.isLowLightRetry();
	}

	/**
	 * Returns the uncounted low-light results of the transaction since its last counted failure, earlier liveness
	 * windows of the same workflow and person included. Once it reaches {@code passive.low_light_max_uncounted} the
	 * next low-light PAD failure counts as an attempt.
	 *
	 * @return 0 or more; 0 after a counted failure
	 */
	public int lowLightRetries() {
		return lowLightRetries;
	}

	@Override
	public String toString() {
		return "LivenessOutcome[" + workflow.id() + ", " + (isPassed() ? "PASSED" : "FAILED(" + failureReason + ")")
				+ ", " + status + ", attempt " + attempt + ", attempts=" + attemptsUsed + "/" + maxAttempts + ", retry=" + retryAllowed
				+ ", override=" + overrideAllowed + ", lock=" + lockRequired + ", attack=" + suspectedAttack + ", lowLight=" + lowLightRetries + "]";
	}

}
