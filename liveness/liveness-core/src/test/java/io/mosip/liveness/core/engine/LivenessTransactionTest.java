/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.ui.MessageKeys;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-CORE-TX-03: a PAD, integrity or binding failure marks the whole transaction as a suspected attack, so a later
 * timeout or failed challenge on the last attempt cannot unlock the supervisor override. A transaction continued
 * from an earlier screen also continues that screen's low-light count (audit fix 17), so reopening a window never
 * restarts the low-light allowance.
 */
class LivenessTransactionTest {

    private static final LivenessPolicy RESIDENT_OVERRIDE = LivenessPolicy.defaults(Workflow.REGISTRATION).toBuilder()
            .onMaxFailure(OnMaxFailure.SUPERVISOR_OVERRIDE).maxRetries(3).build();

    private static LivenessResult failed(FailureReason reason) {
        return LivenessResult.builder("s", Workflow.REGISTRATION, SessionHarness.engineInfo(),
                SessionHarness.device(false, true)).method(LivenessMethod.ACTIVE)
                .period(FakeClock.EPOCH, FakeClock.EPOCH).fail(reason).build();
    }

    private static LivenessTransaction run(FailureReason... reasons) {
        LivenessTransaction tx = new LivenessTransaction(RESIDENT_OVERRIDE);
        for (FailureReason r : reasons) {
            tx.record(failed(r));
        }
        assertEquals(LivenessTransaction.Status.EXHAUSTED, tx.status());
        return tx;
    }

    @Test
    void padThenTimeoutRefusesTheOverride() {
        LivenessTransaction tx = run(FailureReason.PAD, FailureReason.TIMEOUT, FailureReason.TIMEOUT);
        assertEquals(FailureReason.TIMEOUT, tx.lastFailure().get());
        assertTrue(tx.isSuspectedAttack());
        assertFalse(tx.isOverrideAllowed(), "an earlier PAD failure forbids the override");
    }

    @Test
    void integrityThenActiveRefusesTheOverride() {
        LivenessTransaction tx = run(FailureReason.INTEGRITY, FailureReason.ACTIVE, FailureReason.ACTIVE);
        assertEquals(FailureReason.ACTIVE, tx.lastFailure().get());
        assertTrue(tx.isSuspectedAttack());
        assertFalse(tx.isOverrideAllowed(), "an earlier integrity failure forbids the override");
    }

    @Test
    void bindingThenTimeoutRefusesTheOverride() {
        LivenessTransaction tx = run(FailureReason.TIMEOUT, FailureReason.BINDING, FailureReason.TIMEOUT);
        assertEquals(FailureReason.TIMEOUT, tx.lastFailure().get());
        assertTrue(tx.isSuspectedAttack());
        assertFalse(tx.isOverrideAllowed(), "an earlier binding failure forbids the override");
    }

    @Test
    void threeTimeoutsAllowTheOverride() {
        LivenessTransaction tx = run(FailureReason.TIMEOUT, FailureReason.TIMEOUT, FailureReason.TIMEOUT);
        assertFalse(tx.isSuspectedAttack());
        assertTrue(tx.isOverrideAllowed());
    }

    @Test
    void uncountedFailuresNeverSetTheMark() {
        LivenessTransaction tx = new LivenessTransaction(RESIDENT_OVERRIDE);
        for (FailureReason r : new FailureReason[] {FailureReason.DEVICE, FailureReason.QUALITY,
                FailureReason.ENGINE, FailureReason.CANCELLED, FailureReason.ACTIVE, FailureReason.BELOW_THRESHOLD}) {
            tx.record(failed(r));
            assertFalse(tx.isSuspectedAttack(), r.name());
        }
        tx.record(failed(FailureReason.TIMEOUT));
        assertEquals(LivenessTransaction.Status.EXHAUSTED, tx.status());
        assertTrue(tx.isOverrideAllowed());
    }

    @Test
    void theMarkIsSetBeforeTheAttemptsRunOut() {
        LivenessTransaction tx = new LivenessTransaction(RESIDENT_OVERRIDE);
        assertFalse(tx.isSuspectedAttack());
        tx.record(failed(FailureReason.PAD));
        assertTrue(tx.isSuspectedAttack());
        assertTrue(tx.canRetry(), "a suspected attack still uses only one attempt");
        assertFalse(tx.isOverrideAllowed(), "no override before the attempts are used");
    }

    private static LivenessResult lowLight() {
        return LivenessResult.builder("s", Workflow.REGISTRATION, SessionHarness.engineInfo(),
                SessionHarness.device(false, true)).method(LivenessMethod.PASSIVE)
                .period(FakeClock.EPOCH, FakeClock.EPOCH).fail(FailureReason.QUALITY).reason(ReasonCode.LOW_LIGHT)
                .build();
    }

    /** Low-light results add up across uncounted failures and restart only after a counted failure. */
    @Test
    void lowLightRetriesRestartOnlyAfterACountedFailure() {
        LivenessTransaction tx = new LivenessTransaction(RESIDENT_OVERRIDE);
        assertEquals(0, tx.lowLightRetries());
        tx.record(lowLight());
        tx.record(failed(FailureReason.DEVICE));
        tx.record(failed(FailureReason.CANCELLED));
        tx.record(lowLight());
        assertEquals(2, tx.lowLightRetries());
        assertEquals(0, tx.attemptsUsed());
        assertFalse(tx.isSuspectedAttack(), "a low-light result is not an attack verdict");
        tx.record(failed(FailureReason.PAD));
        assertEquals(0, tx.lowLightRetries());
        assertEquals(1, tx.attemptsUsed());
        assertFalse(failed(FailureReason.QUALITY).isLowLightRetry());
        assertTrue(lowLight().isLowLightRetry());
    }

    /** A transaction continued from an earlier screen numbers its attempts after the earlier counted failures. */
    @Test
    void priorFailuresContinueTheAttemptCount() {
        LivenessTransaction tx = new LivenessTransaction(RESIDENT_OVERRIDE, 2, false);
        assertEquals(2, tx.priorCountedFailures());
        assertEquals(2, tx.attemptsUsed());
        assertEquals(3, tx.nextAttempt());
        assertEquals(0, tx.sessions());
        tx.record(failed(FailureReason.DEVICE));
        assertTrue(tx.canRetry(), "an uncounted failure keeps the last attempt");
        assertEquals(3, tx.nextAttempt());
        tx.record(failed(FailureReason.TIMEOUT));
        assertEquals(LivenessTransaction.Status.EXHAUSTED, tx.status());
        assertEquals(3, tx.attemptsUsed());
        assertEquals(2, tx.sessions());
        assertTrue(tx.isOverrideAllowed());
        assertEquals(0, new LivenessTransaction(RESIDENT_OVERRIDE).priorCountedFailures());
    }

    /** A suspected attack on an earlier screen keeps the override closed on this one. */
    @Test
    void priorSuspectedAttackRefusesTheOverride() {
        LivenessTransaction tx = new LivenessTransaction(RESIDENT_OVERRIDE, 2, true);
        assertTrue(tx.isSuspectedAttack());
        tx.record(failed(FailureReason.TIMEOUT));
        assertEquals(LivenessTransaction.Status.EXHAUSTED, tx.status());
        assertFalse(tx.isOverrideAllowed());
    }

    /** Prior failures must leave at least one attempt; an exhausted subject gets no new transaction. */
    @Test
    void priorFailuresOutsideTheLimitAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LivenessTransaction(RESIDENT_OVERRIDE, 3, false));
        assertThrows(IllegalArgumentException.class, () -> new LivenessTransaction(RESIDENT_OVERRIDE, -1, false));
    }

    // ------------------------------------------------------------------ low-light count of earlier screens

    /** Grey level of the synthetic dim face: above the TOO_DARK floor (50), below quality.low_light_brightness. */
    private static final double DIM = 65;

    /** Runs one passive session of a dim face with a failing PAD score, as the next session of {@code tx}. */
    private static SessionHarness dimSession(LivenessTransaction tx) {
        SessionHarness h = new SessionHarness(TestPolicies.passiveFirst(Workflow.REGISTRATION));
        h.lowLightRetries = tx.lowLightRetries();
        h.attempt = tx.nextAttempt();
        h.start();
        h.pad.next = 0.1;
        h.runPassive(TestFaces.neutral().brightness(DIM));
        return h;
    }

    private static LivenessTransaction continued(int countedFailures, boolean attack, int lowLight) {
        return new LivenessTransaction(TestPolicies.passiveFirst(Workflow.REGISTRATION), countedFailures, attack,
                lowLight);
    }

    private static void assertCountedPad(SessionHarness h) {
        LivenessResult r = h.result();
        assertEquals(FailureReason.PAD, r.failureReason().get());
        assertEquals(EnumSet.of(ReasonCode.PAD_SCORE), r.reasonCodes());
        assertFalse(r.isLowLightRetry());
        assertEquals(MessageKeys.FAIL_PAD, h.listener.lastUi().messageKey(), "the generic attack message");
        assertNotNull(h.listener.lastAudit(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED));
    }

    /** Two low-light results of an earlier window and one in this one use the allowance of 3: the next counts. */
    @Test
    void priorLowLightCountAddsToThisWindow() {
        LivenessTransaction tx = continued(0, false, 2);
        assertEquals(2, tx.priorLowLightRetries());
        assertEquals(2, tx.lowLightRetries());
        SessionHarness third = dimSession(tx);
        assertTrue(third.result().isLowLightRetry(), "the third dim result is still uncounted");
        tx.record(third.result());
        assertEquals(3, tx.lowLightRetries());
        assertEquals(0, tx.attemptsUsed());
        assertFalse(tx.isSuspectedAttack());

        SessionHarness fourth = dimSession(tx);
        assertEquals(1, fourth.result().attempt());
        assertCountedPad(fourth);
        tx.record(fourth.result());
        assertEquals(1, tx.attemptsUsed());
        assertTrue(tx.isSuspectedAttack());
    }

    /** An earlier window that used the whole allowance leaves none: the first dim failure here counts. */
    @Test
    void priorLowLightCountAtTheLimitCountsTheFirstDimFailure() {
        LivenessTransaction tx = continued(1, false, 3);
        SessionHarness first = dimSession(tx);
        assertEquals(2, first.result().attempt());
        assertCountedPad(first);
        tx.record(first.result());
        assertEquals(2, tx.attemptsUsed());
        assertEquals(0, tx.lowLightRetries(), "the counted failure starts the allowance again");
    }

    /** A counted failure resets the carried count; the prior value stays readable. */
    @Test
    void countedFailureResetsTheCarriedLowLightCount() {
        LivenessTransaction tx = new LivenessTransaction(RESIDENT_OVERRIDE, 1, false, 2);
        tx.record(failed(FailureReason.CANCELLED));
        tx.record(failed(FailureReason.DEVICE));
        assertEquals(2, tx.lowLightRetries(), "uncounted failures keep the carried count");
        tx.record(lowLight());
        assertEquals(3, tx.lowLightRetries());
        tx.record(failed(FailureReason.TIMEOUT));
        assertEquals(0, tx.lowLightRetries());
        assertEquals(2, tx.attemptsUsed());
        assertEquals(2, tx.priorLowLightRetries());
        assertFalse(tx.isSuspectedAttack(), "a carried low-light count is not an attack mark");
    }

    /** The three-argument constructor carries no low-light count. */
    @Test
    void earlierConstructorsCarryNoLowLightCount() {
        assertEquals(0, new LivenessTransaction(RESIDENT_OVERRIDE).lowLightRetries());
        LivenessTransaction tx = new LivenessTransaction(RESIDENT_OVERRIDE, 2, true);
        assertEquals(0, tx.lowLightRetries());
        assertEquals(0, tx.priorLowLightRetries());
    }

    /** The carried count lies between 0 and the largest passive.low_light_max_uncounted the signed file may hold. */
    @Test
    void priorLowLightCountOutsideItsRangeIsRefused() {
        int max = LivenessTransaction.maxPriorLowLightRetries();
        assertEquals((int) EngineParameter.LOW_LIGHT_MAX_UNCOUNTED.max(), max);
        assertEquals(max, new LivenessTransaction(RESIDENT_OVERRIDE, 0, false, max).lowLightRetries());
        assertThrows(IllegalArgumentException.class, () -> new LivenessTransaction(RESIDENT_OVERRIDE, 0, false, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new LivenessTransaction(RESIDENT_OVERRIDE, 0, false, max + 1));
        assertThrows(IllegalArgumentException.class, () -> new LivenessTransaction(RESIDENT_OVERRIDE, 3, false, 0),
                "the attempt limit still applies");
    }
}
