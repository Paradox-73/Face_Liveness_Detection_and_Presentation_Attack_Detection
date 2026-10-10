/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;
import io.mosip.registration.livenessmanager.auth.FaceAuthOutcome;
import io.mosip.registration.livenessmanager.auth.FaceAuthStatus;
import io.mosip.registration.livenessmanager.auth.FaceAuthenticator;
import io.mosip.registration.livenessmanager.auth.OperatorDirectory;
import io.mosip.registration.livenessmanager.match.FaceMatch;

@RunWith(RobolectricTestRunner.class)
public class SupervisorOverrideFlowTest {

    private static final Map<String, String> FAILED = Collections.singletonMap(PacketEvidenceKeys.SESSION, "S-9");

    private final FakeLivenessService liveness = new FakeLivenessService();
    private final List<LivenessCaptureResult> photos = new ArrayList<>();
    private final List<FaceAuthOutcome> refusals = new ArrayList<>();
    private final List<String> supervisors = new ArrayList<>();
    private FaceMatch match = FaceMatch.compared(80, 40);
    private LivenessCaptureLauncher launcher;
    private SupervisorOverrideFlow flow;

    @Before
    public void setUp() {
        liveness.enforced.add(Workflow.EXCEPTION_AUTH);
        supervisors.add("supervisor1");
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
        flow = new SupervisorOverrideFlow(new FaceAuthFlow(authenticator, launcher), launcher);
    }

    @Test
    public void verifiedSupervisorLeadsToTheOverridePhoto() throws Exception {
        start("supervisor1");
        LivenessCaptureRequest auth = liveness.requests.get(0);
        assertEquals(Workflow.EXCEPTION_AUTH, auth.workflow());
        assertEquals("supervisor1", auth.subjectRef());

        finishScreen(LivenessCaptureResult.builder(Workflow.EXCEPTION_AUTH, Decision.PASS).attempts(1, 3)
                .capture("tx", new byte[] {2}, "{}", true).build());
        waitFor(() -> liveness.requests.size() == 2);

        LivenessCaptureRequest photo = liveness.requests.get(1);
        assertEquals(Workflow.REGISTRATION, photo.workflow());
        assertEquals("rid-1", photo.subjectRef());
        assertNotNull(photo.override());
        assertEquals("supervisor1", photo.override().supervisorId());
        assertEquals(PacketEvidenceKeys.DECISION_OVERRIDE, photo.override().evidence().get(PacketEvidenceKeys.DECISION));
        assertEquals("S-9", photo.override().evidence().get(PacketEvidenceKeys.SESSION));

        LivenessCaptureResult stored = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.PASS)
                .reasonCodes(Collections.singletonList(LivenessCaptureResult.REASON_SUPERVISOR_OVERRIDE)).build();
        finishScreen(stored);
        assertEquals(1, photos.size());
        assertTrue(photos.get(0).isSupervisorOverride());
        assertTrue(refusals.isEmpty());
    }

    @Test
    public void nonSupervisorIsRefusedWithoutOpeningTheCamera() {
        start("officer1");
        assertEquals(FaceAuthStatus.NOT_SUPERVISOR, refusals.get(0).status());
        assertTrue(liveness.requests.isEmpty());
        assertTrue(photos.isEmpty());
    }

    @Test
    public void failedSupervisorLivenessTakesNoPhoto() throws Exception {
        start("supervisor1");
        finishScreen(LivenessCaptureResult.builder(Workflow.EXCEPTION_AUTH, Decision.FAIL_PAD)
                .failureReason(FailureReason.PAD).attempts(3, 3).build());
        waitFor(() -> !refusals.isEmpty());
        assertEquals(1, liveness.requests.size());
        assertTrue(photos.isEmpty());
    }

    @Test
    public void mismatchedSupervisorFaceTakesNoPhoto() throws Exception {
        match = FaceMatch.compared(5, 40);
        start("supervisor1");
        finishScreen(LivenessCaptureResult.builder(Workflow.EXCEPTION_AUTH, Decision.PASS).attempts(1, 3)
                .capture("tx", new byte[] {2}, "{}", true).build());
        waitFor(() -> !refusals.isEmpty());
        assertEquals(FaceAuthStatus.FACE_MISMATCH, refusals.get(0).status());
        assertEquals(1, liveness.requests.size());
    }

    @Test
    public void operatorCannotApproveTheirOwnOverride() {
        start("officer9", " OFFICER9 ");
        supervisors.add("officer9");
        start("officer9", "officer9");
        start("", "supervisor1");
        assertEquals(3, refusals.size());
        assertEquals(FaceAuthStatus.NOT_SUPERVISOR, refusals.get(1).status());
        assertTrue(liveness.requests.isEmpty());
        assertTrue(photos.isEmpty());
    }

    @Test
    public void unverifiableSupervisorWorkflowRefuses() {
        liveness.enforced.remove(Workflow.EXCEPTION_AUTH);
        start("supervisor1");
        assertEquals(FaceAuthStatus.NOT_ENFORCED, refusals.get(0).status());
        assertTrue(photos.isEmpty());
        assertTrue(liveness.requests.isEmpty());
    }

    private void start(String supervisor) {
        start("officer9", supervisor);
    }

    private void start(String operator, String supervisor) {
        flow.run(Workflow.REGISTRATION, "rid-1", operator, supervisor, FAILED, new SupervisorOverrideFlow.Callback() {
            @Override
            public void onPhoto(LivenessCaptureResult photo) {
                photos.add(photo);
            }

            @Override
            public void onRefused(FaceAuthOutcome supervisorOutcome) {
                refusals.add(supervisorOutcome);
            }
        });
    }

    private void finishScreen(LivenessCaptureResult result) {
        liveness.nextResult = result;
        launcher.onActivityResult(LivenessService.REQUEST_CODE, Activity.RESULT_OK, null);
    }

    /** The face match runs on a worker thread and posts its outcome to the main thread. */
    private static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
