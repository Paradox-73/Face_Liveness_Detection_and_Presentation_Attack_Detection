/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;
import io.mosip.registration.livenessmanager.auth.FaceAuthOutcome;
import io.mosip.registration.livenessmanager.auth.FaceAuthStatus;
import io.mosip.registration.livenessmanager.auth.FaceAuthenticator;
import io.mosip.registration.livenessmanager.auth.OperatorDirectory;
import io.mosip.registration.livenessmanager.match.FaceMatch;

@RunWith(RobolectricTestRunner.class)
public class FaceAuthFlowTest {

    private final FakeLivenessService liveness = new FakeLivenessService();
    private final List<FaceAuthOutcome> outcomes = new ArrayList<>();
    private final List<String> supervisors = new ArrayList<>();
    private final List<String> locked = new ArrayList<>();
    /** ARC locks the account; false when its invalid-login limit is unset. */
    private boolean lockTakes = true;
    private FaceAuthenticator authenticator;
    private FaceMatch match = FaceMatch.compared(75, 40);
    private LivenessCaptureLauncher launcher;
    private FaceAuthFlow flow;

    @Before
    public void setUp() {
        liveness.enforced.addAll(Arrays.asList(Workflow.LOGIN_AUTH, Workflow.PACKET_AUTH, Workflow.EOD_AUTH));
        OperatorDirectory directory = new OperatorDirectory() {
            @Override
            public List<String> roles(String userId) {
                return supervisors.contains(userId) ? Collections.singletonList(FaceAuthenticator.SUPERVISOR_ROLE)
                        : Collections.singletonList("REGISTRATION_OFFICER");
            }

            @Override
            public List<byte[]> enrolledFaces(String userId) {
                return Collections.singletonList(new byte[] {1});
            }

            @Override
            public boolean isLocked(String userId) {
                return locked.contains(userId);
            }

            @Override
            public void recordFailure(String userId) {
            }

            @Override
            public void lock(String userId) {
                if (lockTakes) {
                    locked.add(userId);
                }
            }
        };
        authenticator = new FaceAuthenticator(liveness, directory, (probe, gallery) -> match);
        launcher = new LivenessCaptureLauncher(liveness);
        launcher.attach(Robolectric.buildActivity(Activity.class).setup().get());
        flow = new FaceAuthFlow(authenticator, launcher);
    }

    @Test
    public void refusedPrecheckOpensNoCamera() {
        flow.authenticate("officer1", Workflow.EOD_AUTH, outcomes::add);

        assertEquals(FaceAuthStatus.NOT_SUPERVISOR, outcomes.get(0).status());
        assertTrue(liveness.requests.isEmpty());
        assertFalse(flow.isAvailable("officer1", Workflow.EOD_AUTH));
        supervisors.add("supervisor1");
        assertTrue(flow.isAvailable("supervisor1", Workflow.EOD_AUTH));
    }

    @Test
    public void busyCaptureScreenIsReported() {
        launcher.launch(Workflow.REGISTRATION, "rid", r -> { });
        flow.authenticate("officer1", Workflow.PACKET_AUTH, outcomes::add);
        assertEquals(FaceAuthStatus.BUSY, outcomes.get(0).status());
    }

    @Test
    public void successfulFaceSignInLeavesOneGrant() throws Exception {
        assertFalse(flow.consumeLoginGrant("officer1"));

        runCapture(Workflow.LOGIN_AUTH, pass(Workflow.LOGIN_AUTH));

        assertEquals(FaceAuthStatus.SUCCESS, outcomes.get(0).status());
        assertTrue(flow.consumeLoginGrant("OFFICER1 "));
        assertFalse("the grant is used once", flow.consumeLoginGrant("officer1"));
    }

    @Test
    public void mismatchLeavesNoGrant() throws Exception {
        match = FaceMatch.compared(10, 40);
        runCapture(Workflow.LOGIN_AUTH, pass(Workflow.LOGIN_AUTH));

        assertEquals(FaceAuthStatus.FACE_MISMATCH, outcomes.get(0).status());
        assertFalse(flow.consumeLoginGrant("officer1"));
    }

    @Test
    public void packetAuthenticationLeavesNoSignInGrant() throws Exception {
        runCapture(Workflow.PACKET_AUTH, pass(Workflow.PACKET_AUTH));

        assertEquals(FaceAuthStatus.SUCCESS, outcomes.get(0).status());
        assertFalse(flow.consumeLoginGrant("officer1"));
    }

    @Test
    public void priorAttemptsSurvivePadPadCancelSoReopenedFaceSignInIsBlockedAfterOnePad() throws Exception {
        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        assertEquals(0, liveness.requests.get(0).priorAttempts());
        // PAD, PAD, then Cancel on the same screen: its last result does not count.
        deliver(cancelled(Workflow.LOGIN_AUTH, 2));
        assertEquals(FaceAuthStatus.CANCELLED, outcomes.get(0).status());
        assertEquals(2, flow.usedAttempts("officer1", Workflow.LOGIN_AUTH));

        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        assertEquals("the reopened screen starts at attempt 3", 2, liveness.requests.get(1).priorAttempts());
        deliver(failure(Workflow.LOGIN_AUTH, FailureReason.PAD, 3, false));
        assertEquals(FaceAuthStatus.MAX_ATTEMPTS, outcomes.get(1).status());

        flow.authenticate("OFFICER1", Workflow.LOGIN_AUTH, outcomes::add);
        assertEquals(FaceAuthStatus.MAX_ATTEMPTS, outcomes.get(2).status());
        assertEquals(MessageKeys.RETRY_MAX_AUTH, outcomes.get(2).messageKey());
        assertEquals("no camera after the block", 2, liveness.requests.size());
        assertFalse(flow.consumeLoginGrant("officer1"));
    }

    @Test
    public void priorAttemptsOfSupervisorAuthenticationAreKeptPerWorkflowAndUserUntilAPass() throws Exception {
        supervisors.add("supervisor1");
        flow.authenticate("supervisor1", Workflow.EOD_AUTH, outcomes::add);
        deliver(failure(Workflow.EOD_AUTH, FailureReason.PAD, 1, true));
        flow.authenticate("supervisor1", Workflow.EOD_AUTH, outcomes::add);
        deliver(failure(Workflow.EOD_AUTH, FailureReason.QUALITY, 2, true));

        assertEquals(2, flow.usedAttempts("supervisor1", Workflow.EOD_AUTH));
        assertEquals(0, flow.usedAttempts("supervisor1", Workflow.PACKET_AUTH));
        assertEquals(0, flow.usedAttempts("officer1", Workflow.EOD_AUTH));

        flow.authenticate("supervisor1", Workflow.EOD_AUTH, outcomes::add);
        assertEquals(2, liveness.requests.get(2).priorAttempts());
        deliver(pass(Workflow.EOD_AUTH));
        assertEquals(FaceAuthStatus.SUCCESS, outcomes.get(2).status());
        assertEquals("a pass clears the count", 0, flow.usedAttempts("supervisor1", Workflow.EOD_AUTH));
    }

    @Test
    public void priorAttemptsGiveWayToTheAccountLockWithBlockAndLock() throws Exception {
        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        deliver(failure(Workflow.LOGIN_AUTH, FailureReason.PAD, 3, false).toBuilder().lockRequired(true).build());

        assertEquals(FaceAuthStatus.MAX_ATTEMPTS, outcomes.get(0).status());
        // The account lock refuses the user while it lasts; afterwards the count starts again.
        assertEquals(0, flow.usedAttempts("officer1", Workflow.LOGIN_AUTH));
        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        assertEquals(FaceAuthStatus.USER_LOCKED, outcomes.get(1).status());
        locked.clear();
        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        assertEquals(2, liveness.requests.size());
        assertEquals(0, liveness.requests.get(1).priorAttempts());
    }

    @Test
    public void lockThatDidNotTakeKeepsTheBlock() throws Exception {
        lockTakes = false;
        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        deliver(failure(Workflow.LOGIN_AUTH, FailureReason.PAD, 3, false).toBuilder().lockRequired(true).build());

        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        assertEquals(FaceAuthStatus.MAX_ATTEMPTS, outcomes.get(1).status());
        assertEquals("no camera: the attempts are used up", 1, liveness.requests.size());
    }

    @Test
    public void blockSurvivesAnAppRestart() throws Exception {
        flow.persistTo(new SavedAttempts(RuntimeEnvironment.getApplication(), "test_auth_attempts"));
        flow.authenticate("officer1", Workflow.PACKET_AUTH, outcomes::add);
        deliver(failure(Workflow.PACKET_AUTH, FailureReason.PAD, 3, false));

        FaceAuthFlow restarted = new FaceAuthFlow(authenticator, launcher);
        restarted.persistTo(new SavedAttempts(RuntimeEnvironment.getApplication(), "test_auth_attempts"));
        restarted.authenticate("officer1", Workflow.PACKET_AUTH, outcomes::add);
        assertEquals(FaceAuthStatus.MAX_ATTEMPTS, outcomes.get(1).status());
        assertEquals(3, restarted.usedAttempts("officer1", Workflow.PACKET_AUTH));
        assertTrue(restarted.isSuspectedAttack("officer1", Workflow.PACKET_AUTH));
        assertEquals(1, liveness.requests.size());
    }

    @Test
    public void reopenedScreenReceivesTheEarlierAttackAndDimLightCount() throws Exception {
        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        assertFalse(liveness.requests.get(0).isPriorSuspectedAttack());
        assertEquals(0, liveness.requests.get(0).priorLowLight());
        // Dim, dim, then the screen is closed.
        deliver(dim(Workflow.LOGIN_AUTH, 0, 2));
        assertEquals(2, flow.lowLightRetries("officer1", Workflow.LOGIN_AUTH));
        assertEquals(0, flow.usedAttempts("officer1", Workflow.LOGIN_AUTH));

        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        assertEquals("the reopened screen continues the dim-light count", 2,
                liveness.requests.get(1).priorLowLight());
        assertEquals(0, liveness.requests.get(1).priorAttempts());
        assertEquals("kept per workflow", 0, flow.lowLightRetries("officer1", Workflow.PACKET_AUTH));
        // The third dim try was uncounted, the fourth counted as a PAD failure.
        deliver(failure(Workflow.LOGIN_AUTH, FailureReason.PAD, 1, true));
        assertEquals("a counted failure resets the dim-light count", 0,
                flow.lowLightRetries("officer1", Workflow.LOGIN_AUTH));
        assertTrue(flow.isSuspectedAttack("officer1", Workflow.LOGIN_AUTH));

        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        assertEquals(1, liveness.requests.get(2).priorAttempts());
        assertTrue("the screen starts with the earlier attack", liveness.requests.get(2).isPriorSuspectedAttack());
        assertEquals(0, liveness.requests.get(2).priorLowLight());
        deliver(cancelled(Workflow.LOGIN_AUTH, 1));
        assertTrue("a cancellation keeps the attack mark", flow.isSuspectedAttack("officer1", Workflow.LOGIN_AUTH));

        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        deliver(pass(Workflow.LOGIN_AUTH));
        assertFalse("a pass clears the attack mark", flow.isSuspectedAttack("officer1", Workflow.LOGIN_AUTH));
        flow.authenticate("officer1", Workflow.LOGIN_AUTH, outcomes::add);
        assertFalse(liveness.requests.get(4).isPriorSuspectedAttack());
        assertEquals(0, liveness.requests.get(4).priorAttempts());
    }

    @Test
    public void screenThatCountedAFailureReplacesTheStoredDimLightCount() throws Exception {
        flow.authenticate("officer1", Workflow.PACKET_AUTH, outcomes::add);
        deliver(dim(Workflow.PACKET_AUTH, 0, 3));
        flow.authenticate("officer1", Workflow.PACKET_AUTH, outcomes::add);
        // That screen: the fourth dim try counted, then one more dim try.
        deliver(dim(Workflow.PACKET_AUTH, 1, 1));

        assertEquals(1, flow.lowLightRetries("officer1", Workflow.PACKET_AUTH));
        assertEquals(1, flow.usedAttempts("officer1", Workflow.PACKET_AUTH));
        assertFalse("a dim-light result is no attack", flow.isSuspectedAttack("officer1", Workflow.PACKET_AUTH));
    }

    /** Returns the given result from the open capture screen and waits for the flow's outcome. */
    private void deliver(LivenessCaptureResult result) throws InterruptedException {
        int before = outcomes.size();
        liveness.nextResult = result;
        launcher.onActivityResult(LivenessService.REQUEST_CODE, Activity.RESULT_OK, null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (outcomes.size() == before && System.nanoTime() < deadline) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertEquals(before + 1, outcomes.size());
    }

    private static LivenessCaptureResult cancelled(Workflow workflow, int used) {
        return LivenessCaptureResult.builder(workflow, Decision.CANCELLED).failureReason(FailureReason.CANCELLED)
                .attempts(used, 3).retryAllowed(true).build();
    }

    /** "Improve lighting": a dark face's PAD rejection the screen did not count. */
    private static LivenessCaptureResult dim(Workflow workflow, int used, int lowLight) {
        return failure(workflow, FailureReason.QUALITY, used, true).toBuilder()
                .reasonCodes(Collections.singletonList("LOW_LIGHT")).lowLightRetries(lowLight).build();
    }

    private static LivenessCaptureResult failure(Workflow workflow, FailureReason reason, int used, boolean retry) {
        return LivenessCaptureResult.builder(workflow, reason.decision()).failureReason(reason)
                .messageKey(MessageKeys.FAIL_LIVENESS).attempts(used, 3).retryAllowed(retry).build();
    }

    private void runCapture(Workflow workflow, LivenessCaptureResult result) throws InterruptedException {
        flow.authenticate("officer1", workflow, outcomes::add);
        assertEquals(workflow, liveness.requests.get(0).workflow());
        liveness.nextResult = result;
        launcher.onActivityResult(LivenessService.REQUEST_CODE, Activity.RESULT_OK, null);
        // The match runs on a worker thread and posts the outcome to the main thread.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (outcomes.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static LivenessCaptureResult pass(Workflow workflow) {
        return LivenessCaptureResult.builder(workflow, Decision.PASS).attempts(1, 3)
                .capture("tx", new byte[] {2}, "{}", true).build();
    }
}
