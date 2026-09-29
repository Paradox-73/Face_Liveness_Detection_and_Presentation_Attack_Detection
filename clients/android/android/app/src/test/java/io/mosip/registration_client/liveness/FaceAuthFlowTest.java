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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
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
                return false;
            }

            @Override
            public void recordFailure(String userId) {
            }

            @Override
            public void lock(String userId) {
            }
        };
        FaceAuthenticator authenticator = new FaceAuthenticator(liveness, directory, (probe, gallery) -> match);
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
