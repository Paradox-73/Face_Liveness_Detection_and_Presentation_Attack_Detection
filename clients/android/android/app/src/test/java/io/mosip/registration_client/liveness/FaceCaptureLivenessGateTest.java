/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.auth.FaceAuthOutcome;
import io.mosip.registration.livenessmanager.auth.FaceAuthStatus;
import io.mosip.registration.livenessmanager.ui.MessageResolver;

public class FaceCaptureLivenessGateTest {

    private static final String FIELD = "individualBiometrics";
    private static final String RID = "10001100010000120260930";

    private final FakeLivenessService liveness = new FakeLivenessService();
    private FaceCaptureLivenessGate gate;

    @Before
    public void setUp() {
        liveness.enforced.add(Workflow.REGISTRATION);
        gate = new FaceCaptureLivenessGate(liveness);
    }

    @Test
    public void fieldsMapToTheirWorkflows() {
        assertEquals(Workflow.ONBOARD_AUTH, FaceCaptureLivenessGate.workflowFor("operatorBiometrics"));
        assertEquals(Workflow.REGISTRATION, FaceCaptureLivenessGate.workflowFor(FIELD));
        assertTrue(gate.isRequired(FIELD));
        assertFalse(gate.isRequired("operatorBiometrics"));
    }

    @Test
    public void exceptionPhotoIsKeptApartFromTheFaceCapture() {
        String photo = FaceCaptureLivenessGate.exceptionPhotoField(FIELD);
        assertEquals(Workflow.EXCEPTION_PHOTO, FaceCaptureLivenessGate.workflowFor(photo));
        assertFalse(gate.isRequired(photo));
        assertTrue(gate.takesExceptionPhotos());
        gate.record(RID, FIELD, failure(FailureReason.PAD, 3, false));
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.lastOutcome(FIELD).status());
        assertNull(gate.admit(RID, photo));
        LivenessCaptureResult photoOnly = LivenessCaptureResult.builder(Workflow.EXCEPTION_PHOTO, Decision.PASS)
                .reasonCodes(java.util.Collections.singletonList(
                        LivenessCaptureResult.REASON_LIVENESS_NOT_REQUIRED)).build();
        assertFalse(photoOnly.isLivenessChecked());
        assertEquals(CaptureOutcome.Status.PASSED, gate.record(RID, photo, photoOnly).status());
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.lastOutcome(FIELD).status());
    }

    @Test
    public void passIsReportedAndStartsNothingBlocked() {
        assertNull(gate.admit(RID, FIELD));
        CaptureOutcome outcome = gate.record(RID, FIELD, pass());
        assertEquals(CaptureOutcome.Status.PASSED, outcome.status());
        assertEquals(MessageKeys.FEEDBACK_SUCCESS, outcome.messageKey());
        assertEquals(outcome, gate.lastOutcome(FIELD));
    }

    @Test
    public void failureWithRetryLeftIsFailedWithTheEngineMessage() {
        CaptureOutcome outcome = gate.record(RID, FIELD, failure(FailureReason.PAD, 1, true));
        assertEquals(CaptureOutcome.Status.FAILED, outcome.status());
        assertEquals(MessageKeys.FAIL_PAD, outcome.messageKey());
        assertEquals(1, outcome.attemptsUsed());
        assertEquals(3, outcome.maxAttempts());
        assertNull(gate.admit(RID, FIELD));
    }

    @Test
    public void closingTheScreenDoesNotResetTheAttemptLimit() {
        gate.record(RID, FIELD, failure(FailureReason.BELOW_THRESHOLD, 2, true));
        assertEquals("the next screen continues the count", 2, gate.usedAttempts(RID, FIELD));
        // The next screen was started with 2 prior attempts, so it reports 3 used.
        CaptureOutcome third = gate.record(RID, FIELD, failure(FailureReason.ACTIVE, 3, false));

        assertEquals(CaptureOutcome.Status.BLOCKED, third.status());
        assertEquals(MessageKeys.RETRY_MAX_RESIDENT, third.messageKey());
        assertEquals("3", third.args().get(0));
        CaptureOutcome refused = gate.admit(RID, FIELD);
        assertEquals(CaptureOutcome.Status.BLOCKED, refused.status());
        assertNull("a new registration starts a new count", gate.admit("another-rid", FIELD));
    }

    @Test
    public void screenReportingNoRetryLeftBlocks() {
        CaptureOutcome outcome = gate.record(RID, FIELD, failure(FailureReason.PAD, 3, false));
        assertEquals(CaptureOutcome.Status.BLOCKED, outcome.status());
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.admit(RID, FIELD).status());
    }

    @Test
    public void onboardingUsesTheAuthenticationMessage() {
        CaptureOutcome outcome = gate.record("officer1", "operatorBiometrics",
                failure(FailureReason.PAD, 3, false));
        assertEquals(MessageKeys.RETRY_MAX_AUTH, outcome.messageKey());
    }

    @Test
    public void cancelAndDeviceErrorsDoNotCount() {
        LivenessCaptureResult cancelled = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.CANCELLED)
                .failureReason(FailureReason.CANCELLED).build();
        assertEquals(CaptureOutcome.Status.CANCELLED, gate.record(RID, FIELD, cancelled).status());
        for (int i = 0; i < 5; i++) {
            gate.record(RID, FIELD, failure(FailureReason.DEVICE, 1, true));
        }
        assertNull(gate.admit(RID, FIELD));
    }

    @Test
    public void passResetsTheCountAndRejectionIsReported() {
        gate.record(RID, FIELD, failure(FailureReason.PAD, 2, true));
        gate.record(RID, FIELD, pass());
        gate.record(RID, FIELD, failure(FailureReason.PAD, 2, true));
        assertNull(gate.admit(RID, FIELD));

        CaptureOutcome rejected = gate.recordRejected(FIELD);
        assertEquals(CaptureOutcome.Status.FAILED, rejected.status());
        gate.clearOutcome(FIELD);
        assertEquals(CaptureOutcome.Status.NOT_REQUIRED, gate.lastOutcome(FIELD).status());
    }

    @Test
    public void overrideIsKeptOnlyWhenThePolicyAndTheFailureAllowIt() {
        gate.record(RID, FIELD, failure(FailureReason.ACTIVE, 3, false).toBuilder().overrideAllowed(true).build());
        assertNull("shipped policy BLOCK: no override", gate.overrideEvidence(RID, FIELD));

        allowOverride();
        gate.record("rid-2", FIELD, failure(FailureReason.PAD, 3, false));
        assertNull("the engine refused the override after a suspected attack", gate.overrideEvidence("rid-2", FIELD));

        gate.record(RID, FIELD, overridable());
        assertEquals("S-1", gate.overrideEvidence(RID, FIELD).get(PacketEvidenceKeys.SESSION));
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.admit(RID, FIELD).status());
    }

    @Test
    public void overridePhotoUnblocksTheFieldWithTheOverrideMessage() {
        allowOverride();
        gate.record(RID, FIELD, overridable());
        LivenessCaptureResult photo = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.PASS)
                .reasonCodes(java.util.Collections.singletonList(LivenessCaptureResult.REASON_SUPERVISOR_OVERRIDE))
                .evidence(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_OVERRIDE).build();

        CaptureOutcome outcome = gate.recordOverride(RID, FIELD, photo);

        assertEquals(CaptureOutcome.Status.PASSED, outcome.status());
        assertEquals(MessageResolver.OVERRIDE_USED, outcome.messageKey());
        assertNull(gate.overrideEvidence(RID, FIELD));
        assertNull(gate.admit(RID, FIELD));
    }

    @Test
    public void refusedSupervisorKeepsTheFieldBlockedAndTheOfferOpen() {
        allowOverride();
        gate.record(RID, FIELD, overridable());

        CaptureOutcome outcome = gate.recordOverrideRefused(RID, FIELD, FaceAuthOutcome.early(
                Workflow.EXCEPTION_AUTH, FaceAuthStatus.NOT_SUPERVISOR, MessageKeys.ERROR_NOT_SUPERVISOR));

        assertEquals(CaptureOutcome.Status.BLOCKED, outcome.status());
        assertEquals(MessageKeys.ERROR_NOT_SUPERVISOR, outcome.messageKey());
        assertTrue(gate.overrideEvidence(RID, FIELD) != null);
        LivenessCaptureResult cancelled = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.CANCELLED)
                .failureReason(FailureReason.CANCELLED).build();
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.recordOverride(RID, FIELD, cancelled).status());
    }

    @Test
    public void padPadCancelKeepsTheCountedAttemptsSoTheNextScreenStartsAtAttempt3() {
        gate.record(RID, FIELD, failure(FailureReason.PAD, 1, true));
        // Second PAD failure on the same screen, then Cancel: the screen's last result does not count.
        CaptureOutcome cancelled = gate.record(RID, FIELD, cancelled(2));

        assertEquals(CaptureOutcome.Status.CANCELLED, cancelled.status());
        assertEquals(2, cancelled.attemptsUsed());
        assertEquals("the next screen starts at attempt 3", 2, gate.usedAttempts(RID, FIELD));
        assertNull(gate.admit(RID, FIELD));
        CaptureOutcome third = gate.record(RID, FIELD, failure(FailureReason.PAD, 3, false));
        assertEquals(CaptureOutcome.Status.BLOCKED, third.status());
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.admit(RID, FIELD).status());
    }

    @Test
    public void padPadCancelOnOneScreenIsNotLostWhenTheGateSawNoFailureBefore() {
        gate.record(RID, FIELD, cancelled(2));

        assertEquals(2, gate.usedAttempts(RID, FIELD));
        gate.record(RID, FIELD, cancelled(0));
        assertEquals("a later screen cancelled before any attempt keeps the count", 2,
                gate.usedAttempts(RID, FIELD));
    }

    @Test
    public void padPadQualityKeepsTheTwoCountedAttempts() {
        LivenessCaptureResult quality = failure(FailureReason.QUALITY, 2, true);

        CaptureOutcome outcome = gate.record(RID, FIELD, quality);

        assertEquals(CaptureOutcome.Status.FAILED, outcome.status());
        assertEquals(2, outcome.attemptsUsed());
        assertEquals(2, gate.usedAttempts(RID, FIELD));
        gate.record(RID, FIELD, failure(FailureReason.DEVICE, 2, true));
        assertEquals("device errors keep the count as it is", 2, gate.usedAttempts(RID, FIELD));
    }

    @Test
    public void earlierAttackForbidsTheOverrideAfterTimeoutsOnLaterScreens() {
        allowOverride();
        gate.record(RID, FIELD, failure(FailureReason.PAD, 1, true));
        assertTrue(gate.isSuspectedAttack(RID, FIELD));
        gate.record(RID, FIELD, failure(FailureReason.TIMEOUT, 2, true));
        // The last screen's engine saw only timeouts, so it allows the override.
        CaptureOutcome last = gate.record(RID, FIELD, overridable(FailureReason.TIMEOUT));

        assertEquals(CaptureOutcome.Status.BLOCKED, last.status());
        assertNull("a PAD failure on an earlier screen forbids the override", gate.overrideEvidence(RID, FIELD));
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.recordOverride(RID, FIELD, overridePhoto()).status());
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.admit(RID, FIELD).status());

        gate.record("rid-2", FIELD, failure(FailureReason.TIMEOUT, 1, true));
        gate.record("rid-2", FIELD, overridable(FailureReason.TIMEOUT));
        assertTrue("timeouts alone still allow it", gate.overrideEvidence("rid-2", FIELD) != null);
    }

    @Test
    public void earlierAttackOfEveryKindIsRememberedUntilAPass() {
        for (FailureReason attack : new FailureReason[] {FailureReason.PAD, FailureReason.INTEGRITY,
                FailureReason.BINDING}) {
            String rid = "rid-" + attack;
            gate.record(rid, FIELD, failure(attack, 1, true));
            assertTrue(attack.name(), gate.isSuspectedAttack(rid, FIELD));
            assertFalse("kept per registration", gate.isSuspectedAttack(RID, FIELD));
            gate.record(rid, FIELD, pass());
            assertFalse(gate.isSuspectedAttack(rid, FIELD));
        }
        gate.record(RID, FIELD, failure(FailureReason.ACTIVE, 1, true));
        gate.record(RID, FIELD, failure(FailureReason.QUALITY, 1, true));
        assertFalse(gate.isSuspectedAttack(RID, FIELD));
    }

    @Test
    public void dimLightCountIsKeptPerKeyUntilACountedFailure() {
        // Dim, dim on the first screen; the screen is closed.
        gate.record(RID, FIELD, dim(0, 2));
        assertEquals(2, gate.lowLightRetries(RID, FIELD));
        assertEquals("kept per registration", 0, gate.lowLightRetries("another-rid", FIELD));
        assertEquals("kept per field", 0, gate.lowLightRetries(RID, "operatorBiometrics"));
        assertEquals("not counted", 0, gate.usedAttempts(RID, FIELD));

        // A screen closed before its first attempt ended reports no count: the stored one stays.
        gate.record(RID, FIELD, cancelled(0));
        assertEquals(2, gate.lowLightRetries(RID, FIELD));

        // The reopened screen started at 2 and reports 3.
        gate.record(RID, FIELD, dim(0, 3));
        assertEquals(3, gate.lowLightRetries(RID, FIELD));

        CaptureOutcome counted = gate.record(RID, FIELD, failure(FailureReason.PAD, 1, true));
        assertEquals(CaptureOutcome.Status.FAILED, counted.status());
        assertEquals("a counted failure resets the dim-light count", 0, gate.lowLightRetries(RID, FIELD));
        assertEquals(1, gate.usedAttempts(RID, FIELD));
    }

    @Test
    public void screenThatCountedAFailureReplacesTheStoredDimLightCount() {
        gate.record(RID, FIELD, dim(0, 3));
        // The next screen: the fourth dim try counted (attempt 1), then one more dim try.
        gate.record(RID, FIELD, dim(1, 1));
        assertEquals(1, gate.lowLightRetries(RID, FIELD));
        assertEquals(1, gate.usedAttempts(RID, FIELD));
    }

    @Test
    public void passAndBlockResetTheDimLightCount() {
        gate.record(RID, FIELD, dim(0, 2));
        gate.record(RID, FIELD, pass());
        assertEquals(0, gate.lowLightRetries(RID, FIELD));

        gate.record(RID, FIELD, dim(2, 2));
        gate.record(RID, FIELD, failure(FailureReason.PAD, 3, false));
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.admit(RID, FIELD).status());
        assertEquals(0, gate.lowLightRetries(RID, FIELD));
    }

    @Test
    public void nextRequestCarriesTheEarlierScreensState() {
        LivenessCaptureRequest first = gate.nextRequest(RID, FIELD, false).build();
        assertEquals(Workflow.REGISTRATION, first.workflow());
        assertEquals(RID, first.subjectRef());
        assertEquals(0, first.priorAttempts());
        assertFalse(first.isPriorSuspectedAttack());
        assertEquals(0, first.priorLowLight());

        gate.record(RID, FIELD, failure(FailureReason.PAD, 1, true));
        gate.record(RID, FIELD, dim(1, 2));
        LivenessCaptureRequest next = gate.nextRequest(RID, FIELD, false).build();
        assertEquals(1, next.priorAttempts());
        assertTrue("the PAD failure is a suspected attack", next.isPriorSuspectedAttack());
        assertEquals(2, next.priorLowLight());

        String photo = FaceCaptureLivenessGate.exceptionPhotoField(FIELD);
        LivenessCaptureRequest photoRequest = gate.nextRequest(RID, photo, true).build();
        assertEquals(Workflow.EXCEPTION_PHOTO, photoRequest.workflow());
        assertTrue(photoRequest.isPhotoWhenOff());
        assertEquals("the exception photo has its own count", 0, photoRequest.priorAttempts());
        assertFalse(photoRequest.isPriorSuspectedAttack());
    }

    @Test
    public void overridePhotoWithoutAPendingOverrideIsRefused() {
        allowOverride();
        gate.record(RID, FIELD, failure(FailureReason.ACTIVE, 3, false));

        assertEquals(CaptureOutcome.Status.BLOCKED, gate.recordOverride(RID, FIELD, overridePhoto()).status());
        assertEquals(CaptureOutcome.Status.BLOCKED, gate.admit(RID, FIELD).status());
    }

    private static LivenessCaptureResult cancelled(int used) {
        return LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.CANCELLED)
                .failureReason(FailureReason.CANCELLED).attempts(used, 3).retryAllowed(true).build();
    }

    /** "Improve lighting": a dark face's PAD rejection the screen did not count. */
    private static LivenessCaptureResult dim(int used, int lowLight) {
        return LivenessCaptureResult.builder(Workflow.REGISTRATION, FailureReason.QUALITY.decision())
                .failureReason(FailureReason.QUALITY).reasonCodes(java.util.Collections.singletonList("LOW_LIGHT"))
                .messageKey(MessageKeys.FAIL_LIVENESS).attempts(used, 3).retryAllowed(true)
                .lowLightRetries(lowLight).build();
    }

    private static LivenessCaptureResult overridable(FailureReason reason) {
        return failure(reason, 3, false).toBuilder().overrideAllowed(true)
                .evidence(PacketEvidenceKeys.SESSION, "S-2")
                .evidence(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_FAIL).build();
    }

    private static LivenessCaptureResult overridePhoto() {
        return LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.PASS)
                .reasonCodes(java.util.Collections.singletonList(LivenessCaptureResult.REASON_SUPERVISOR_OVERRIDE))
                .evidence(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_OVERRIDE).build();
    }

    private void allowOverride() {
        liveness.config.put(LivenessConfigKeys.workflowKey(Workflow.REGISTRATION,
                LivenessConfigKeys.WORKFLOW_ON_MAX_FAILURE_SUFFIX), "SUPERVISOR_OVERRIDE");
    }

    private static LivenessCaptureResult overridable() {
        return failure(FailureReason.ACTIVE, 3, false).toBuilder().overrideAllowed(true)
                .evidence(PacketEvidenceKeys.SESSION, "S-1")
                .evidence(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_FAIL).build();
    }

    private static LivenessCaptureResult pass() {
        return LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.PASS).attempts(1, 3).build();
    }

    private static LivenessCaptureResult failure(FailureReason reason, int used, boolean retry) {
        return LivenessCaptureResult.builder(Workflow.REGISTRATION, reason.decision()).failureReason(reason)
                .messageKey(reason == FailureReason.PAD ? MessageKeys.FAIL_PAD : MessageKeys.FAIL_LIVENESS)
                .attempts(used, 3).retryAllowed(retry).build();
    }
}
