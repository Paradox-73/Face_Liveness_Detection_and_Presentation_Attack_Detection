/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;

import java.util.Objects;
import java.util.Optional;

/**
 * Retry counting for one workflow and one subject (docs/design/engine-interface.md section 4).
 *
 * <p>The client keeps one transaction while a person tries to pass, opens each session with
 * {@link #nextAttempt()} as its attempt number, and records every result. Failures for PAD, integrity, active
 * challenges, timeouts, below-threshold scores and binding use up an attempt; device, engine, quality and
 * cancellation failures do not, so the next session keeps the same attempt number. After
 * {@code max_retries} counted failures the transaction is {@link Status#EXHAUSTED} and
 * {@code <workflow>.on_max_failure} applies: {@link #isOverrideAllowed()} for a resident supervisor override
 * or {@link #isLockRequired()} for an authentication lock.
 *
 * <p>A PAD, integrity or binding failure marks the transaction as a suspected attack for the rest of its life
 * ({@link #isSuspectedAttack()}): later timeouts or failed challenges do not clear the mark, so an attacker cannot
 * earn a supervisor override by letting the last attempt run out.
 *
 * <p>A session that ended uncounted because passive PAD failed in low light
 * ({@link LivenessResult#isLowLightRetry()}) raises {@link #lowLightRetries()}; the next counted failure resets it.
 * The client passes the value to {@link io.mosip.liveness.core.api.SessionRequest.Builder#lowLightRetries(int)},
 * so after {@code passive.low_light_max_uncounted} such results the next low-light PAD failure counts again and
 * darkness cannot buy unlimited attempts.
 *
 * <p>A transaction can continue the counted failures of an earlier screen or window for the same workflow and
 * subject ({@link #LivenessTransaction(LivenessPolicy, int, boolean)}): attempt numbers, the attempt limit and the
 * suspected-attack mark then cover both, so closing a screen never gives new attempts or clears the mark. It can
 * also continue the earlier screens' uncounted low-light results
 * ({@link #LivenessTransaction(LivenessPolicy, int, boolean, int)}), so reopening a screen never restarts the
 * low-light allowance either. Not thread-safe.
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
    private final int priorCountedFailures;
    private final int priorLowLightRetries;
    private int countedFailures;
    private int sessions;
    private int lowLightRetries;
    private Status status = Status.IN_PROGRESS;
    private FailureReason lastFailure;
    private boolean suspectedAttack;

    /**
     * Starts a transaction.
     *
     * @param policy resolved policy of the workflow
     */
    public LivenessTransaction(LivenessPolicy policy) {
        this(policy, 0, false);
    }

    /**
     * Starts a transaction that continues the counted failures of earlier screens or windows for the same workflow
     * and subject. {@link #nextAttempt()} and {@link #attemptsUsed()} include them, the transaction is exhausted
     * after {@code max_retries} counted failures in total, and a prior suspected attack keeps
     * {@link #isOverrideAllowed()} false.
     *
     * @param policy               resolved policy of the workflow
     * @param priorCountedFailures counted failures of earlier screens, 0 to {@code max_retries - 1}
     * @param priorSuspectedAttack whether one of the earlier failures was a PAD, integrity or binding failure
     * @throws IllegalArgumentException if {@code priorCountedFailures} is negative or leaves no attempt (the caller
     *                                  applies {@code on_max_failure} without opening a transaction)
     */
    public LivenessTransaction(LivenessPolicy policy, int priorCountedFailures, boolean priorSuspectedAttack) {
        this(policy, priorCountedFailures, priorSuspectedAttack, 0);
    }

    /**
     * Starts a transaction that continues the counted failures, the suspected-attack mark and the uncounted
     * low-light results of earlier screens or windows for the same workflow and subject. {@link #lowLightRetries()}
     * starts at {@code priorLowLightRetries}, so the low-light allowance ({@code passive.low_light_max_uncounted})
     * covers the earlier screens too; the next counted failure resets it as usual.
     *
     * <p>The transaction does not know the signed {@code passive.low_light_max_uncounted}; it accepts up to the
     * largest value that parameter may take ({@link EngineParameter#LOW_LIGHT_MAX_UNCOUNTED}). A carried count at
     * or above the signed value makes the next low-light PAD failure count, as in one screen.
     *
     * @param policy               resolved policy of the workflow
     * @param priorCountedFailures counted failures of earlier screens, 0 to {@code max_retries - 1}
     * @param priorSuspectedAttack whether one of the earlier failures was a PAD, integrity or binding failure
     * @param priorLowLightRetries uncounted low-light results of earlier screens since their last counted failure
     *                             ({@link #lowLightRetries()} of the earlier transaction), 0 to the largest allowed
     *                             {@code passive.low_light_max_uncounted}
     * @throws IllegalArgumentException if {@code priorCountedFailures} is negative or leaves no attempt (the caller
     *                                  applies {@code on_max_failure} without opening a transaction), or if
     *                                  {@code priorLowLightRetries} is outside its range
     */
    public LivenessTransaction(LivenessPolicy policy, int priorCountedFailures, boolean priorSuspectedAttack,
            int priorLowLightRetries) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (priorCountedFailures < 0 || priorCountedFailures >= policy.maxRetries()) {
            throw new IllegalArgumentException("prior counted failures must be between 0 and max_retries - 1 ("
                    + (policy.maxRetries() - 1) + "): " + priorCountedFailures);
        }
        checkPriorLowLightRetries(priorLowLightRetries);
        this.priorCountedFailures = priorCountedFailures;
        this.countedFailures = priorCountedFailures;
        this.suspectedAttack = priorSuspectedAttack;
        this.priorLowLightRetries = priorLowLightRetries;
        this.lowLightRetries = priorLowLightRetries;
    }

    private static void checkPriorLowLightRetries(int priorLowLightRetries) {
        int max = maxPriorLowLightRetries();
        if (priorLowLightRetries < 0 || priorLowLightRetries > max) {
            throw new IllegalArgumentException("prior low-light retries must be between 0 and " + max + ": "
                    + priorLowLightRetries);
        }
    }

    /**
     * Returns the largest low-light count a transaction may continue: the upper bound of the signed engine
     * parameter {@code passive.low_light_max_uncounted}.
     *
     * @return the bound
     */
    public static int maxPriorLowLightRetries() {
        return (int) EngineParameter.LOW_LIGHT_MAX_UNCOUNTED.max();
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
        if (!lastFailure.allowsOverride()) {
            suspectedAttack = true;
        }
        if (result.isLowLightRetry()) {
            lowLightRetries++;
        }
        if (lastFailure.countsAsAttempt()) {
            lowLightRetries = 0;
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
     * Returns the number of attempts used (counted failures), including those of earlier screens.
     *
     * @return attempts used
     */
    public int attemptsUsed() {
        return countedFailures;
    }

    /**
     * Returns the counted failures this transaction continued from earlier screens.
     *
     * @return 0 for a new transaction
     */
    public int priorCountedFailures() {
        return priorCountedFailures;
    }

    /**
     * Returns the uncounted low-light results this transaction continued from earlier screens.
     *
     * @return 0 for a new transaction
     */
    public int priorLowLightRetries() {
        return priorLowLightRetries;
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
     * Returns the number of sessions that ended uncounted for low light since the last counted failure, including
     * those of earlier screens this transaction continues, for
     * {@link io.mosip.liveness.core.api.SessionRequest.Builder#lowLightRetries(int)} of the next session. The client
     * keeps it with the counted failures so the next screen continues it.
     *
     * @return 0 or more
     */
    public int lowLightRetries() {
        return lowLightRetries;
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
     * Tells whether any session of this transaction, or an earlier screen it continues, failed for PAD, integrity
     * or binding. The mark stays set whatever the later sessions return.
     *
     * @return {@code true} after a suspected attack
     */
    public boolean isSuspectedAttack() {
        return suspectedAttack;
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
     * with {@code SUPERVISOR_OVERRIDE}, and no session of this transaction failed for PAD, integrity or binding
     * ({@link #isSuspectedAttack()}), whatever the last failure was.
     *
     * @return {@code true} if an override may be offered
     */
    public boolean isOverrideAllowed() {
        return status == Status.EXHAUSTED && overrideAllowed(policy, lastFailure, suspectedAttack);
    }

    /**
     * The override rule, once for the transaction and the screen ({@link LivenessUiStateMapper}): a resident
     * workflow configured with {@code SUPERVISOR_OVERRIDE}, a last failure that allows it, and no suspected attack in
     * any session of the transaction.
     *
     * @param policy          the workflow's policy
     * @param lastFailure     the last failure, or {@code null}
     * @param suspectedAttack whether any session failed for PAD, integrity or binding
     * @return {@code true} if an override may follow the last attempt
     */
    static boolean overrideAllowed(LivenessPolicy policy, FailureReason lastFailure, boolean suspectedAttack) {
        return policy.onMaxFailure() == OnMaxFailure.SUPERVISOR_OVERRIDE && policy.workflow().isResident()
                && !suspectedAttack && lastFailure != null && lastFailure.allowsOverride();
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
