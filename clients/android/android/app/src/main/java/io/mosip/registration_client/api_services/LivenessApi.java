package io.mosip.registration_client.api_services;

import android.app.Activity;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collections;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.liveness.core.Workflow;
import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.livenessmanager.LivenessService;
import io.mosip.registration.livenessmanager.auth.FaceAuthOutcome;
import io.mosip.registration_client.liveness.CaptureOutcome;
import io.mosip.registration_client.liveness.FaceAuthFlow;
import io.mosip.registration_client.liveness.FaceCaptureLivenessGate;
import io.mosip.registration_client.liveness.LivenessCaptureLauncher;
import io.mosip.registration_client.liveness.LivenessMessages;
import io.mosip.registration_client.model.LivenessPigeon;

/**
 * Flutter bridge of face liveness ({@code pigeon/liveness.dart}): policy per workflow, the outcome of the last
 * face capture of a field, and operator / supervisor face authentication. Messages are resolved here, in the
 * display language, from the engine's catalogue, so Flutter shows the same text as the capture screen.
 */
@Singleton
public class LivenessApi implements LivenessPigeon.LivenessApi {

    /** Status sent to Flutter for a successful face authentication. */
    static final String PASSED = "PASSED";
    /** Status sent to Flutter for a failed face authentication. */
    static final String FAILED = "FAILED";

    private final LivenessService liveness;
    private final FaceCaptureLivenessGate gate;
    private final FaceAuthFlow faceAuth;
    private final LivenessCaptureLauncher launcher;
    private final LivenessMessages messages;
    private final AuditManagerService audit;
    private Activity activity;

    /**
     * Creates the bridge.
     *
     * @param liveness the liveness facade
     * @param gate     face capture rule (last outcome per field)
     * @param faceAuth face authentication flow
     * @param launcher capture screen launcher (display language)
     * @param messages engine message texts
     * @param audit    ARC's audit trail
     */
    @Inject
    public LivenessApi(LivenessService liveness, FaceCaptureLivenessGate gate, FaceAuthFlow faceAuth,
                       LivenessCaptureLauncher launcher, LivenessMessages messages, AuditManagerService audit) {
        this.liveness = liveness;
        this.gate = gate;
        this.faceAuth = faceAuth;
        this.launcher = launcher;
        this.messages = messages;
        this.audit = audit;
    }

    /**
     * Sets the activity used for message resources.
     *
     * @param activity ARC's main activity
     */
    public void setCallbackActivity(Activity activity) {
        this.activity = activity;
    }

    @NonNull
    @Override
    public Boolean isEnforced(@NonNull String workflow) {
        return Workflow.fromId(workflow).map(liveness::isEnforced).orElse(false);
    }

    @NonNull
    @Override
    public Boolean isFaceAuthAvailable(@NonNull String userId, @NonNull String workflow) {
        return Workflow.fromId(workflow).filter(Workflow::isAuth).map(w -> faceAuth.isAvailable(userId, w))
                .orElse(false);
    }

    @Override
    public void setLanguage(@NonNull String languageCode) {
        launcher.setLanguage(languageCode);
    }

    @NonNull
    @Override
    public LivenessPigeon.LivenessOutcome getLastCaptureOutcome(@NonNull String fieldId) {
        CaptureOutcome outcome = gate.lastOutcome(fieldId);
        return new LivenessPigeon.LivenessOutcome.Builder()
                .setStatus(outcome.status().name())
                .setMessage(text(outcome.messageKey(), outcome.args()))
                .setAttemptsUsed((long) outcome.attemptsUsed())
                .setMaxAttempts((long) outcome.maxAttempts())
                .build();
    }

    @Override
    public void authenticateFace(@NonNull String userId, @NonNull String workflow,
                                 @NonNull LivenessPigeon.Result<LivenessPigeon.LivenessOutcome> result) {
        Workflow w = Workflow.fromId(workflow).filter(Workflow::isAuth).orElse(null);
        if (w == null) {
            result.success(new LivenessPigeon.LivenessOutcome.Builder().setStatus(FAILED)
                    .setErrorCode("NOT_ENFORCED").setAttemptsUsed(0L).setMaxAttempts(0L).build());
            return;
        }
        auditStart(w);
        faceAuth.authenticate(userId, w, outcome -> result.success(toPigeon(outcome)));
    }

    /** Same user events as the desktop client's face authentication. */
    private void auditStart(Workflow workflow) {
        if (workflow == Workflow.LOGIN_AUTH) {
            audit.audit(AuditEvent.LOGIN_WITH_FACE, Components.LOGIN);
        } else if (workflow == Workflow.EOD_AUTH || workflow == Workflow.EXCEPTION_AUTH) {
            audit.audit(AuditEvent.REG_SUPERVISOR_AUTH_FACE, Components.REGISTRATION);
        } else {
            audit.audit(AuditEvent.REG_OPERATOR_AUTH_FACE, Components.REGISTRATION);
        }
    }

    private LivenessPigeon.LivenessOutcome toPigeon(FaceAuthOutcome outcome) {
        java.util.List<String> args = outcome.maxAttempts() > 0
                ? Arrays.asList(String.valueOf(outcome.attemptsUsed()), String.valueOf(outcome.maxAttempts()))
                : Collections.emptyList();
        return new LivenessPigeon.LivenessOutcome.Builder()
                .setStatus(outcome.isSuccess() ? PASSED : FAILED)
                .setErrorCode(outcome.isSuccess() ? null : outcome.status().name())
                .setMessage(text(outcome.messageKey(), args))
                .setAttemptsUsed((long) outcome.attemptsUsed())
                .setMaxAttempts((long) outcome.maxAttempts())
                .build();
    }

    private String text(String key, java.util.List<String> args) {
        if (key == null || activity == null) {
            return null;
        }
        return messages.text(activity, launcher.language(), key, args);
    }
}
