package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;

import java.util.Objects;
import java.util.Optional;

/**
 * Retry counting for one workflow and one subject (design section 4.5, "Retry counting").
 *
 * <p>The client keeps one transaction while a person tries to pass, opens each session with
 * {@link #nextAttempt()} as its attempt number, and records every result. Failures for PAD, integrity, active
 * challenges, timeouts, below-threshold scores and binding use up an attempt; device, engine, quality and
 * cancellation failures do not, so the next session keeps the same attempt number. After
 * {@code max_retries} counted failures the transaction is {@link Status#EXHAUSTED} and
 * {@code <workflow>.on_max_failure} applies: {@link #isOverrideAllowed()} for a resident supervisor override
 * (never after a suspected attack) or {@link #isLockRequired()} for an authentication lock. Not thread-safe.
 */
public final class LivenessTransaction {

    /** State of the transaction. */
    public enum Status {
        /** Another session may be opened. */
        IN_PROGRESS,
        /** A session passed; no more sessions are needed. */
        PASSED,
        /** All attempts are used; the {@code on_max_failure} behaviour applies. */
        EXHAUSTED
    }

    private final LivenessPolicy policy;
    private int countedFailures;
    private int sessions;
    private Status status = Status.IN_PROGRESS;
    private FailureReason lastFailure;

    /**
     * Starts a transaction.
     *
     * @param policy resolved policy of the workflow
     */
    public LivenessTransaction(LivenessPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Returns the attempt number for the next session.
     *
     * @return counted failures + 1
     * @throws IllegalStateException if the transaction has passed or is exhausted
     */
    public int nextAttempt() {
        if (status != Status.IN_PROGRESS) {
            throw new IllegalStateException("no further attempt: " + status);
        }
        return countedFailures + 1;
    }

    /**
     * Records the result of a session of this transaction.
     *
     * @param result the result
     * @return the new status
     * @throws IllegalStateException    if the transaction has already passed or is exhausted
     * @throws IllegalArgumentException if the result belongs to another workflow
     */
    public Status record(LivenessResult result) {
        Objects.requireNonNull(result, "result");
        if (status != Status.IN_PROGRESS) {
            throw new IllegalStateException("transaction already " + status);
        }
        if (result.workflow() != policy.workflow()) {
            throw new IllegalArgumentException("result of another workflow: " + result.workflow().id());
        }
        sessions++;
        if (result.isPass()) {
            status = Status.PASSED;
            return status;
        }
        lastFailure = result.failureReason().get();
        if (lastFailure.countsAsAttempt()) {
            countedFailures++;
            if (countedFailures >= policy.maxRetries()) {
                status = Status.EXHAUSTED;
            }
        }
        return status;
    }

    /**
     * Returns the status.
     *
     * @return the status
     */
    public Status status() {
        return status;
    }

    /**
     * Tells whether another session may be opened.
     *
     * @return {@code true} while in progress
     */
    public boolean canRetry() {
        return status == Status.IN_PROGRESS;
    }

    /**
     * Returns the number of attempts used (counted failures).
     *
     * @return attempts used
     */
    public int attemptsUsed() {
        return countedFailures;
    }

    /**
     * Returns the number of sessions recorded, including failures that did not use an attempt.
     *
     * @return session count
     */
    public int sessions() {
        return sessions;
    }

    /**
     * Returns the reason of the last failure.
     *
     * @return the reason, if a session failed
     */
    public Optional<FailureReason> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    /**
     * Returns the behaviour after the last failed attempt.
     *
     * @return the policy's {@code on_max_failure}
     */
    public OnMaxFailure onMaxFailure() {
        return policy.onMaxFailure();
    }

    /**
     * Tells whether a supervisor may accept the capture now: attempts exhausted, a resident workflow configured
     * with {@code SUPERVISOR_OVERRIDE}, and a last failure that was not a suspected attack.
     *
     * @return {@code true} if an override may be offered
     */
    public boolean isOverrideAllowed() {
        return status == Status.EXHAUSTED && policy.onMaxFailure() == OnMaxFailure.SUPERVISOR_OVERRIDE
                && policy.workflow().isResident() && lastFailure != null && lastFailure.allowsOverride();
    }

    /**
     * Tells whether the client must also count a failed login ({@code BLOCK_AND_LOCK}).
     *
     * @return {@code true} if attempts are exhausted and the workflow is configured to lock
     */
    public boolean isLockRequired() {
        return status == Status.EXHAUSTED && policy.onMaxFailure() == OnMaxFailure.BLOCK_AND_LOCK;
    }
}
