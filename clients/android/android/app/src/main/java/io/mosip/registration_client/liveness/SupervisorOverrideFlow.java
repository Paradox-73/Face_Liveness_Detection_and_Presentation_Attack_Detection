package io.mosip.registration_client.liveness;

import android.util.Log;

import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.liveness.core.Workflow;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.SupervisorOverride;
import io.mosip.registration.livenessmanager.auth.FaceAuthOutcome;
import io.mosip.registration.livenessmanager.auth.FaceAuthStatus;

/**
 * Supervisor override of a resident's face capture ({@code on_max_failure=SUPERVISOR_OVERRIDE}) after the
 * resident used up the liveness attempts, in two steps:
 *
 * <ol>
 *   <li>the supervisor named on the capture screen authenticates with the face: liveness, the
 *       {@code REGISTRATION_SUPERVISOR} role and a match against the supervisor's onboarding face, with workflow
 *       {@code exception_auth} (the workflow of supervisor authentication for exceptions);</li>
 *   <li>only then the capture screen takes the resident's photo without a liveness check; it is flagged
 *       {@code LIVENESS_DECISION=OVERRIDE} with the failed transaction's evidence, and the screen writes audit
 *       {@code REG-EVT-LV16} naming the supervisor.</li>
 * </ol>
 *
 * <p>No photo is taken when the supervisor is refused (not a supervisor, face mismatch, liveness failure, or
 * {@code exception_auth} not enforced, which would leave the supervisor unverified).
 */
@Singleton
public class SupervisorOverrideFlow {

    /** Receives the end of the flow on the main thread. */
    public interface Callback {

        /**
         * The supervisor verified and the capture screen returned the resident's photo (a pass flagged as an
         * override), or its failure or cancellation.
         *
         * @param photo the capture screen's result
         */
        void onPhoto(LivenessCaptureResult photo);

        /**
         * The supervisor did not pass face authentication; no photo was taken.
         *
         * @param supervisor the authentication outcome
         */
        void onRefused(FaceAuthOutcome supervisor);
    }

    /** Workflow of the supervisor's face authentication. */
    static final Workflow SUPERVISOR_WORKFLOW = Workflow.EXCEPTION_AUTH;

    private static final String TAG = "Liveness";

    private final FaceAuthFlow faceAuth;
    private final LivenessCaptureLauncher launcher;

    /**
     * Creates the flow.
     *
     * @param faceAuth face authentication (liveness, role, match)
     * @param launcher capture screen launcher
     */
    @Inject
    public SupervisorOverrideFlow(FaceAuthFlow faceAuth, LivenessCaptureLauncher launcher) {
        this.faceAuth = Objects.requireNonNull(faceAuth, "faceAuth");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    /**
     * Runs the override.
     *
     * @param workflow       the resident workflow ({@code registration})
     * @param subject        registration ID
     * @param supervisorId   user ID entered on the capture screen
     * @param failedEvidence packet evidence of the failed transaction
     * @param done           receives the end of the flow
     */
    public void run(Workflow workflow, String subject, String supervisorId, Map<String, String> failedEvidence,
                    Callback done) {
        Objects.requireNonNull(done, "done");
        SupervisorOverride grant;
        try {
            grant = new SupervisorOverride(supervisorId, failedEvidence);
        } catch (IllegalArgumentException e) {
            done.onRefused(FaceAuthOutcome.early(SUPERVISOR_WORKFLOW, FaceAuthStatus.FACE_NOT_ENROLLED, null));
            return;
        }
        faceAuth.authenticate(grant.supervisorId(), SUPERVISOR_WORKFLOW, outcome -> {
            if (!outcome.isSuccess()) {
                Log.i(TAG, "Supervisor override refused: " + outcome.status());
                done.onRefused(outcome);
                return;
            }
            boolean started = launcher.launch(LivenessCaptureRequest.builder(workflow, subject).override(grant),
                    done::onPhoto);
            if (!started) {
                done.onRefused(FaceAuthOutcome.early(SUPERVISOR_WORKFLOW, FaceAuthStatus.BUSY, null));
            }
        });
    }
}
