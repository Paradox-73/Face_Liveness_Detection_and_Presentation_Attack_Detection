package io.mosip.registration.livenessmanager.auth;

import android.util.Log;

import java.util.List;
import java.util.Objects;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;
import io.mosip.registration.livenessmanager.match.FaceMatch;
import io.mosip.registration.livenessmanager.match.FaceVerifier;

/**
 * Operator and supervisor face authentication: liveness first, then a one-to-one match against the face the user
 * enrolled at onboarding. Everything runs on the device, so it works offline.
 *
 * <p>Use in two steps around the capture screen:
 * <ol>
 * <li>{@link #precheck} before the screen opens: liveness switched on for the workflow, account not locked,
 * supervisor role for supervisor workflows, an enrolled face. A refusal here opens no camera.</li>
 * <li>{@link #complete} with the screen's result: a liveness pass is matched; a failure is mapped to a status and
 * the engine's message. {@code BLOCK_AND_LOCK} after the last attempt locks the account; a face mismatch at
 * sign-in counts as a failed sign-in, like a wrong password.</li>
 * </ol>
 */
public final class FaceAuthenticator {

    /** Role that supervisor workflows require. */
    public static final String SUPERVISOR_ROLE = "REGISTRATION_SUPERVISOR";

    private static final String TAG = "Liveness";

    private final LivenessService liveness;
    private final OperatorDirectory directory;
    private final FaceVerifier verifier;

    /**
     * Creates the authenticator.
     *
     * @param liveness  liveness facade (policy per workflow)
     * @param directory roles, enrolled faces and lockout
     * @param verifier  face matcher
     */
    public FaceAuthenticator(LivenessService liveness, OperatorDirectory directory, FaceVerifier verifier) {
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    /**
     * Tells whether a workflow needs a supervisor.
     *
     * @param workflow the workflow
     * @return {@code true} for exception (reviewer) and end-of-day approval authentication
     */
    public static boolean isSupervisorWorkflow(Workflow workflow) {
        return workflow == Workflow.EXCEPTION_AUTH || workflow == Workflow.EOD_AUTH;
    }

    /**
     * Tells whether face authentication can be offered to this user in this workflow (the screens show the face
     * option only then).
     *
     * @param userId   user ID
     * @param workflow an authentication workflow
     * @return {@code true} when {@link #precheck} would let the capture start
     */
    public boolean isAvailable(String userId, Workflow workflow) {
        FaceAuthOutcome refused = precheck(userId, workflow);
        if (refused != null) {
            Log.d(TAG, "Face authentication not offered for " + workflow.id() + ": " + refused.status());
        }
        return refused == null;
    }

    /**
     * Checks everything that does not need the camera.
     *
     * @param userId   user ID
     * @param workflow an authentication workflow
     * @return {@code null} when the capture may start, else the outcome to report
     */
    public FaceAuthOutcome precheck(String userId, Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        if (!workflow.isAuth() || !liveness.isEnforced(workflow)) {
            return FaceAuthOutcome.early(workflow, FaceAuthStatus.NOT_ENFORCED, null);
        }
        if (userId == null || userId.trim().isEmpty()) {
            return FaceAuthOutcome.early(workflow, FaceAuthStatus.FACE_NOT_ENROLLED, null);
        }
        if (directory.isLocked(userId)) {
            return FaceAuthOutcome.early(workflow, FaceAuthStatus.USER_LOCKED, null);
        }
        if (isSupervisorWorkflow(workflow) && !hasRole(userId, SUPERVISOR_ROLE)) {
            return FaceAuthOutcome.early(workflow, FaceAuthStatus.NOT_SUPERVISOR, MessageKeys.ERROR_NOT_SUPERVISOR);
        }
        if (directory.enrolledFaces(userId).isEmpty()) {
            return FaceAuthOutcome.early(workflow, FaceAuthStatus.FACE_NOT_ENROLLED, null);
        }
        return null;
    }

    /**
     * Finishes an authentication with the capture screen's result.
     *
     * @param userId   user ID the capture was made for
     * @param workflow the workflow
     * @param result   the capture screen's result
     * @return the outcome
     */
    public FaceAuthOutcome complete(String userId, Workflow workflow, LivenessCaptureResult result) {
        Objects.requireNonNull(result, "result");
        int used = result.attemptsUsed();
        int max = result.maxAttempts();
        if (result.isPass()) {
            FaceAuthOutcome matched = match(userId, workflow, result.faceIso(), used, max);
            return matched.isSuccess() ? matched.withCapture(result) : matched;
        }
        if (result.isLockRequired()) {
            directory.lock(userId);
        }
        FaceAuthStatus status = failureStatus(result);
        String key = failureMessageKey(status, result);
        Log.i(TAG, "Face authentication " + workflow.id() + ": " + status);
        return new FaceAuthOutcome(workflow, status, key, used, max);
    }

    private FaceAuthOutcome match(String userId, Workflow workflow, byte[] probe, int used, int max) {
        List<byte[]> gallery = directory.enrolledFaces(userId);
        FaceMatch match = verifier.verify(probe, gallery);
        Log.i(TAG, "Face authentication " + workflow.id() + ": " + match);
        switch (match.status()) {
            case MATCHED:
                return new FaceAuthOutcome(workflow, FaceAuthStatus.SUCCESS, MessageKeys.FEEDBACK_SUCCESS, used, max);
            case NOT_MATCHED:
                if (workflow == Workflow.LOGIN_AUTH) {
                    directory.recordFailure(userId);
                }
                return new FaceAuthOutcome(workflow, FaceAuthStatus.FACE_MISMATCH, null, used, max);
            case NO_GALLERY:
                return new FaceAuthOutcome(workflow, FaceAuthStatus.FACE_NOT_ENROLLED, null, used, max);
            case NO_PROBE_FACE:
                return new FaceAuthOutcome(workflow, FaceAuthStatus.LIVENESS_FAILED, MessageKeys.ERROR_POOR_QUALITY,
                        used, max);
            case UNAVAILABLE:
            default:
                return new FaceAuthOutcome(workflow, FaceAuthStatus.ENGINE_UNAVAILABLE, MessageKeys.ERROR_ENGINE,
                        used, max);
        }
    }

    private static FaceAuthStatus failureStatus(LivenessCaptureResult result) {
        FailureReason reason = result.failureReason();
        if (result.decision() == Decision.CANCELLED || reason == FailureReason.CANCELLED) {
            return FaceAuthStatus.CANCELLED;
        }
        if (reason == FailureReason.ENGINE) {
            return FaceAuthStatus.ENGINE_UNAVAILABLE;
        }
        if (!result.isRetryAllowed() && reason != null && reason.countsAsAttempt()) {
            return FaceAuthStatus.MAX_ATTEMPTS;
        }
        return FaceAuthStatus.LIVENESS_FAILED;
    }

    private static String failureMessageKey(FaceAuthStatus status, LivenessCaptureResult result) {
        switch (status) {
            case MAX_ATTEMPTS:
                return MessageKeys.RETRY_MAX_AUTH;
            case ENGINE_UNAVAILABLE:
                return MessageKeys.ERROR_ENGINE;
            case LIVENESS_FAILED:
                String key = result.messageKey();
                return key == null || key.isEmpty() ? MessageKeys.FAIL_LIVENESS : key;
            default:
                return null;
        }
    }

    private boolean hasRole(String userId, String role) {
        for (String r : directory.roles(userId)) {
            if (role.equalsIgnoreCase(r)) {
                return true;
            }
        }
        return false;
    }
}
