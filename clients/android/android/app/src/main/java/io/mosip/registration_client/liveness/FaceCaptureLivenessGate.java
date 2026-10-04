package io.mosip.registration_client.liveness;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;
import io.mosip.registration.livenessmanager.SupervisorOverride;
import io.mosip.registration.livenessmanager.auth.FaceAuthOutcome;
import io.mosip.registration.livenessmanager.ui.MessageResolver;

/**
 * Liveness rule for face captures that end up stored: the resident's face in a registration (workflow
 * {@code registration}) and the operator's face at onboarding ({@code onboard_auth}), which later serves as the
 * reference for face authentication.
 *
 * <p>The capture screen runs the retries of one transaction itself. This gate adds the limit across
 * transactions: failed attempts are counted per subject (registration ID or operator) and field, so closing the
 * screen and pressing Scan again does not reset {@code max_retries}. The screen reports the attempts it counted
 * (including those it was told about), and the gate keeps the higher of that and its own count whatever the
 * screen's last result was: "PAD, PAD, Cancel" and "PAD, PAD, poor quality" keep 2 attempts. Once the limit is
 * reached (or the screen reports that no retry is left), face capture stays blocked for that registration; a new
 * registration starts a new count. A pass resets the count. Only a pass stores a face (PS04 task 5).
 *
 * <p>The exception photo (taken when fingers or irises are marked as exceptions) uses the same screen with the
 * workflow {@code exception_photo}, counted under its own key ({@link #exceptionPhotoField}): with the shipped
 * policy {@code OFF} the built-in camera takes a signed photo without a liveness check, as on the desktop client.
 *
 * <p>{@code SUPERVISOR_OVERRIDE} (resident workflows, docs/design/android-liveness.md): when the last attempt
 * failed for a reason that allows an override (not a suspected attack, frame integrity or binding failure), the gate keeps
 * the failed transaction's packet evidence ({@link #overrideEvidence}). A counted PAD, integrity or binding
 * failure on any earlier screen of the same subject and field marks a suspected attack, which forbids the
 * override until a pass, even when the last attempts ended as timeouts. The capture screen offers the override;
 * after the supervisor passed face authentication with liveness ({@code SupervisorOverrideFlow}), the resident's
 * photo is stored with {@code LIVENESS_DECISION=OVERRIDE} ({@link #recordOverride}). Until then the field stays
 * blocked, and pressing Scan again offers the override again instead of a new liveness check.
 *
 * <p>Dim light: the screen ends a PAD rejection of a dark face as "Improve lighting", not counted, up to
 * {@code passive.low_light_max_uncounted} times since the last counted failure. The gate keeps the screen's count
 * ({@link LivenessCaptureResult#lowLightRetries()}) per subject and field, so closing the screen and pressing Scan
 * again gives no new uncounted tries; a counted failure, a block or a pass resets it. The next screen receives the
 * attempts, the suspected-attack mark and this count ({@link #nextRequest}).
 */
@Singleton
public class FaceCaptureLivenessGate {

    /** Field ID of operator onboarding biometrics in ARC. */
    public static final String OPERATOR_FIELD = "operatorBiometrics";
    /** Suffix of the gate's key for a field's exception photo. */
    static final String EXCEPTION_PHOTO_SUFFIX = "#exceptionPhoto";

    private final LivenessService liveness;
    private final Map<String, Integer> failedAttempts = new HashMap<>();
    private final Map<String, CaptureOutcome> lastOutcome = new HashMap<>();
    private final Map<String, Map<String, String>> overrideEvidence = new HashMap<>();
    private final Set<String> suspectedAttack = new HashSet<>();
    private final Map<String, Integer> lowLightRetries = new HashMap<>();

    /**
     * Creates the gate.
     *
     * @param liveness the liveness facade
     */
    @Inject
    public FaceCaptureLivenessGate(LivenessService liveness) {
        this.liveness = liveness;
    }

    /**
     * Returns the workflow of a face capture field.
     *
     * @param fieldId ARC field ID
     * @return {@code onboard_auth} for operator onboarding, else {@code registration}
     */
    public static Workflow workflowFor(String fieldId) {
        if (fieldId != null && fieldId.endsWith(EXCEPTION_PHOTO_SUFFIX)) {
            return Workflow.EXCEPTION_PHOTO;
        }
        return OPERATOR_FIELD.equals(fieldId) ? Workflow.ONBOARD_AUTH : Workflow.REGISTRATION;
    }

    /**
     * Returns the gate's key for the exception photo of a field, so its attempts and outcome are kept apart from
     * the field's face capture.
     *
     * @param fieldId ARC field ID
     * @return the key, whose workflow is {@code exception_photo}
     */
    public static String exceptionPhotoField(String fieldId) {
        return fieldId + EXCEPTION_PHOTO_SUFFIX;
    }

    /**
     * Tells whether the built-in camera takes the exception photo (it is the tablet's face device). Otherwise
     * ARC asks the vendor SBI face app, as upstream.
     *
     * @return {@code true} for the built-in camera
     */
    public boolean takesExceptionPhotos() {
        return liveness.isBuiltInCameraFaceDevice();
    }

    /**
     * Tells whether a face capture of this field must pass liveness.
     *
     * @param fieldId ARC field ID
     * @return {@code true} when liveness is enforced for the field's workflow
     */
    public boolean isRequired(String fieldId) {
        return liveness.isEnforced(workflowFor(fieldId));
    }

    /**
     * Checks whether a new capture may start.
     *
     * @param subject registration ID or operator user ID
     * @param fieldId ARC field ID
     * @return {@code null} when it may start, else the blocked outcome (also kept as the field's last outcome)
     */
    public synchronized CaptureOutcome admit(String subject, String fieldId) {
        int max = maxAttempts(fieldId);
        int failed = failedAttempts.getOrDefault(key(subject, fieldId), 0);
        if (failed < max) {
            return null;
        }
        CaptureOutcome blocked = blocked(fieldId, failed, max);
        lastOutcome.put(fieldId, blocked);
        return blocked;
    }

    /**
     * Returns the attempts the subject already used for this field on earlier capture screens, so the next screen
     * continues the count.
     *
     * @param subject registration ID or operator user ID
     * @param fieldId ARC field ID
     * @return failed attempts that count, 0 or more
     */
    public synchronized int usedAttempts(String subject, String fieldId) {
        return failedAttempts.getOrDefault(key(subject, fieldId), 0);
    }

    /**
     * Returns the uncounted dim-light results of the subject for this field since the last counted failure, so the
     * next screen continues the dim-light allowance instead of starting it again.
     *
     * @param subject registration ID or operator user ID
     * @param fieldId ARC field ID
     * @return 0 or more
     */
    public synchronized int lowLightRetries(String subject, String fieldId) {
        return lowLightRetries.getOrDefault(key(subject, fieldId), 0);
    }

    /**
     * Starts the request of the next capture screen of the field with the state of the earlier screens: the
     * attempts used, the suspected-attack mark and the dim-light count.
     *
     * @param subject      registration ID or operator user ID
     * @param fieldId      ARC field ID (its workflow is {@link #workflowFor})
     * @param photoWhenOff take a signed photo without liveness when the workflow's policy is {@code OFF}
     * @return the request builder
     */
    public synchronized LivenessCaptureRequest.Builder nextRequest(String subject, String fieldId,
                                                                  boolean photoWhenOff) {
        String key = key(subject, fieldId);
        return LivenessCaptureRequest.builder(workflowFor(fieldId), subject).photoWhenOff(photoWhenOff)
                .priorAttempts(failedAttempts.getOrDefault(key, 0))
                .priorSuspectedAttack(suspectedAttack.contains(key))
                .priorLowLight(lowLightRetries.getOrDefault(key, 0));
    }

    /**
     * Records the capture screen's result and returns the outcome to show.
     *
     * @param subject registration ID or operator user ID
     * @param fieldId ARC field ID
     * @param result  the capture screen's result
     * @return the outcome
     */
    public synchronized CaptureOutcome record(String subject, String fieldId, LivenessCaptureResult result) {
        String key = key(subject, fieldId);
        int max = Math.max(maxAttempts(fieldId), result.maxAttempts());
        CaptureOutcome outcome;
        if (result.isPass()) {
            failedAttempts.remove(key);
            overrideEvidence.remove(key);
            suspectedAttack.remove(key);
            lowLightRetries.remove(key);
            outcome = new CaptureOutcome(CaptureOutcome.Status.PASSED, MessageKeys.FEEDBACK_SUCCESS,
                    Collections.emptyList(), result.attemptsUsed(), max);
        } else {
            FailureReason reason = result.failureReason();
            boolean counts = reason != null && reason.countsAsAttempt();
            if (counts && !reason.allowsOverride()) {
                suspectedAttack.add(key);
            }
            int storedFailed = failedAttempts.getOrDefault(key, 0);
            recordLowLight(key, counts, result.attemptsUsed() > storedFailed, result.lowLightRetries());
            // The screen reports the attempts of the whole count, including those it was told about, whatever its
            // last result was (a cancellation or a poor-quality frame after counted failures keeps them).
            int failed = Math.max(storedFailed, result.attemptsUsed());
            if (failed > 0) {
                failedAttempts.put(key, failed);
            }
            if (counts && (failed >= max || !result.isRetryAllowed())) {
                failedAttempts.put(key, Math.max(failed, max));
                if (result.isOverrideAllowed() && isOverridePolicy(fieldId) && !suspectedAttack.contains(key)) {
                    overrideEvidence.put(key, new HashMap<>(result.evidence()));
                } else {
                    overrideEvidence.remove(key);
                }
                outcome = blocked(fieldId, Math.max(failed, max), max);
            } else if (result.attemptsUsed() == 0 || reason == FailureReason.CANCELLED) {
                outcome = new CaptureOutcome(CaptureOutcome.Status.CANCELLED, null, Collections.emptyList(),
                        failed, max);
            } else {
                String message = result.messageKey() == null ? MessageKeys.FAIL_LIVENESS : result.messageKey();
                outcome = new CaptureOutcome(CaptureOutcome.Status.FAILED, message, Collections.emptyList(),
                        failed, max);
            }
        }
        lastOutcome.put(fieldId, outcome);
        return outcome;
    }

    /**
     * Returns the packet evidence of a failed transaction a supervisor may still override.
     *
     * @param subject registration ID
     * @param fieldId ARC field ID
     * @return the evidence, or {@code null} when no override is possible for this field (also after a suspected
     *         attack on any of the subject's screens for this field)
     */
    public synchronized Map<String, String> overrideEvidence(String subject, String fieldId) {
        String key = key(subject, fieldId);
        Map<String, String> evidence = overrideEvidence.get(key);
        return evidence == null || !isOverridePolicy(fieldId) || suspectedAttack.contains(key) ? null
                : new HashMap<>(evidence);
    }

    /**
     * Records the resident photo taken under a supervisor override.
     *
     * @param subject registration ID
     * @param fieldId ARC field ID
     * @param photo   the capture screen's result in override mode
     * @return the outcome: passed with the override message, or still blocked
     */
    public synchronized CaptureOutcome recordOverride(String subject, String fieldId, LivenessCaptureResult photo) {
        String key = key(subject, fieldId);
        int max = maxAttempts(fieldId);
        CaptureOutcome outcome;
        if (photo.isSupervisorOverride() && overrideEvidence.containsKey(key) && !suspectedAttack.contains(key)) {
            failedAttempts.remove(key);
            overrideEvidence.remove(key);
            lowLightRetries.remove(key);
            outcome = new CaptureOutcome(CaptureOutcome.Status.PASSED, MessageResolver.OVERRIDE_USED,
                    Collections.emptyList(), max, max);
        } else {
            outcome = blocked(fieldId, failedAttempts.getOrDefault(key, max), max);
        }
        lastOutcome.put(fieldId, outcome);
        return outcome;
    }

    /**
     * Records that the supervisor did not pass face authentication, so no override was granted. The field stays
     * blocked; the override can be offered again.
     *
     * @param subject    registration ID
     * @param fieldId    ARC field ID
     * @param supervisor the supervisor's face authentication outcome
     * @return the blocked outcome, with the authentication's message when it has one
     */
    public synchronized CaptureOutcome recordOverrideRefused(String subject, String fieldId,
                                                             FaceAuthOutcome supervisor) {
        int max = maxAttempts(fieldId);
        int failed = failedAttempts.getOrDefault(key(subject, fieldId), max);
        String message = supervisor == null || supervisor.messageKey() == null ? MessageKeys.ERROR_NOT_SUPERVISOR
                : supervisor.messageKey();
        CaptureOutcome outcome = new CaptureOutcome(CaptureOutcome.Status.BLOCKED, message,
                Collections.emptyList(), failed, max);
        lastOutcome.put(fieldId, outcome);
        return outcome;
    }

    /**
     * Records that a passing capture was then refused by the client's own checks (device signature, trust,
     * transaction), so no face was stored.
     *
     * @param fieldId ARC field ID
     * @return the outcome to show
     */
    public synchronized CaptureOutcome recordRejected(String fieldId) {
        CaptureOutcome outcome = new CaptureOutcome(CaptureOutcome.Status.FAILED, MessageKeys.FAIL_PAD,
                Collections.emptyList(), 0, maxAttempts(fieldId));
        lastOutcome.put(fieldId, outcome);
        return outcome;
    }

    /**
     * Returns the outcome of the field's last face capture.
     *
     * @param fieldId ARC field ID
     * @return the outcome; {@link CaptureOutcome#none()} when no liveness capture ran for it
     */
    public synchronized CaptureOutcome lastOutcome(String fieldId) {
        return lastOutcome.getOrDefault(fieldId, CaptureOutcome.none());
    }

    /**
     * Forgets the field's last outcome (a capture without liveness is starting).
     *
     * @param fieldId ARC field ID
     */
    public synchronized void clearOutcome(String fieldId) {
        lastOutcome.remove(fieldId);
    }

    /**
     * Keeps the screen's dim-light count. A counted failure resets it. A screen that counted a failure of its own
     * restarted the count, so its number replaces the stored one; otherwise the screen started from the stored
     * number and the higher of the two is kept (a result without a session, such as a cancellation before the
     * first attempt ended, reports none).
     */
    private void recordLowLight(String key, boolean counted, boolean countedOnThisScreen, int screenCount) {
        int count = counted ? 0
                : countedOnThisScreen ? screenCount : Math.max(lowLightRetries.getOrDefault(key, 0), screenCount);
        if (count > 0) {
            lowLightRetries.put(key, count);
        } else {
            lowLightRetries.remove(key);
        }
    }

    private int maxAttempts(String fieldId) {
        return Math.max(1, liveness.policy(workflowFor(fieldId)).maxRetries());
    }

    private boolean isOverridePolicy(String fieldId) {
        return SupervisorOverride.isAllowedBy(liveness.policy(workflowFor(fieldId)));
    }

    /**
     * Tells whether a counted PAD, integrity or binding failure was recorded for the subject and field since the
     * last pass.
     *
     * @param subject registration ID or operator user ID
     * @param fieldId ARC field ID
     * @return {@code true} when a supervisor override must not be offered
     */
    public synchronized boolean isSuspectedAttack(String subject, String fieldId) {
        return suspectedAttack.contains(key(subject, fieldId));
    }

    private static CaptureOutcome blocked(String fieldId, int failed, int max) {
        boolean resident = workflowFor(fieldId).isResident();
        return new CaptureOutcome(CaptureOutcome.Status.BLOCKED,
                resident ? MessageKeys.RETRY_MAX_RESIDENT : MessageKeys.RETRY_MAX_AUTH,
                Arrays.asList(String.valueOf(max)), failed, max);
    }

    private static String key(String subject, String fieldId) {
        return (subject == null ? "" : subject) + '|' + fieldId;
    }
}
