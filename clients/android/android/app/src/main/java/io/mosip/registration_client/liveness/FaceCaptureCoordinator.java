/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.NoStreamPolicy;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.engine.LivenessUiStateMapper;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.clientmanager.config.SessionManager;
import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.constant.Modality;
import io.mosip.registration.clientmanager.constant.RegistrationConstants;
import io.mosip.registration.clientmanager.dto.sbi.CaptureRequest;
import io.mosip.registration.clientmanager.dto.sbi.DiscoverRequest;
import io.mosip.registration.clientmanager.repository.GlobalParamRepository;
import io.mosip.registration.clientmanager.service.Biometrics095Service;
import io.mosip.registration.clientmanager.service.UserOnboardService;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.clientmanager.spi.RegistrationService;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.auth.FaceAuthOutcome;
import io.mosip.registration.livenessmanager.config.GlobalParamConfigSource;
import io.mosip.registration.livenessmanager.device.FaceDeviceSelection;
import io.mosip.registration.livenessmanager.device.SbiIntentDevice;
import io.mosip.registration.livenessmanager.ui.MessageResolver;
import io.mosip.registration.packetmanager.cbeffutil.jaxbclasses.SingleType;
import io.mosip.registration_client.model.BiometricsPigeon;

/**
 * The face liveness path of ARC's biometric capture: the one entry point {@code BiometricsDetailsApi} calls for a
 * FACE capture or the exception photo ({@link #tryStart}), and the SBI intent answers it waits for
 * ({@link #deliverSbiAnswer}). Everything else about liveness on the capture screen lives here, so the upstream
 * class keeps two delegating calls.
 *
 * <p>The face device is {@code mosip.registration.liveness.android.device} ({@link FaceDeviceSelection}):
 * <ul>
 *   <li>built-in camera ({@code BUILTIN}, the default; also for an unknown value): the liveness capture screen when
 *       liveness is enforced, else the same screen takes a signed photo without a liveness check, so a tablet
 *       without a face SBI app still has a face device with {@code mosip.registration.liveness.enable=N};</li>
 *   <li>vendor SBI face app ({@code SBI}): {@link SbiIntentDevice} over ARC's SBI intents when liveness is
 *       enforced, else ARC's upstream SBI capture. Without an installed SBI app the built-in camera is used.</li>
 * </ul>
 * The exception photo uses the built-in camera's screen (which applies the {@code exception_photo} policy) unless
 * the vendor app is the face device. Each capture carries its own field and modality from start to store; a second
 * start while one is running is answered {@code BUSY}.
 */
public final class FaceCaptureCoordinator {

    /** Request code of ARC's SBI discovery intent. */
    public static final int SBI_DISCOVER_REQUEST = 1;
    /** Request code of ARC's SBI device-info intent. */
    public static final int SBI_INFO_REQUEST = 2;
    /** Request code of ARC's SBI capture intent. */
    public static final int SBI_CAPTURE_REQUEST = 3;

    private static final String TAG = "BiometricsDetailApi";
    /** ARC's field ID of the operator onboarding capture. */
    private static final String OPERATOR_BIOMETRICS = "operatorBiometrics";
    private static final long SBI_INTENT_TIMEOUT_MS = 30_000L;

    /** What the coordinator needs from ARC's capture API. */
    public interface Host {

        /**
         * Takes over the pending Flutter capture call.
         *
         * @param result the call
         */
        void begin(BiometricsPigeon.Result<String> result);

        /**
         * Completes the pending Flutter capture call once.
         *
         * @param status {@code Ok} or a {@link CaptureOutcome.Status} name
         */
        void complete(String status);

        /**
         * Stores an rCapture response through ARC's SBI response checks (signature, device trust, timestamp,
         * transaction ID) and completes the Flutter call when it was stored.
         *
         * @param fieldId   the field of the capture
         * @param modality  FACE or EXCEPTION_PHOTO
         * @param response  the rCapture response JSON
         * @param evidence  LIVENESS_* packet evidence, or {@code null} for a photo without a liveness check
         * @return {@code false} when ARC refused the response
         */
        boolean store(String fieldId, Modality modality, String response, Map<String, String> evidence);

        /**
         * Returns the exception attributes of the capture being started.
         *
         * @return the attributes marked as exceptions
         */
        List<String> exceptionAttributes();
    }

    private final Supplier<Activity> activity;
    private final Host host;
    private final AuditManagerService auditManagerService;
    private final ObjectMapper objectMapper;
    private final Biometrics095Service biometricsService;
    private final RegistrationService registrationService;
    private final GlobalParamRepository globalParamRepository;
    private final UserOnboardService userOnboardService;

    private FaceCaptureLivenessGate livenessGate;
    private LivenessCaptureLauncher livenessLauncher;
    private SupervisorOverrideFlow livenessOverride;
    private ConfigSource livenessConfig;
    private boolean sbiCaptureRunning;
    private ExecutorService sbiWorker;
    private volatile SbiIntentCall pendingSbiCall;

    /**
     * Creates the coordinator of one capture API.
     *
     * @param activity              the current activity (set after construction)
     * @param host                  the capture API
     * @param auditManagerService   ARC's audit service
     * @param objectMapper          JSON mapper of the SBI requests
     * @param biometricsService     ARC's SBI 0.9.5 service
     * @param registrationService   the registration being captured
     * @param globalParamRepository synced configuration
     * @param userOnboardService    the operator onboarding being captured
     */
    public FaceCaptureCoordinator(Supplier<Activity> activity, Host host, AuditManagerService auditManagerService,
                                  ObjectMapper objectMapper, Biometrics095Service biometricsService,
                                  RegistrationService registrationService,
                                  GlobalParamRepository globalParamRepository,
                                  UserOnboardService userOnboardService) {
        this.activity = activity;
        this.host = host;
        this.auditManagerService = auditManagerService;
        this.objectMapper = objectMapper;
        this.biometricsService = biometricsService;
        this.registrationService = registrationService;
        this.globalParamRepository = globalParamRepository;
        this.userOnboardService = userOnboardService;
    }

    /**
     * Routes FACE captures through face liveness when the workflow's policy requires it.
     *
     * @param gate     liveness rule for stored face captures
     * @param launcher liveness capture screen launcher
     */
    public void setLivenessGate(FaceCaptureLivenessGate gate, LivenessCaptureLauncher launcher) {
        this.livenessGate = gate;
        this.livenessLauncher = launcher;
    }

    /**
     * Replaces the liveness configuration the face device is chosen from (tests); by default the synced global
     * parameters, the same source as the liveness module's.
     *
     * @param config the configuration
     */
    public void setLivenessConfig(ConfigSource config) {
        this.livenessConfig = config;
    }

    /**
     * Enables the supervisor override of a resident's failed face capture ({@code on_max_failure=SUPERVISOR_OVERRIDE}).
     *
     * @param override the override flow (supervisor face authentication, then the resident's photo)
     */
    public void setSupervisorOverrideFlow(SupervisorOverrideFlow override) {
        this.livenessOverride = override;
    }

    /**
     * Starts a FACE capture or the exception photo on the liveness path, when it applies.
     *
     * @param fieldId  the field being captured
     * @param modality the modality being captured
     * @param result   the Flutter capture call
     * @return {@code true} when the liveness path took over (it completes the call); {@code false} for ARC's
     *         upstream SBI intents
     */
    public boolean tryStart(String fieldId, Modality modality,
                            BiometricsPigeon.Result<String> result) {
        if (livenessGate == null) {
            return false;
        }
        boolean exceptionPhoto = modality == Modality.EXCEPTION_PHOTO;
        if (modality != Modality.FACE && !exceptionPhoto) {
            return false;
        }
        if ((livenessLauncher != null && livenessLauncher.isBusy()) || sbiCaptureRunning) {
            // A second tap on Scan: answer it, and keep the first call's pending answer for its capture.
            result.success("BUSY");
            return true;
        }
        String gateField = exceptionPhoto ? FaceCaptureLivenessGate.exceptionPhotoField(fieldId) : fieldId;
        boolean required = !exceptionPhoto && livenessGate.isRequired(gateField);
        FaceDeviceSelection.Kind device = FaceDeviceSelection.kind(livenessConfig());
        FaceDeviceSelection.Route route = FaceDeviceSelection.route(device, required,
                device == FaceDeviceSelection.Kind.SBI && isSbiAppInstalled());
        if (exceptionPhoto) {
            if (route == FaceDeviceSelection.Route.UPSTREAM_SBI) {
                return false;
            }
            return startBuiltInCapture(result, fieldId, modality, gateField, true);
        }
        switch (route) {
            case UPSTREAM_SBI:
                livenessGate.clearOutcome(gateField);
                return false;
            case SBI_LIVENESS:
                startSbiLivenessCapture(result, fieldId, modality, gateField);
                return true;
            case BUILT_IN_PHOTO:
                startBuiltInPhoto(result, fieldId, modality, gateField);
                return true;
            default:
                return startBuiltInCapture(result, fieldId, modality, gateField, false);
        }
    }

    /**
     * Hands an SBI intent result to the waiting {@link ArcSbiBridge} call.
     *
     * @param requestCode one of the {@code SBI_*_REQUEST} codes
     * @param extras      the answer, or {@code null}
     * @return {@code true} when a bridge call was waiting for this request code
     */
    public boolean deliverSbiAnswer(int requestCode, Bundle extras) {
        SbiIntentCall call = pendingSbiCall;
        if (call == null || call.requestCode != requestCode) {
            return false;
        }
        pendingSbiCall = null;
        call.extras = extras;
        call.answered.countDown();
        return true;
    }

    /**
     * Returns the packet evidence of a vendor capture stored without a liveness check: never recorded as a pass.
     *
     * @param transactionId the capture's transaction ID
     * @param deviceLevel   the device's certification level
     * @return the LIVENESS_* evidence
     */
    public static Map<String, String> captureOnlyEvidence(String transactionId, String deviceLevel) {
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_NOT_CHECKED);
        evidence.put(PacketEvidenceKeys.METHOD, LivenessMethod.CAPTURE_ONLY.name());
        evidence.put(PacketEvidenceKeys.SESSION, transactionId);
        evidence.put(PacketEvidenceKeys.DEVICE_LEVEL, deviceLevel);
        return evidence;
    }

    private ConfigSource livenessConfig() {
        if (livenessConfig == null) {
            livenessConfig = new GlobalParamConfigSource(globalParamRepository);
        }
        return livenessConfig;
    }

    /** Tells whether an app answers ARC's SBI discovery intent. */
    private boolean isSbiAppInstalled() {
        try {
            List<?> apps = activity.get().getPackageManager().queryIntentActivities(
                    new Intent(RegistrationConstants.DISCOVERY_INTENT_ACTION), 0);
            return apps != null && !apps.isEmpty();
        } catch (RuntimeException e) {
            Log.w(TAG, "SBI apps could not be listed", e);
            return false;
        }
    }

    /**
     * Opens the liveness capture screen with the built-in camera: a FACE capture that requires liveness, or the
     * exception photo (a signed photo, with liveness only if the {@code exception_photo} policy asks for it).
     *
     * @return {@code true}: the capture screen path completes the Flutter call
     */
    private boolean startBuiltInCapture(BiometricsPigeon.Result<String> result,
                                        String fieldId, Modality modality, String gateField,
                                        boolean exceptionPhoto) {
        host.begin(result);
        String subject = livenessSubject(fieldId);
        if (subject == null) {
            host.complete(CaptureOutcome.Status.FAILED.name());
            return true;
        }
        CaptureOutcome blocked = livenessGate.admit(subject, gateField);
        if (blocked != null) {
            // Attempts used up: a supervisor may still accept the face when the policy allows an override.
            if (exceptionPhoto || !offerSupervisorOverride(subject, fieldId, gateField, modality)) {
                host.complete(blocked.status().name());
            }
            return true;
        }
        boolean started = livenessLauncher.launch(livenessGate, subject, gateField, exceptionPhoto,
                captureResult -> onLivenessCapture(subject, fieldId, gateField, modality, captureResult));
        if (!started) {
            host.complete("BUSY");
        }
        return true;
    }

    /**
     * Liveness does not run for this face ({@code mosip.registration.liveness.enable=N} or policy {@code OFF})
     * and the built-in camera is the face device: the capture screen takes a signed photo without a liveness
     * check. No liveness evidence is attached and no attempt is counted.
     */
    private void startBuiltInPhoto(BiometricsPigeon.Result<String> result,
                                   String fieldId, Modality modality, String gateField) {
        livenessGate.clearOutcome(gateField);
        host.begin(result);
        String subject = livenessSubject(fieldId);
        // No attempt is counted for this photo, so the field name may stand in for a missing subject.
        boolean started = livenessLauncher.launch(FaceCaptureLivenessGate.workflowFor(gateField),
                subject == null ? fieldId : subject, true, 0,
                captureResult -> onPhotoCapture(fieldId, gateField, modality, captureResult));
        if (!started) {
            host.complete("BUSY");
        }
    }

    /** Stores the photo of {@link #startBuiltInPhoto}; the field keeps no liveness outcome. */
    private void onPhotoCapture(String captureFieldId, String gateField, Modality modality,
                                LivenessCaptureResult captureResult) {
        livenessGate.clearOutcome(gateField);
        if (!captureResult.isPass() || captureResult.rCaptureResponse() == null) {
            host.complete(captureResult.decision() == Decision.CANCELLED ? CaptureOutcome.Status.CANCELLED.name()
                    : CaptureOutcome.Status.FAILED.name());
            return;
        }
        storeLivenessCapture(captureFieldId, gateField, modality, captureResult);
    }

    /**
     * The vendor SBI face app is the face device ({@code mosip.registration.liveness.android.device=SBI}) and this
     * face must pass liveness. The app is found and used through {@link SbiIntentDevice} over ARC's SBI intents,
     * on a worker thread (the intents answer on the main thread). SBI 0.9.5 intents give the client no preview
     * frames, so {@code mosip.registration.liveness.no_stream_policy} decides, as in the engine for any device
     * without a stream: {@code BLOCK} (the default) refuses the capture with "This face camera does not support
     * live verification"; {@code CAPTURE_ONLY} stores the app's signed capture through ARC's checks with
     * {@code LIVENESS_METHOD=CAPTURE_ONLY}, so reviewers see that no liveness check ran.
     */
    private void startSbiLivenessCapture(BiometricsPigeon.Result<String> result,
                                         String fieldId, Modality modality, String gateField) {
        host.begin(result);
        String subject = livenessSubject(fieldId);
        if (subject == null) {
            host.complete(CaptureOutcome.Status.FAILED.name());
            return;
        }
        CaptureOutcome blocked = livenessGate.admit(subject, gateField);
        if (blocked != null) {
            host.complete(blocked.status().name());
            return;
        }
        livenessGate.clearOutcome(gateField);
        Workflow workflow = FaceCaptureLivenessGate.workflowFor(gateField);
        LivenessPolicy policy = new PolicyResolver(livenessConfig()).resolve(workflow).policy();
        ArcSbiBridge bridge = new ArcSbiBridge(fieldId, modality, host.exceptionAttributes());
        sbiCaptureRunning = true;
        sbiWorker().execute(() -> {
            SbiCaptureAttempt attempt = runSbiCapture(bridge, workflow, policy);
            activity.get().runOnUiThread(() -> {
                sbiCaptureRunning = false;
                finishSbiCapture(fieldId, modality, attempt);
            });
        });
    }

    private synchronized ExecutorService sbiWorker() {
        if (sbiWorker == null) {
            sbiWorker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "liveness-sbi");
                t.setDaemon(true);
                return t;
            });
        }
        return sbiWorker;
    }

    /** Worker thread: discovery, then the no-stream policy, then the app's capture. */
    private SbiCaptureAttempt runSbiCapture(ArcSbiBridge bridge, Workflow workflow, LivenessPolicy policy) {
        try {
            SbiIntentDevice device = SbiIntentDevice.discover(bridge,
                    workflow.isAuth() ? DevicePurpose.AUTH : DevicePurpose.REGISTRATION);
            bridge.useApp(device.callbackId());
            if (!policy.allowedCertifications().contains(device.describe().certification())) {
                Log.w(TAG, "SBI face device " + device.describe().serialNumber() + " is "
                        + device.describe().certification() + ", not in mds.allowed_certifications");
                return SbiCaptureAttempt.failed(DeviceErrorCode.NOT_ALLOWED);
            }
            if (policy.noStreamPolicy() != NoStreamPolicy.CAPTURE_ONLY) {
                Log.w(TAG, "SBI face device " + device.describe().serialNumber()
                        + " cannot stream; no_stream_policy=" + policy.noStreamPolicy());
                return SbiCaptureAttempt.failed(DeviceErrorCode.NO_STREAM);
            }
            SignedFaceCapture capture = device.capture(io.mosip.liveness.core.device.CaptureRequest.builder()
                    .transactionId(UUID.randomUUID().toString()).build());
            return SbiCaptureAttempt.captured(device.lastResponseJson(),
                    captureOnlyEvidence(capture.transactionId(), device.describe().certification().code()));
        } catch (DeviceException e) {
            Log.w(TAG, "SBI face capture failed: " + e.getErrorCode());
            return SbiCaptureAttempt.failed(e.getErrorCode());
        } catch (RuntimeException e) {
            Log.e(TAG, "SBI face capture failed", e);
            return SbiCaptureAttempt.failed(DeviceErrorCode.CAPTURE_FAILED);
        }
    }

    /** Main thread: stores the vendor app's capture, or reports why there is none. */
    private void finishSbiCapture(String captureFieldId, Modality modality, SbiCaptureAttempt attempt) {
        if (attempt.response == null) {
            auditManagerService.audit(AuditEvent.R_CAPTURE_FAILED, Components.REGISTRATION, attempt.error.name());
            showLivenessMessage(LivenessUiStateMapper.deviceMessageKey(attempt.error));
            host.complete(CaptureOutcome.Status.FAILED.name());
            return;
        }
        if (!host.store(captureFieldId, modality, attempt.response, attempt.evidence)) {
            host.complete(CaptureOutcome.Status.FAILED.name());
        }
    }

    private void showLivenessMessage(String key) {
        try {
            String language = livenessLauncher != null ? livenessLauncher.language() : null;
            String text = new MessageResolver(activity.get().getResources(), livenessConfig(), language)
                    .text(key, Collections.emptyList());
            Toast.makeText(activity.get().getApplicationContext(), text, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Liveness message " + key + " could not be shown", e);
        }
    }

    /**
     * The registration ID for a resident, the signed-in operator for onboarding: the key of the failed-attempt
     * counters. {@code null} when it cannot be read; the caller then refuses the capture, because a shared stand-in
     * key would mix the counters of different residents.
     */
    private String livenessSubject(String fieldId) {
        try {
            String subject = fieldId.equals(OPERATOR_BIOMETRICS) ? signedInUserId()
                    : registrationService.getRegistrationDto().getRId();
            return subject == null || subject.trim().isEmpty() ? null : subject;
        } catch (Exception e) {
            Log.e(TAG, "No subject for the face liveness counters: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** The signed-in operator's user ID, or an empty string. */
    private String signedInUserId() {
        Activity a = activity.get();
        String user = a.getSharedPreferences(a.getString(io.mosip.registration.clientmanager.R.string.app_name),
                android.content.Context.MODE_PRIVATE).getString(SessionManager.USER_ID, "");
        return user == null ? "" : user.trim();
    }

    /**
     * Opens the capture screen only to offer the supervisor override again (the resident's attempts were used up
     * on an earlier screen).
     *
     * @return {@code true} when the screen opened (its result completes the Flutter call)
     */
    private boolean offerSupervisorOverride(String subject, String captureFieldId, String gateField,
                                            Modality modality) {
        if (livenessOverride == null || livenessGate.overrideEvidence(subject, gateField) == null) {
            return false;
        }
        return livenessLauncher.launch(LivenessCaptureRequest.builder(
                        FaceCaptureLivenessGate.workflowFor(gateField), subject).overrideOffer(true),
                captureResult -> onLivenessCapture(subject, captureFieldId, gateField, modality, captureResult));
    }

    /**
     * Handles the liveness capture screen's result: only a pass is stored, through the same SBI response checks as
     * an external device (signature, DEVICE trust, timestamp, transaction ID), with the liveness evidence attached.
     * A request for a supervisor override starts {@link SupervisorOverrideFlow}.
     */
    private void onLivenessCapture(String subject, String captureFieldId, String gateField, Modality modality,
                                   LivenessCaptureResult captureResult) {
        CaptureOutcome outcome = livenessGate.record(subject, gateField, captureResult);
        String supervisor = captureResult.overrideRequestedBy();
        if (supervisor != null && livenessOverride != null) {
            runSupervisorOverride(subject, captureFieldId, gateField, modality, supervisor);
            return;
        }
        if (!captureResult.isPass() && outcome.status() == CaptureOutcome.Status.CANCELLED) {
            // Closing the override offer leaves the field blocked.
            CaptureOutcome blocked = livenessGate.admit(subject, gateField);
            outcome = blocked != null ? blocked : outcome;
        }
        if (!captureResult.isPass() || captureResult.rCaptureResponse() == null) {
            host.complete(outcome.status().name());
            return;
        }
        storeLivenessCapture(captureFieldId, gateField, modality, captureResult);
    }

    /**
     * Supervisor face authentication, then the resident's photo flagged as an override. A refused supervisor
     * leaves the field blocked with the refusal's message.
     */
    private void runSupervisorOverride(String subject, String captureFieldId, String gateField, Modality modality,
                                       String supervisorId) {
        Map<String, String> evidence = livenessGate.overrideEvidence(subject, gateField);
        if (evidence == null) {
            host.complete(CaptureOutcome.Status.BLOCKED.name());
            return;
        }
        livenessOverride.run(FaceCaptureLivenessGate.workflowFor(gateField), subject, signedInUserId(), supervisorId,
                evidence, new SupervisorOverrideFlow.Callback() {
                    @Override
                    public void onPhoto(LivenessCaptureResult photo) {
                        CaptureOutcome outcome = livenessGate.recordOverride(subject, gateField, photo);
                        if (!photo.isSupervisorOverride() || photo.rCaptureResponse() == null) {
                            host.complete(outcome.status().name());
                            return;
                        }
                        storeLivenessCapture(captureFieldId, gateField, modality, photo);
                    }

                    @Override
                    public void onRefused(FaceAuthOutcome supervisorOutcome) {
                        host.complete(livenessGate.recordOverrideRefused(subject, gateField, supervisorOutcome)
                                .status().name());
                    }
                });
    }

    /** Stores a passing capture (or an override photo) through ARC's SBI response checks. */
    private void storeLivenessCapture(String captureFieldId, String gateField, Modality modality,
                                      LivenessCaptureResult captureResult) {
        try {
            if (captureFieldId.equals(OPERATOR_BIOMETRICS)) {
                userOnboardService.setCaptureTransactionId(captureResult.transactionId());
            } else {
                registrationService.getRegistrationDto().setCaptureTransactionId(captureResult.transactionId());
            }
        } catch (Exception e) {
            Log.e(TAG, "No registration for the face capture", e);
            host.complete(CaptureOutcome.Status.FAILED.name());
            return;
        }
        // A photo taken without a liveness check (exception photo, policy OFF) carries no liveness evidence; an
        // override photo carries the failed transaction's evidence with LIVENESS_DECISION=OVERRIDE.
        Map<String, String> evidence = captureResult.isLivenessChecked() ? captureResult.evidence() : null;
        if (!host.store(captureFieldId, modality, captureResult.rCaptureResponse(), evidence)) {
            if (captureResult.isLivenessChecked()) {
                livenessGate.recordRejected(gateField);
            } else {
                livenessGate.clearOutcome(gateField);
            }
            host.complete(CaptureOutcome.Status.FAILED.name());
        }
    }

    /** Result of {@link #runSbiCapture}: the app's rCapture response, or the device error. */
    private static final class SbiCaptureAttempt {
        final String response;
        final Map<String, String> evidence;
        final DeviceErrorCode error;

        private SbiCaptureAttempt(String response, Map<String, String> evidence, DeviceErrorCode error) {
            this.response = response;
            this.evidence = evidence;
            this.error = error;
        }

        static SbiCaptureAttempt captured(String response, Map<String, String> evidence) {
            return new SbiCaptureAttempt(response, evidence, null);
        }

        static SbiCaptureAttempt failed(DeviceErrorCode error) {
            return new SbiCaptureAttempt(null, null, error);
        }
    }

    /** One SBI intent the worker thread waits for; answered by {@link #deliverSbiAnswer}. */
    private static final class SbiIntentCall {
        final int requestCode;
        final CountDownLatch answered = new CountDownLatch(1);
        volatile Bundle extras;

        SbiIntentCall(int requestCode) {
            this.requestCode = requestCode;
        }
    }

    /**
     * {@link SbiIntentDevice.Bridge} over ARC's SBI intents: discovery ({@code io.sbi.device}), device info (ARC
     * checks the signed device info) and {@code rCapture} with ARC's own capture request and transaction ID.
     * Runs on the worker thread; each intent is started on the main thread and awaited. A device app that is
     * closed without an answer (result not OK) ends the wait at the timeout.
     */
    private final class ArcSbiBridge implements SbiIntentDevice.Bridge {
        private final String captureFieldId;
        private final Modality modality;
        private final List<String> exceptions;
        private volatile String app = RegistrationConstants.DISCOVERY_INTENT_ACTION;

        ArcSbiBridge(String captureFieldId, Modality modality, List<String> exceptions) {
            this.captureFieldId = captureFieldId;
            this.modality = modality;
            this.exceptions = exceptions;
        }

        void useApp(String callbackId) {
            if (callbackId != null && !callbackId.isEmpty()) {
                app = callbackId;
            }
        }

        @Override
        public String discover() throws DeviceException {
            Intent intent = new Intent();
            intent.setAction(RegistrationConstants.DISCOVERY_INTENT_ACTION);
            try {
                DiscoverRequest discoverRequest = new DiscoverRequest();
                discoverRequest.setType(SingleType.FACE.value());
                intent.putExtra(RegistrationConstants.SBI_INTENT_REQUEST_KEY,
                        objectMapper.writeValueAsBytes(discoverRequest));
            } catch (Exception e) {
                throw new DeviceException(DeviceErrorCode.NOT_FOUND, "discover request could not be written", e);
            }
            Bundle answer = call(intent, SBI_DISCOVER_REQUEST, SBI_INTENT_TIMEOUT_MS, DeviceErrorCode.NOT_FOUND);
            byte[] bytes = answer.getByteArray(RegistrationConstants.SBI_INTENT_RESPONSE_KEY);
            if (bytes == null) {
                throw new DeviceException(DeviceErrorCode.NOT_FOUND, "SBI app returned no discover response");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public String capture(io.mosip.liveness.core.device.CaptureRequest request) throws DeviceException {
            Intent info = new Intent();
            info.setAction(app + RegistrationConstants.D_INFO_INTENT_ACTION);
            Bundle infoAnswer = call(info, SBI_INFO_REQUEST, SBI_INTENT_TIMEOUT_MS, DeviceErrorCode.NOT_READY);
            Intent intent = new Intent();
            try {
                String[] device = biometricsService.handleDeviceInfoResponse(modality,
                        infoAnswer.getByteArray(RegistrationConstants.SBI_INTENT_RESPONSE_KEY));
                CaptureRequest captureRequest = biometricsService.getRCaptureRequest(modality, device[1], exceptions);
                if (captureFieldId.equals(OPERATOR_BIOMETRICS)) {
                    userOnboardService.setCaptureTransactionId(captureRequest.getTransactionId());
                } else {
                    registrationService.getRegistrationDto().setCaptureTransactionId(
                            captureRequest.getTransactionId());
                }
                auditManagerService.auditWithArguments(AuditEvent.REG_BIO_SCAN, Components.REGISTRATION.getId(),
                        Components.REGISTRATION.getName(), String.valueOf(modality));
                intent.setAction(device[0] + RegistrationConstants.R_CAPTURE_INTENT_ACTION);
                intent.putExtra("input", objectMapper.writeValueAsBytes(captureRequest));
            } catch (Exception e) {
                auditManagerService.audit(AuditEvent.DEVICE_INFO_PARSE_FAILED, Components.REGISTRATION,
                        e.getMessage());
                throw new DeviceException(DeviceErrorCode.NOT_READY, "SBI device info refused", e);
            }
            long timeout = Math.max(2 * SBI_INTENT_TIMEOUT_MS, request.timeoutMs() + SBI_INTENT_TIMEOUT_MS);
            Bundle answer = call(intent, SBI_CAPTURE_REQUEST, timeout, DeviceErrorCode.CAPTURE_TIMEOUT);
            try {
                Uri uri = answer.getParcelable(RegistrationConstants.SBI_INTENT_RESPONSE_KEY);
                try (InputStream in = activity.get().getContentResolver().openInputStream(uri)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                        out.write(buffer, 0, n);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                auditManagerService.audit(AuditEvent.R_CAPTURE_PARSE_FAILED, Components.REGISTRATION,
                        e.getMessage());
                throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "rCapture response unreadable", e);
            }
        }

        private Bundle call(Intent intent, int requestCode, long timeoutMs, DeviceErrorCode noAnswer)
                throws DeviceException {
            SbiIntentCall call = new SbiIntentCall(requestCode);
            pendingSbiCall = call;
            Activity a = activity.get();
            a.runOnUiThread(() -> {
                try {
                    a.startActivityForResult(intent, requestCode);
                } catch (RuntimeException e) {
                    Log.e(TAG, "SBI intent " + intent.getAction() + " could not be started", e);
                    deliverSbiAnswer(requestCode, null);
                }
            });
            try {
                if (!call.answered.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new DeviceException(noAnswer, "SBI app did not answer " + intent.getAction());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DeviceException(noAnswer, "interrupted while waiting for the SBI app");
            } finally {
                if (pendingSbiCall == call) {
                    pendingSbiCall = null;
                }
            }
            if (call.extras == null) {
                throw new DeviceException(noAnswer, "SBI app gave no answer to " + intent.getAction());
            }
            return call.extras;
        }
    }
}
