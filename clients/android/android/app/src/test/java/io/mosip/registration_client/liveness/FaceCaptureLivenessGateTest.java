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
