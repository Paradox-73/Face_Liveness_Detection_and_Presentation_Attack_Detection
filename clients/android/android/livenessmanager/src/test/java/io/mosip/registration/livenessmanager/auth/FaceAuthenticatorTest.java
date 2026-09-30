package io.mosip.registration.livenessmanager.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;
import io.mosip.registration.livenessmanager.match.FaceMatch;

public class FaceAuthenticatorTest {

    private static final byte[] PROBE = {1, 2, 3};
    private static final byte[] ENROLLED = {4, 5, 6};

    private final Set<Workflow> enforced = EnumSet.allOf(Workflow.class);
    private final FakeDirectory directory = new FakeDirectory();
    private FaceMatch nextMatch = FaceMatch.compared(80, 40);
    private final List<byte[]> probesSeen = new ArrayList<>();
    private FaceAuthenticator authenticator;

    @Before
    public void setUp() {
        LivenessService liveness = (LivenessService) java.lang.reflect.Proxy.newProxyInstance(
                LivenessService.class.getClassLoader(), new Class<?>[] {LivenessService.class},
                (proxy, method, args) -> "isEnforced".equals(method.getName())
                        ? enforced.contains((Workflow) args[0]) : null);
        authenticator = new FaceAuthenticator(liveness, directory, (probe, gallery) -> {
            probesSeen.add(probe);
            return gallery.isEmpty() ? FaceMatch.notCompared(FaceMatch.Status.NO_GALLERY, 40) : nextMatch;
        });
        directory.faces.put("officer1", Collections.singletonList(ENROLLED));
        directory.faces.put("supervisor1", Collections.singletonList(ENROLLED));
        directory.roles.put("officer1", Collections.singletonList("REGISTRATION_OFFICER"));
        directory.roles.put("supervisor1", Arrays.asList("REGISTRATION_OFFICER", "REGISTRATION_SUPERVISOR"));
    }

    @Test
    public void precheckLetsAnEnrolledOperatorStart() {
        assertNull(authenticator.precheck("officer1", Workflow.PACKET_AUTH));
        assertTrue(authenticator.isAvailable("officer1", Workflow.LOGIN_AUTH));
    }

    @Test
    public void precheckReportsLivenessSwitchedOffForTheWorkflow() {
        enforced.remove(Workflow.PACKET_AUTH);
        assertEquals(FaceAuthStatus.NOT_ENFORCED, authenticator.precheck("officer1", Workflow.PACKET_AUTH).status());
        assertEquals(FaceAuthStatus.NOT_ENFORCED,
                authenticator.precheck("officer1", Workflow.REGISTRATION).status());
    }

    @Test
    public void supervisorWorkflowNeedsTheSupervisorRole() {
        FaceAuthOutcome refused = authenticator.precheck("officer1", Workflow.EOD_AUTH);
        assertEquals(FaceAuthStatus.NOT_SUPERVISOR, refused.status());
        assertEquals(MessageKeys.ERROR_NOT_SUPERVISOR, refused.messageKey());
        assertNull(authenticator.precheck("supervisor1", Workflow.EOD_AUTH));
        assertEquals(FaceAuthStatus.NOT_SUPERVISOR,
                authenticator.precheck("officer1", Workflow.EXCEPTION_AUTH).status());
    }

    @Test
    public void userWithoutEnrolledFaceIsRefusedBeforeTheCamera() {
        assertEquals(FaceAuthStatus.FACE_NOT_ENROLLED, authenticator.precheck("officer2", Workflow.LOGIN_AUTH).status());
        assertEquals(FaceAuthStatus.FACE_NOT_ENROLLED, authenticator.precheck(" ", Workflow.LOGIN_AUTH).status());
        assertFalse(authenticator.isAvailable("officer2", Workflow.LOGIN_AUTH));
    }

    @Test
    public void lockedUserIsRefused() {
        directory.locked.add("officer1");
        assertEquals(FaceAuthStatus.USER_LOCKED, authenticator.precheck("officer1", Workflow.LOGIN_AUTH).status());
    }

    @Test
    public void livenessPassAndMatchingFaceAuthenticates() {
        FaceAuthOutcome outcome = authenticator.complete("officer1", Workflow.PACKET_AUTH, pass());
        assertTrue(outcome.isSuccess());
        assertEquals(1, outcome.attemptsUsed());
        assertEquals(3, outcome.maxAttempts());
        assertTrue(Arrays.equals(PROBE, probesSeen.get(0)));
        assertTrue("the signed capture is kept for the packet", outcome.capture().isPresent());
    }

    @Test
    public void mismatchAtSignInCountsAsFailedSignIn() {
        nextMatch = FaceMatch.compared(12, 40);
        FaceAuthOutcome outcome = authenticator.complete("officer1", Workflow.LOGIN_AUTH, pass());
        assertEquals(FaceAuthStatus.FACE_MISMATCH, outcome.status());
        assertFalse("no capture is kept after a mismatch", outcome.capture().isPresent());
        assertEquals(1, directory.failures);

        authenticator.complete("officer1", Workflow.PACKET_AUTH, pass());
        assertEquals("only sign-in failures count towards the lockout", 1, directory.failures);
    }

    @Test
    public void unusableProbeAndMissingModelsAreReported() {
        nextMatch = FaceMatch.notCompared(FaceMatch.Status.NO_PROBE_FACE, 40);
        FaceAuthOutcome noFace = authenticator.complete("officer1", Workflow.PACKET_AUTH, pass());
        assertEquals(FaceAuthStatus.LIVENESS_FAILED, noFace.status());
        assertEquals(MessageKeys.ERROR_POOR_QUALITY, noFace.messageKey());

        nextMatch = FaceMatch.notCompared(FaceMatch.Status.UNAVAILABLE, 40);
        FaceAuthOutcome engine = authenticator.complete("officer1", Workflow.PACKET_AUTH, pass());
        assertEquals(FaceAuthStatus.ENGINE_UNAVAILABLE, engine.status());
        assertEquals(MessageKeys.ERROR_ENGINE, engine.messageKey());
    }

    @Test
    public void lastFailedAttemptReportsMaximumAttempts() {
        LivenessCaptureResult last = failure(FailureReason.PAD, 3, false, false);
        FaceAuthOutcome outcome = authenticator.complete("officer1", Workflow.PACKET_AUTH, last);
        assertEquals(FaceAuthStatus.MAX_ATTEMPTS, outcome.status());
        assertEquals(MessageKeys.RETRY_MAX_AUTH, outcome.messageKey());
        assertEquals(0, directory.lockCalls);
        assertTrue("a liveness failure never reaches the matcher", probesSeen.isEmpty());
    }

    @Test
    public void blockAndLockLocksTheAccount() {
        LivenessCaptureResult last = failure(FailureReason.ACTIVE, 3, false, true);
        authenticator.complete("officer1", Workflow.LOGIN_AUTH, last);
        assertEquals(1, directory.lockCalls);
    }

    @Test
    public void closedScreenAfterAFailureKeepsTheEngineMessage() {
        LivenessCaptureResult closed = failure(FailureReason.BELOW_THRESHOLD, 1, true, false);
        FaceAuthOutcome outcome = authenticator.complete("officer1", Workflow.PACKET_AUTH, closed);
        assertEquals(FaceAuthStatus.LIVENESS_FAILED, outcome.status());
        assertEquals(MessageKeys.FAIL_LIVENESS, outcome.messageKey());
    }

    @Test
    public void cancelAndEngineErrorsAreMapped() {
        LivenessCaptureResult cancelled = LivenessCaptureResult.builder(Workflow.PACKET_AUTH, Decision.CANCELLED)
                .failureReason(FailureReason.CANCELLED).build();
        assertEquals(FaceAuthStatus.CANCELLED,
                authenticator.complete("officer1", Workflow.PACKET_AUTH, cancelled).status());

        LivenessCaptureResult engine = LivenessCaptureResult.builder(Workflow.PACKET_AUTH, Decision.ERROR)
                .failureReason(FailureReason.ENGINE).build();
        assertEquals(FaceAuthStatus.ENGINE_UNAVAILABLE,
                authenticator.complete("officer1", Workflow.PACKET_AUTH, engine).status());
    }

    private static LivenessCaptureResult pass() {
        return LivenessCaptureResult.builder(Workflow.PACKET_AUTH, Decision.PASS).attempts(1, 3)
                .capture("tx-1", PROBE, "{}", true).build();
    }

    private static LivenessCaptureResult failure(FailureReason reason, int used, boolean retry, boolean lock) {
        return LivenessCaptureResult.builder(Workflow.PACKET_AUTH, reason.decision()).failureReason(reason)
                .messageKey(reason == FailureReason.PAD ? MessageKeys.FAIL_PAD : MessageKeys.FAIL_LIVENESS)
                .attempts(used, 3).retryAllowed(retry).lockRequired(lock)
                .onMaxFailure(lock ? OnMaxFailure.BLOCK_AND_LOCK : OnMaxFailure.BLOCK).build();
    }

    private static final class FakeDirectory implements OperatorDirectory {
        final Map<String, List<String>> roles = new HashMap<>();
        final Map<String, List<byte[]>> faces = new HashMap<>();
        final Set<String> locked = new java.util.HashSet<>();
        int failures;
        int lockCalls;

        @Override
        public List<String> roles(String userId) {
            return roles.getOrDefault(userId, Collections.emptyList());
        }

        @Override
        public List<byte[]> enrolledFaces(String userId) {
            return faces.getOrDefault(userId, Collections.emptyList());
        }

        @Override
        public boolean isLocked(String userId) {
            return locked.contains(userId);
        }

        @Override
        public void recordFailure(String userId) {
            failures++;
        }

        @Override
        public void lock(String userId) {
            lockCalls++;
        }
    }
}
