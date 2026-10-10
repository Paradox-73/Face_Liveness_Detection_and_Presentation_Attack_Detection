/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.api_services;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.onnx.bundle.ModelBundleImporter;
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
import io.mosip.registration_client.liveness.OperatorFaceEvidence;
import io.mosip.registration_client.model.LivenessPigeon;

/**
 * Flutter bridge of face liveness ({@code pigeon/liveness.dart}): policy per workflow, the outcome of the last
 * face capture of a field, operator / supervisor face authentication, and the supervisor's offline import of a
 * signed model bundle. Messages are resolved here, in the display language, from the engine's catalogue, so Flutter
 * shows the same text as the capture screen.
 */
@Singleton
public class LivenessApi implements LivenessPigeon.LivenessApi {

    /** Status sent to Flutter for a successful face authentication. */
    static final String PASSED = "PASSED";
    /** Status sent to Flutter for a failed face authentication. */
    static final String FAILED = "FAILED";
    /** Folder under the app's external files folder that {@link #importModelBundle} reads when no path is given. */
    static final String IMPORT_FOLDER = "liveness-models";

    private final LivenessService liveness;
    private final FaceCaptureLivenessGate gate;
    private final FaceAuthFlow faceAuth;
    private final LivenessCaptureLauncher launcher;
    private final LivenessMessages messages;
    private final AuditManagerService audit;
    private final OperatorFaceEvidence operatorFaces;
    private Executor importExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "liveness-model-import");
        t.setDaemon(true);
        return t;
    });
    private Executor resultExecutor = command -> new Handler(Looper.getMainLooper()).post(command);
    private Activity activity;

    /**
     * Creates the bridge.
     *
     * @param liveness the liveness facade
     * @param gate     face capture rule (last outcome per field)
     * @param faceAuth face authentication flow
     * @param launcher capture screen launcher (display language)
     * @param messages      engine message texts
     * @param audit         ARC's audit trail
     * @param operatorFaces keeps the operator's face of a packet authentication for the packet
     */
    @Inject
    public LivenessApi(LivenessService liveness, FaceCaptureLivenessGate gate, FaceAuthFlow faceAuth,
                       LivenessCaptureLauncher launcher, LivenessMessages messages, AuditManagerService audit,
                       OperatorFaceEvidence operatorFaces) {
        this.liveness = liveness;
        this.gate = gate;
        this.faceAuth = faceAuth;
        this.launcher = launcher;
        this.messages = messages;
        this.audit = audit;
        this.operatorFaces = operatorFaces;
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
        faceAuth.authenticate(userId, w, outcome -> {
            if (w == Workflow.PACKET_AUTH && operatorFaces != null) {
                outcome.capture().ifPresent(operatorFaces::keep);
            }
            result.success(toPigeon(outcome));
        });
    }

    /**
     * Supervisor action: verifies and imports a signed model bundle on a background thread, then answers on the
     * main thread. An empty path means the app's import folder ({@code Android/data/<app>/files/liveness-models}).
     */
    @Override
    public void importModelBundle(@NonNull String path,
                                  @NonNull LivenessPigeon.Result<LivenessPigeon.ModelBundleOutcome> result) {
        File location = path.trim().isEmpty() ? defaultImportFolder() : new File(path.trim());
        if (location == null) {
            result.success(toPigeon(ModelBundleImporter.Result.of(ModelBundleImporter.Status.NOT_FOUND)));
            return;
        }
        importExecutor.execute(() -> {
            ModelBundleImporter.Result r;
            try {
                r = liveness.importModelBundle(location);
            } catch (RuntimeException e) {
                r = ModelBundleImporter.Result.of(ModelBundleImporter.Status.LOAD_FAILED);
            }
            LivenessPigeon.ModelBundleOutcome outcome = toPigeon(r);
            resultExecutor.execute(() -> result.success(outcome));
        });
    }

    @NonNull
    @Override
    public LivenessPigeon.ModelBundleOutcome getModelBundle() {
        return toPigeon(liveness.currentModelBundle()
                .orElse(ModelBundleImporter.Result.of(ModelBundleImporter.Status.NOT_FOUND)));
    }

    /**
     * Replaces the threads of the model import (tests run it on the calling thread).
     *
     * @param work   runs the import
     * @param answer delivers the result to Flutter
     */
    void setImportExecutors(Executor work, Executor answer) {
        this.importExecutor = work;
        this.resultExecutor = answer;
    }

    private File defaultImportFolder() {
        return activity == null ? null : activity.getExternalFilesDir(IMPORT_FOLDER);
    }

    static LivenessPigeon.ModelBundleOutcome toPigeon(ModelBundleImporter.Result r) {
        return new LivenessPigeon.ModelBundleOutcome.Builder()
                .setStatus(r.status().name())
                .setBundle(r.bundle())
                .setVersion(r.version())
                .setPreviousVersion(r.previousVersion())
                .setFromStore(r.isFromStore())
                .build();
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
