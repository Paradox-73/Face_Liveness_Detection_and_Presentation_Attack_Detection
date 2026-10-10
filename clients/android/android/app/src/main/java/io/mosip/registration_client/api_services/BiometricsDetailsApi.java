/*
 * Copyright (c) Modular Open Source Identity Platform
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 *
*/

package io.mosip.registration_client.api_services;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.gemalto.jp2.JP2Decoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.biometrics.util.face.FaceBDIR;
import io.mosip.biometrics.util.finger.FingerBDIR;
import io.mosip.biometrics.util.iris.IrisBDIR;
import io.mosip.registration.clientmanager.config.SessionManager;
import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.constant.Modality;
import io.mosip.registration.clientmanager.constant.RegistrationConstants;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.clientmanager.dto.registration.RegistrationDto;
import io.mosip.registration.clientmanager.dto.sbi.CaptureRequest;
import io.mosip.registration.clientmanager.dto.sbi.DiscoverRequest;
import io.mosip.registration.clientmanager.exception.BiometricsServiceException;
import io.mosip.registration.clientmanager.exception.ClientCheckedException;
import io.mosip.registration.clientmanager.repository.GlobalParamRepository;
import io.mosip.registration.clientmanager.service.Biometrics095Service;
import io.mosip.registration.clientmanager.service.UserOnboardService;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.clientmanager.spi.RegistrationService;
import io.mosip.registration.clientmanager.util.UserInterfaceHelperService;
import io.mosip.registration.keymanager.util.CryptoUtil;
import io.mosip.registration.packetmanager.cbeffutil.jaxbclasses.SingleType;
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
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.config.GlobalParamConfigSource;
import io.mosip.registration.livenessmanager.device.FaceDeviceSelection;
import io.mosip.registration.livenessmanager.device.SbiIntentDevice;
import io.mosip.registration.livenessmanager.ui.MessageResolver;
import io.mosip.registration_client.MainActivity;
import io.mosip.registration_client.liveness.CaptureOutcome;
import io.mosip.registration_client.liveness.FaceCaptureLivenessGate;
import io.mosip.registration_client.liveness.LivenessCaptureLauncher;
import io.mosip.registration_client.liveness.SupervisorOverrideFlow;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.auth.FaceAuthOutcome;
import io.mosip.registration_client.model.BiometricsPigeon;

@Singleton
public class BiometricsDetailsApi implements BiometricsPigeon.BiometricsApi {
    private Activity activity;
    private final AuditManagerService auditManagerService;
    private final ObjectMapper objectMapper;
    private final Biometrics095Service biometricsService;
    private final RegistrationService registrationService;
    private final GlobalParamRepository globalParamRepository;
    private final UserOnboardService userOnboardService;

    private Modality currentModality;
    private static final String TAG = "BiometricsDetailApi";
    private String callbackId;
    private String fieldId;
    private String purpose;
    private int capturedAttempts;
    private int currentAttempt;
    private int allowedAttempts;
    private int qualityThreshold;
    List<BiometricsDto> biometricsDtoList;

    private final List<Bitmap> listBitmaps1 = new ArrayList<>();
    private byte[] byteArrayTester;
    private List<byte[]> listByteArrayTester1 = new ArrayList<>();
    BiometricsPigeon.Result<String> biometricResult;

    public List<String> OPERATOR_EXCEPTIONS=new ArrayList<>();

    private static final String OPERATOR_BIOMETRICS = "operatorBiometrics";
    private static final String LEFT_INDEX_FINGER = "Left IndexFinger";
    private static final String LEFT_MIDDLE_FINGER = "Left MiddleFinger";
    private static final String LEFT_RING_FINGER = "Left RingFinger";
    private static final String LEFT_LITTLE_FINGER = "Left LittleFinger";
    private static final String RIGHT_INDEX_FINGER = "Right IndexFinger";
    private static final String RIGHT_MIDDLE_FINGER = "Right MiddleFinger";
    private static final String RIGHT_RING_FINGER = "Right RingFinger";
    private static final String RIGHT_LITTLE_FINGER = "Right LittleFinger";
    private static final String LEFT_THUMB = "Left Thumb";
    private static final String RIGHT_THUMB = "Right Thumb";
    private static final String RIGHT = "Right";
    private static final String LEFT = "Left";

    private final List<BiometricsPigeon.DeviceInfo> deviceDetailsList = new ArrayList<>();
    private BiometricsPigeon.Result<List<BiometricsPigeon.DeviceInfo>> deviceListResult;

    private Modality currentDeviceListModality;

    // Face liveness: FACE captures of residents and onboarding operators go through the liveness capture screen
    private FaceCaptureLivenessGate livenessGate;
    private LivenessCaptureLauncher livenessLauncher;
    private SupervisorOverrideFlow livenessOverride;
    // Face device (mosip.registration.liveness.android.device): the built-in camera or a vendor SBI face app
    private ConfigSource livenessConfig;
    private ExecutorService sbiWorker;
    private volatile SbiIntentCall pendingSbiCall;
    private static final long SBI_INTENT_TIMEOUT_MS = 30_000L;
    private static final int SBI_DISCOVER_REQUEST = 1;
    private static final int SBI_INFO_REQUEST = 2;
    private static final int SBI_CAPTURE_REQUEST = 3;






    @Inject
    public BiometricsDetailsApi(AuditManagerService auditManagerService, ObjectMapper objectMapper,
            Biometrics095Service biometrics095Service, RegistrationService registrationService,
            GlobalParamRepository globalParamRepository, UserOnboardService userOnboardService) {
        this.auditManagerService = auditManagerService;
        this.objectMapper = objectMapper;
        this.biometricsService = biometrics095Service;
        this.registrationService = registrationService;
        this.globalParamRepository = globalParamRepository;
        this.userOnboardService = userOnboardService;
    }

    public void setCallbackActivity(MainActivity mainActivity) {
        this.activity = mainActivity;
    }

    /**
     * Sets the activity that starts the capture screen and the SBI intents (tests use a plain activity).
     *
     * @param activity the activity
     */
    void setActivity(Activity activity) {
        this.activity = activity;
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
    void setLivenessConfig(ConfigSource config) {
        this.livenessConfig = config;
    }

    private ConfigSource livenessConfig() {
        if (livenessConfig == null) {
            livenessConfig = new GlobalParamConfigSource(globalParamRepository);
        }
        return livenessConfig;
    }

    /**
     * Enables the supervisor override of a resident's failed face capture ({@code on_max_failure=SUPERVISOR_OVERRIDE}).
     *
     * @param override the override flow (supervisor face authentication, then the resident's photo)
     */
    public void setSupervisorOverrideFlow(SupervisorOverrideFlow override) {
        this.livenessOverride = override;
    }

    @Override
    public void invokeDiscoverSbi(@NonNull String fieldId, @NonNull String modality,
            @NonNull BiometricsPigeon.Result<String> result) {
        auditManagerService.audit(AuditEvent.BIOMETRIC_CAPTURE, Components.REGISTRATION);
        currentModality = getModality(modality);
        this.fieldId = fieldId;
        if (startLivenessCapture(result)) {
            return;
        }
        discoverSBI();
        biometricResult = result;
    }

    /**
     * Takes FACE captures and the exception photo through the face device of
     * {@code mosip.registration.liveness.android.device} ({@link FaceDeviceSelection}):
     * <ul>
     *   <li>built-in camera ({@code BUILTIN}, the default; also for an unknown value): the liveness capture screen
     *       when liveness is enforced, else the same screen takes a signed photo without a liveness check, so a
     *       tablet without a face SBI app still has a face device with {@code mosip.registration.liveness.enable=N};
     *   </li>
     *   <li>vendor SBI face app ({@code SBI}): {@link SbiIntentDevice} over ARC's SBI intents when liveness is
     *       enforced, else ARC's upstream SBI capture. Without an installed SBI app the built-in camera is used.</li>
     * </ul>
     * The exception photo uses the built-in camera's screen (which applies the {@code exception_photo} policy)
     * unless the vendor app is the face device.
     *
     * @return {@code true} when the liveness path took over (it completes the Flutter call); {@code false} for
     *         ARC's upstream SBI intents
     */
    private boolean startLivenessCapture(BiometricsPigeon.Result<String> result) {
        if (livenessGate == null) {
            return false;
        }
        boolean exceptionPhoto = currentModality == Modality.EXCEPTION_PHOTO;
        if (currentModality != Modality.FACE && !exceptionPhoto) {
            return false;
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
            return startBuiltInCapture(result, gateField, true);
        }
        switch (route) {
            case UPSTREAM_SBI:
                livenessGate.clearOutcome(gateField);
                return false;
            case SBI_LIVENESS:
                startSbiLivenessCapture(result, gateField);
                return true;
            case BUILT_IN_PHOTO:
                startBuiltInPhoto(result, gateField);
                return true;
            default:
                return startBuiltInCapture(result, gateField, false);
        }
    }

    /** Tells whether an app answers ARC's SBI discovery intent. */
    private boolean isSbiAppInstalled() {
        try {
            List<?> apps = activity.getPackageManager().queryIntentActivities(
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
    private boolean startBuiltInCapture(BiometricsPigeon.Result<String> result, String gateField,
                                        boolean exceptionPhoto) {
        biometricResult = result;
        String subject = livenessSubject();
        CaptureOutcome blocked = livenessGate.admit(subject, gateField);
        String captureFieldId = fieldId;
        Modality captureModality = currentModality;
        if (blocked != null) {
            // Attempts used up: a supervisor may still accept the face when the policy allows an override.
            if (exceptionPhoto || !offerSupervisorOverride(subject, captureFieldId, gateField, captureModality)) {
                completeCapture(blocked.status().name());
            }
            return true;
        }
        boolean started = livenessLauncher.launch(livenessGate, subject, gateField, exceptionPhoto,
                captureResult -> onLivenessCapture(subject, captureFieldId, gateField,
                        captureModality, captureResult));
        if (!started) {
            completeCapture("BUSY");
        }
        return true;
    }

    /**
     * Liveness does not run for this face ({@code mosip.registration.liveness.enable=N} or policy {@code OFF})
     * and the built-in camera is the face device: the capture screen takes a signed photo without a liveness
     * check. No liveness evidence is attached and no attempt is counted.
     */
    private void startBuiltInPhoto(BiometricsPigeon.Result<String> result, String gateField) {
        livenessGate.clearOutcome(gateField);
        biometricResult = result;
        String captureFieldId = fieldId;
        Modality captureModality = currentModality;
        boolean started = livenessLauncher.launch(FaceCaptureLivenessGate.workflowFor(gateField), livenessSubject(),
                true, 0, captureResult -> onPhotoCapture(captureFieldId, gateField, captureModality, captureResult));
        if (!started) {
            completeCapture("BUSY");
        }
    }

    /** Stores the photo of {@link #startBuiltInPhoto}; the field keeps no liveness outcome. */
    private void onPhotoCapture(String captureFieldId, String gateField, Modality modality,
                                LivenessCaptureResult captureResult) {
        livenessGate.clearOutcome(gateField);
        if (!captureResult.isPass() || captureResult.rCaptureResponse() == null) {
            completeCapture(captureResult.decision() == Decision.CANCELLED ? CaptureOutcome.Status.CANCELLED.name()
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
    private void startSbiLivenessCapture(BiometricsPigeon.Result<String> result, String gateField) {
        biometricResult = result;
        String subject = livenessSubject();
        CaptureOutcome blocked = livenessGate.admit(subject, gateField);
        if (blocked != null) {
            completeCapture(blocked.status().name());
            return;
        }
        livenessGate.clearOutcome(gateField);
        String captureFieldId = fieldId;
        Modality captureModality = currentModality;
        Workflow workflow = FaceCaptureLivenessGate.workflowFor(gateField);
        LivenessPolicy policy = new PolicyResolver(livenessConfig()).resolve(workflow).policy();
        ArcSbiBridge bridge = new ArcSbiBridge(captureFieldId, captureModality, getExceptionAttributes());
        sbiWorker().execute(() -> {
            SbiCaptureAttempt attempt = runSbiCapture(bridge, workflow, policy);
            activity.runOnUiThread(() -> finishSbiCapture(captureFieldId, captureModality, attempt));
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
            if (policy.noStreamPolicy() != NoStreamPolicy.CAPTURE_ONLY) {
                Log.w(TAG, "SBI face device " + device.describe().serialNumber()
                        + " cannot stream; no_stream_policy=" + policy.noStreamPolicy());
                return SbiCaptureAttempt.failed(DeviceErrorCode.NO_STREAM);
            }
            SignedFaceCapture capture = device.capture(io.mosip.liveness.core.device.CaptureRequest.builder()
                    .transactionId(UUID.randomUUID().toString()).build());
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_PASS);
            evidence.put(PacketEvidenceKeys.METHOD, LivenessMethod.CAPTURE_ONLY.name());
            evidence.put(PacketEvidenceKeys.SESSION, capture.transactionId());
            evidence.put(PacketEvidenceKeys.DEVICE_LEVEL, device.describe().certification().code());
            return SbiCaptureAttempt.captured(device.lastResponseJson(), evidence);
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
            completeCapture(CaptureOutcome.Status.FAILED.name());
            return;
        }
        this.fieldId = captureFieldId;
        this.currentModality = modality;
        InputStream response = new ByteArrayInputStream(attempt.response.getBytes(StandardCharsets.UTF_8));
        if (!parseRCaptureResponse(response, attempt.evidence)) {
            completeCapture(CaptureOutcome.Status.FAILED.name());
        }
    }

    private void showLivenessMessage(String key) {
        try {
            String language = livenessLauncher != null ? livenessLauncher.language() : null;
            String text = new MessageResolver(activity.getResources(), livenessConfig(), language)
                    .text(key, Collections.emptyList());
            Toast.makeText(activity.getApplicationContext(), text, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Log.w(TAG, "Liveness message " + key + " could not be shown", e);
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
     * Hands an SBI intent result to the waiting {@link ArcSbiBridge} call.
     *
     * @return {@code true} when a bridge call was waiting for this request code
     */
    private boolean deliverSbiAnswer(int requestCode, Bundle extras) {
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
                try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
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
            activity.runOnUiThread(() -> {
                try {
                    activity.startActivityForResult(intent, requestCode);
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

    /** The registration ID for a resident, the signed-in operator for onboarding. */
    private String livenessSubject() {
        try {
            if (fieldId.equals(OPERATOR_BIOMETRICS)) {
                String user = signedInUserId();
                return user.isEmpty() ? OPERATOR_BIOMETRICS : user;
            }
            return registrationService.getRegistrationDto().getRId();
        } catch (Exception e) {
            return fieldId;
        }
    }

    /** The signed-in operator's user ID, or an empty string. */
    private String signedInUserId() {
        String user = activity.getSharedPreferences(
                activity.getString(io.mosip.registration.clientmanager.R.string.app_name),
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
            completeCapture(outcome.status().name());
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
            completeCapture(CaptureOutcome.Status.BLOCKED.name());
            return;
        }
        livenessOverride.run(FaceCaptureLivenessGate.workflowFor(gateField), subject, signedInUserId(), supervisorId,
                evidence, new SupervisorOverrideFlow.Callback() {
                    @Override
                    public void onPhoto(LivenessCaptureResult photo) {
                        CaptureOutcome outcome = livenessGate.recordOverride(subject, gateField, photo);
                        if (!photo.isSupervisorOverride() || photo.rCaptureResponse() == null) {
                            completeCapture(outcome.status().name());
                            return;
                        }
                        storeLivenessCapture(captureFieldId, gateField, modality, photo);
                    }

                    @Override
                    public void onRefused(FaceAuthOutcome supervisorOutcome) {
                        completeCapture(livenessGate.recordOverrideRefused(subject, gateField, supervisorOutcome)
                                .status().name());
                    }
                });
    }

    /** Stores a passing capture (or an override photo) through ARC's SBI response checks. */
    private void storeLivenessCapture(String captureFieldId, String gateField, Modality modality,
                                      LivenessCaptureResult captureResult) {
        this.fieldId = captureFieldId;
        this.currentModality = modality;
        try {
            if (captureFieldId.equals(OPERATOR_BIOMETRICS)) {
                userOnboardService.setCaptureTransactionId(captureResult.transactionId());
            } else {
                registrationService.getRegistrationDto().setCaptureTransactionId(captureResult.transactionId());
            }
        } catch (Exception e) {
            Log.e(TAG, "No registration for the face capture", e);
            completeCapture(CaptureOutcome.Status.FAILED.name());
            return;
        }
        InputStream response = new ByteArrayInputStream(
                captureResult.rCaptureResponse().getBytes(StandardCharsets.UTF_8));
        // A photo taken without a liveness check (exception photo, policy OFF) carries no liveness evidence; an
        // override photo carries the failed transaction's evidence with LIVENESS_DECISION=OVERRIDE.
        Map<String, String> evidence = captureResult.isLivenessChecked() ? captureResult.evidence() : null;
        if (!parseRCaptureResponse(response, evidence)) {
            if (captureResult.isLivenessChecked()) {
                livenessGate.recordRejected(gateField);
            } else {
                livenessGate.clearOutcome(gateField);
            }
            completeCapture(CaptureOutcome.Status.FAILED.name());
        }
    }

    /** Completes the pending Flutter capture call once; later calls are ignored. */
    private void completeCapture(String status) {
        BiometricsPigeon.Result<String> pending = biometricResult;
        biometricResult = null;
        if (pending != null) {
            pending.success(status);
        }
    }

    @Override
    public void getBestBiometrics(@NonNull String fieldId, @NonNull String modality,
            @NonNull BiometricsPigeon.Result<List<String>> result) {

        if (fieldId.equals(OPERATOR_BIOMETRICS)) {
            try {
                biometricsDtoList=new ArrayList<>();
                ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
                String json;
                List<String> jsonList = new ArrayList<>();

                for (BiometricsDto dto : userOnboardService.getOperatorBiometrics()) {
                    if (modality.equals("Face") && dto.getBioSubType()==null) {
                        biometricsDtoList.add(dto);
                    }
                    if(matchOperatorModality(dto.getBioSubType(),modality)){
                        biometricsDtoList.add(dto);
                    }
                }


                for (int i = 0; i < biometricsDtoList.size(); i++) {
                    json = ow.writeValueAsString(biometricsDtoList.get(i));
                    jsonList.add(json);
                }


                result.success(jsonList);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
        } else {
            try {
                RegistrationDto registrationDto = registrationService.getRegistrationDto();
                ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
                String json;
                List<String> jsonList = new ArrayList<>();
                biometricsDtoList = registrationDto.getBestBiometrics(fieldId, getModality(modality));
                for (int i = 0; i < biometricsDtoList.size(); i++) {
                    json = ow.writeValueAsString(biometricsDtoList.get(i));
                    jsonList.add(json);
                }
                result.success(jsonList);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
        }
    }

    @Override
    public void getBiometrics(@NonNull String fieldId, @NonNull String modality, @NonNull Long attempt,
            @NonNull BiometricsPigeon.Result<List<String>> result) {
        try {
            RegistrationDto registrationDto = registrationService.getRegistrationDto();
            ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
            String json;
            List<String> jsonList = new ArrayList<>();
            biometricsDtoList = registrationDto.getBiometrics(fieldId, getModality(modality), attempt.intValue() - 1);
            for (int i = 0; i < biometricsDtoList.size(); i++) {
                json = ow.writeValueAsString(biometricsDtoList.get(i));
                jsonList.add(json);
            }
            result.success(jsonList);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void extractImageValues(@NonNull String fieldId, @NonNull String modality,
            @NonNull BiometricsPigeon.Result<List<byte[]>> result) {
        List<Bitmap> listBitmaps = new ArrayList<>();
        List<byte[]> listByteArrayTester = new ArrayList<>();
        if (fieldId.equals(OPERATOR_BIOMETRICS)) {
            try {


                ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
                String json;
                List<String> jsonList = new ArrayList<>();
                List<BiometricsDto> biometricsDtoListtemp = new ArrayList<>();

                switch (getModality(modality)) {
                    case FACE:
                    case EXCEPTION_PHOTO: {
                        try {
                            BiometricsDto biometricsDtotemp = new BiometricsDto();

                            for (BiometricsDto dto : userOnboardService.getOperatorBiometrics()) {

                                if (modality.equals("Face") && dto.getBioSubType()==null) {
                                    biometricsDtotemp = dto;
                                    break;
                                }
                            }
                            Bitmap var5;
                            ByteArrayInputStream bais = new ByteArrayInputStream(
                                    CryptoUtil.base64decoder.decode(biometricsDtotemp.getBioValue()));
                            DataInputStream inputStream = new DataInputStream(bais);
                            FaceBDIR faceBDIR = new FaceBDIR(inputStream);
                            byte[] bytes = faceBDIR.getRepresentation().getRepresentationData().getImageData()
                                    .getImage();
                            var5 = (new JP2Decoder(bytes)).decode();
                            listBitmaps.add(var5);
                        } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                        }
                    }
                        break;
                    case FINGERPRINT_SLAB_LEFT:
                    case FINGERPRINT_SLAB_THUMBS:
                    case FINGERPRINT_SLAB_RIGHT: {
                        try {

                            for (BiometricsDto dto : userOnboardService.getOperatorBiometrics()) {

                                if (matchOperatorModality(dto.getBioSubType(),modality)) {
                                    biometricsDtoListtemp.add(dto);
                                }
                            }
                            Bitmap var5;
                            for (int i = 0; i < biometricsDtoListtemp.size(); i++) {
                                ByteArrayInputStream bais = new ByteArrayInputStream(
                                        CryptoUtil.base64decoder.decode(biometricsDtoListtemp.get(i).getBioValue()));
                                DataInputStream inputStream = new DataInputStream(bais);
                                FingerBDIR fingerBDIR = new FingerBDIR(inputStream);
                                byte[] bytes = fingerBDIR.getRepresentation().getRepresentationBody().getImageData()
                                        .getImage();
                                var5 = (new JP2Decoder(bytes)).decode();
                                listBitmaps.add(var5);
                            }
                            } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                        }
                    }
                        break;
                    case IRIS_DOUBLE: {
                        try {


                            for (BiometricsDto dto : userOnboardService.getOperatorBiometrics()) {
                                if (matchOperatorModality(dto.getBioSubType(),modality)) {
                                    biometricsDtoListtemp.add(dto);

                                }
                            }

                            Bitmap var5;
                             for (int i = 0; i < biometricsDtoListtemp.size(); i++) {
                                ByteArrayInputStream bais = new ByteArrayInputStream(
                                        CryptoUtil.base64decoder.decode(biometricsDtoListtemp.get(0).getBioValue()));
                                DataInputStream inputStream = new DataInputStream(bais);
                                IrisBDIR irisBDIR = new IrisBDIR(inputStream);
                                byte[] bytes = irisBDIR.getRepresentation().getRepresentationData().getImageData()
                                        .getImage();
                                var5 = (new JP2Decoder(bytes)).decode();
                                listBitmaps.add(var5);
                            }
                            } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                        }
                    }
                        break;
                }

                for (int i = 0; i < listBitmaps.size(); i++) {
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    listBitmaps.get(i).compress(Bitmap.CompressFormat.PNG, 100, stream);
                    byteArrayTester = stream.toByteArray();
                    listByteArrayTester.add(byteArrayTester);
                }

                listByteArrayTester1 = listByteArrayTester;
                result.success(listByteArrayTester1);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
        } else {
            try {
                RegistrationDto registrationDto = registrationService.getRegistrationDto();
                ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
                String json;
                List<String> jsonList = new ArrayList<>();

                switch (getModality(modality)) {
                    case FACE:
                    case EXCEPTION_PHOTO: {
                        try {
                            Bitmap var5;
                            ByteArrayInputStream bais = new ByteArrayInputStream(
                                    CryptoUtil.base64decoder.decode(registrationDto
                                            .getBestBiometrics(fieldId, getModality(modality)).get(0).getBioValue()));
                            DataInputStream inputStream = new DataInputStream(bais);
                            FaceBDIR faceBDIR = new FaceBDIR(inputStream);
                            byte[] bytes = faceBDIR.getRepresentation().getRepresentationData().getImageData()
                                    .getImage();
                            var5 = (new JP2Decoder(bytes)).decode();
                            listBitmaps.add(var5);
                        } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                        }
                    }
                        break;
                    case FINGERPRINT_SLAB_LEFT:
                    case FINGERPRINT_SLAB_THUMBS:
                    case FINGERPRINT_SLAB_RIGHT: {
                        try {
                            Bitmap var5;
                            for (int i = 0; i < registrationDto.getBestBiometrics(fieldId, getModality(modality))
                                    .size(); i++) {
                                ByteArrayInputStream bais = new ByteArrayInputStream(CryptoUtil.base64decoder
                                        .decode(registrationDto.getBestBiometrics(fieldId, getModality(modality)).get(i)
                                                .getBioValue()));
                                DataInputStream inputStream = new DataInputStream(bais);
                                FingerBDIR fingerBDIR = new FingerBDIR(inputStream);
                                byte[] bytes = fingerBDIR.getRepresentation().getRepresentationBody().getImageData()
                                        .getImage();
                                var5 = (new JP2Decoder(bytes)).decode();
                                listBitmaps.add(var5);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                        }
                    }
                        break;
                    case IRIS_DOUBLE: {
                        try {
                            Bitmap var5;

                            for (int i = 0; i < registrationDto.getBestBiometrics(fieldId, getModality(modality))
                                    .size(); i++) {
                                ByteArrayInputStream bais = new ByteArrayInputStream(CryptoUtil.base64decoder
                                        .decode(registrationDto.getBestBiometrics(fieldId, getModality(modality)).get(0)
                                                .getBioValue()));
                                DataInputStream inputStream = new DataInputStream(bais);
                                IrisBDIR irisBDIR = new IrisBDIR(inputStream);
                                byte[] bytes = irisBDIR.getRepresentation().getRepresentationData().getImageData()
                                        .getImage();
                                var5 = (new JP2Decoder(bytes)).decode();
                                listBitmaps.add(var5);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                        }
                    }
                        break;
                }

                for (int i = 0; i < listBitmaps.size(); i++) {
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    listBitmaps.get(i).compress(Bitmap.CompressFormat.PNG, 100, stream);
                    byteArrayTester = stream.toByteArray();
                    listByteArrayTester.add(byteArrayTester);
                }

                listByteArrayTester1 = listByteArrayTester;
                result.success(listByteArrayTester1);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
        }
    }

    @Override
    public void extractImageValuesByAttempt(@NonNull String fieldId, @NonNull String modality, @NonNull Long attempt,
            @NonNull BiometricsPigeon.Result<List<byte[]>> result) {
        List<Bitmap> listBitmaps = new ArrayList<>();
        List<byte[]> listByteArrayTester = new ArrayList<>();
        try {
            RegistrationDto registrationDto = registrationService.getRegistrationDto();

            ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
            String json;
            List<String> jsonList = new ArrayList<>();

            switch (getModality(modality)) {
                case FACE:
                case EXCEPTION_PHOTO: {
                    try {
                        Bitmap var5;
                        ByteArrayInputStream bais = new ByteArrayInputStream(CryptoUtil.base64decoder.decode(
                                registrationDto.getBiometrics(fieldId, getModality(modality), attempt.intValue() - 1)
                                        .get(0).getBioValue()));
                        DataInputStream inputStream = new DataInputStream(bais);
                        FaceBDIR faceBDIR = new FaceBDIR(inputStream);
                        byte[] bytes = faceBDIR.getRepresentation().getRepresentationData().getImageData().getImage();
                        var5 = (new JP2Decoder(bytes)).decode();
                        listBitmaps.add(var5);
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage());
                    }
                }
                    break;
                case FINGERPRINT_SLAB_LEFT:
                case FINGERPRINT_SLAB_THUMBS:
                case FINGERPRINT_SLAB_RIGHT: {
                    try {
                        Bitmap var5;
                        for (int i = 0; i < registrationDto
                                .getBiometrics(fieldId, getModality(modality), attempt.intValue() - 1).size(); i++) {
                            ByteArrayInputStream bais = new ByteArrayInputStream(
                                    CryptoUtil.base64decoder.decode(registrationDto
                                            .getBiometrics(fieldId, getModality(modality), attempt.intValue() - 1)
                                            .get(i).getBioValue()));
                            DataInputStream inputStream = new DataInputStream(bais);
                            FingerBDIR fingerBDIR = new FingerBDIR(inputStream);
                            byte[] bytes = fingerBDIR.getRepresentation().getRepresentationBody().getImageData()
                                    .getImage();
                            var5 = (new JP2Decoder(bytes)).decode();
                            listBitmaps.add(var5);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage());
                    }
                }
                    break;
                case IRIS_DOUBLE: {
                    try {
                        Bitmap var5;
                        for (int i = 0; i < registrationDto
                                .getBiometrics(fieldId, getModality(modality), attempt.intValue() - 1).size(); i++) {
                            ByteArrayInputStream bais = new ByteArrayInputStream(
                                    CryptoUtil.base64decoder.decode(registrationDto
                                            .getBiometrics(fieldId, getModality(modality), attempt.intValue() - 1)
                                            .get(0).getBioValue()));
                            DataInputStream inputStream = new DataInputStream(bais);
                            IrisBDIR irisBDIR = new IrisBDIR(inputStream);
                            byte[] bytes = irisBDIR.getRepresentation().getRepresentationData().getImageData()
                                    .getImage();
                            var5 = (new JP2Decoder(bytes)).decode();
                            listBitmaps.add(var5);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage());
                    }
                }
                    break;
            }
            for (int i = 0; i < listBitmaps.size(); i++) {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                listBitmaps.get(i).compress(Bitmap.CompressFormat.PNG, 100, stream);
                byteArrayTester = stream.toByteArray();
                listByteArrayTester.add(byteArrayTester);
            }

            listByteArrayTester1 = listByteArrayTester;
            result.success(listByteArrayTester1);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void incrementBioAttempt(@NonNull String fieldId, @NonNull String modality,
            @NonNull BiometricsPigeon.Result<Long> result) {
        try {
            RegistrationDto registrationDto = registrationService.getRegistrationDto();
            int i = registrationDto.incrementBioAttempt(fieldId, getModality(modality));
            result.success(Long.valueOf(i));
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void getBioAttempt(@NonNull String fieldId, @NonNull String modality,
            @NonNull BiometricsPigeon.Result<Long> result) {
        try {
            RegistrationDto registrationDto = registrationService.getRegistrationDto();
            int i = registrationDto.getBioAttempt(fieldId, getModality(modality));
            result.success(Long.valueOf(i));
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void startOperatorOnboarding(@NonNull BiometricsPigeon.Result<String> result) {
        auditManagerService.audit(AuditEvent.NAV_ON_BOARD_USER, Components.REGISTRATION);
        try {
            OPERATOR_EXCEPTIONS.clear();
            userOnboardService.getOperatorBiometrics().clear();
            userOnboardService.setIdaResponse(false);
            userOnboardService.setIsOnboardSuccess(false);
            userOnboardService.setCaptureTransactionId(null);
            result.success("Ok");
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void clearBiometricAndDocumentHashmap(@NonNull BiometricsPigeon.Result<String> result) {
        try {
            RegistrationDto registrationDto = registrationService.getRegistrationDto();
            registrationDto.clearBiometricsHashmap();
            registrationDto.clearDocumentsHashmap();
            result.success("Ok");
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void saveOperatorBiometrics(@NonNull BiometricsPigeon.Result<String> result) {
        try{
            userOnboardService.onboardOperator(userOnboardService.getOperatorBiometrics(), () -> {
                if(userOnboardService.getIsOnboardSuccess()) {
                    result.success("OK");
                } else {
                    result.success("FAILED");
                }
            });
        }catch (Exception e){
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void addBioException(@NonNull String fieldId, @NonNull String modality, @NonNull String attribute,
            @NonNull BiometricsPigeon.Result<String> result) {
        try {
            auditManagerService.audit(AuditEvent.REG_BIO_EXCEPTION_MARKING, Components.REGISTRATION);
            if(fieldId.equals(OPERATOR_BIOMETRICS)){


                OPERATOR_EXCEPTIONS.add(attribute);


//                String key = String.format(BIO_KEY, fieldId, modality.name());
//                ATTEMPTS.put(key, new AtomicInteger(0));
//                for(String attribute : modality.getAttributes()) {
//                    this.biometrics.keySet()
//                            .removeIf(k -> k.startsWith(String.format(BIO_KEY_PATTERN, fieldId, attribute)));
//                }
                result.success("ok");
            }else{
                RegistrationDto registrationDto = registrationService.getRegistrationDto();
                registrationDto.addBioException(fieldId, getModality(modality), attribute);
                result.success("ok");
            }

        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void removeBioException(@NonNull String fieldId, @NonNull String modality, @NonNull String attribute,
            @NonNull BiometricsPigeon.Result<String> result) {
        try {
            auditManagerService.audit(AuditEvent.REG_BIO_EXCEPTION_REMOVING, Components.REGISTRATION);
            if(fieldId.equals(OPERATOR_BIOMETRICS)){
                OPERATOR_EXCEPTIONS.remove(attribute);
             

                result.success("ok");
            }else{
                RegistrationDto registrationDto = registrationService.getRegistrationDto();
                registrationDto.removeBioException(fieldId, getModality(modality), attribute);
                result.success("ok");
            }

        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void getMapValue(@NonNull String key, @NonNull BiometricsPigeon.Result<String> result) {
        String response = globalParamRepository.getCachedStringGlobalParam(key);
        result.success(response == null ? "" : response);
    }

    @Override
    public void getAgeGroup(@NonNull BiometricsPigeon.Result<String> result) {
        String group = "";
        try {
            RegistrationDto registrationDto = registrationService.getRegistrationDto();
            Map<String, Object> age_group = registrationDto.getAgeGroup();
            Object savedGroup = age_group.get("ageGroup");
            if(savedGroup != null) {
                group = (String) savedGroup;
            }
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
        result.success(group);
    }

    @Override
    public void conditionalBioAttributeValidation(@NonNull String fieldId, @NonNull String expression,
            @NonNull BiometricsPigeon.Result<Boolean> result) {
        try {
            RegistrationDto registrationDto = registrationService.getRegistrationDto();
            List<BiometricsDto> biometricsDtoList = new ArrayList<>();

            for (Modality modality : Modality.values()) {
                List<BiometricsDto> temp = registrationDto.getBestBiometrics(fieldId, modality);
                biometricsDtoList.addAll(temp);
            }
            Map<String, Boolean> dataContext = new HashMap<String, Boolean>();
            Pattern REGEX_PATTERN = Pattern.compile("[a-zA-Z]+");

            Matcher matcher = REGEX_PATTERN.matcher(expression);

            while (matcher.find()) {
                dataContext.put(matcher.group(), false);
                for (BiometricsDto dto : biometricsDtoList) {
                    if (dto.getBioSubType() != null) {
                        if (customMatcher(dto.getBioSubType(), matcher.group())) {
                            dataContext.put(matcher.group(), true);
                            break;
                        }
                    } else {
                        if (dto.getModality().toUpperCase().matches(matcher.group().toUpperCase())) {
                            dataContext.put(matcher.group(), true);
                            break;
                        }
                    }
                }
            }
//            Log.i(TAG, "Printing Map: " + dataContext);
            Boolean response = UserInterfaceHelperService.evaluateValidationExpression(expression, dataContext);
            result.success(response);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    @Override
    public void getListOfDevices(@NonNull String modality, @NonNull BiometricsPigeon.Result<List<BiometricsPigeon.DeviceInfo>> result) {
        deviceDetailsList.clear();
        deviceListResult = result;
        currentDeviceListModality = getModality(modality);
        try {
            Intent intent = new Intent();
            intent.setAction(RegistrationConstants.DISCOVERY_INTENT_ACTION);
            queryPackage(intent);
            DiscoverRequest discoverRequest = new DiscoverRequest();
            discoverRequest.setType(currentDeviceListModality.getSingleType().value());
            intent.putExtra(RegistrationConstants.SBI_INTENT_REQUEST_KEY,
                    objectMapper.writeValueAsBytes(discoverRequest));
            activity.startActivityForResult(intent, 4);
        } catch (Exception e) {
            Log.e(TAG, "Error starting device discovery", e);
            result.success(new ArrayList<>());
        }
    }

    @Override
    public void getOperatorOnboardingBioattributes(@NonNull BiometricsPigeon.Result<String> result) {
        String response = globalParamRepository.getCachedStringOperatorOnboardingBioAttributes();
        result.success(response == null ? "" : response);
    }

    @Override
    public void getCaptureTimeout(@NonNull BiometricsPigeon.Result<Long> result) {
        int timeout = globalParamRepository.getCachedIntCaptureTimeout();
        result.success(Long.valueOf(timeout));
    }

    public void handleDeviceInfoResponseForList(Bundle bundle) {
        try {
            byte[] infoBytes = bundle.getByteArray(RegistrationConstants.SBI_INTENT_RESPONSE_KEY);
            String[] deviceInfo = biometricsService.handleDeviceInfoResponse(currentDeviceListModality, infoBytes);
            BiometricsPigeon.DeviceInfo deviceInfoObj = new BiometricsPigeon.DeviceInfo.Builder()
                    .setDeviceName(deviceInfo[0])
                    .setDeviceId(deviceInfo[1])
                    .setConnectionStatus(deviceInfo[2])
                    .build();

            deviceDetailsList.add(deviceInfoObj);

            if (deviceListResult != null) deviceListResult.success(deviceDetailsList);
        } catch (Exception e) {
            Log.e(TAG, "Error handling device info response", e);
            if (deviceListResult != null) deviceListResult.success(new ArrayList<>());
        }
    }

    // Device listing flow: parse discovery response, then request device info
    public void parseDiscoverResponseForList(Bundle bundle) {
        try {
            byte[] bytes = bundle.getByteArray(RegistrationConstants.SBI_INTENT_RESPONSE_KEY);
            String discoveredCallbackId = biometricsService.handleDiscoveryResponse(currentDeviceListModality, bytes);
            if (discoveredCallbackId != null) {
                infoForList(discoveredCallbackId);
            } else {
                if (deviceListResult != null) deviceListResult.success(new ArrayList<>());
            }
        } catch (BiometricsServiceException e) {
            auditManagerService.audit(AuditEvent.DISCOVER_SBI_PARSE_FAILED, Components.REGISTRATION, e.getMessage());
            Log.e(TAG, "Failed to parse discover response for list", e);
            if (deviceListResult != null) deviceListResult.success(new ArrayList<>());
        }
    }

    private void infoForList(String callbackId) {
        if (callbackId == null) {
            Log.e(TAG, "No SBI found");
            if (deviceListResult != null) deviceListResult.success(new ArrayList<>());
            return;
        }

        try {
            Intent intent = new Intent();
            intent.setAction(callbackId + RegistrationConstants.D_INFO_INTENT_ACTION);
            queryPackage(intent);
            activity.startActivityForResult(intent, 5);
        } catch (ClientCheckedException ex) {
            auditManagerService.audit(AuditEvent.DEVICE_INFO_FAILED, Components.REGISTRATION, ex.getMessage());
            Log.e(TAG, ex.getMessage(), ex);
            if (deviceListResult != null) deviceListResult.success(new ArrayList<>());
        }
    }

    public static Boolean customMatcher(String str1, String str2) {
        Boolean result = false;
        if (str1.matches(LEFT_INDEX_FINGER) && str2.matches("leftIndex")) {
            result = true;
        }
        if (str1.matches(LEFT_MIDDLE_FINGER) && str2.matches("leftMiddle")) {
            result = true;
        }
        if (str1.matches(LEFT_RING_FINGER) && str2.matches("leftRing")) {
            result = true;
        }
        if (str1.matches(LEFT_LITTLE_FINGER) && str2.matches("leftLittle")) {
            result = true;
        }
        if (str1.matches(RIGHT_INDEX_FINGER) && str2.matches("rightIndex")) {
            result = true;
        }
        if (str1.matches(RIGHT_MIDDLE_FINGER) && str2.matches("rightMiddle")) {
            result = true;
        }
        if (str1.matches(RIGHT_RING_FINGER) && str2.matches("rightRing")) {
            result = true;
        }
        if (str1.matches(RIGHT_LITTLE_FINGER) && str2.matches("rightLittle")) {
            result = true;
        }
        if (str1.matches(LEFT_THUMB) && str2.matches("leftThumb")) {
            result = true;
        }
        if (str1.matches(RIGHT_THUMB) && str2.matches("rightThumb")) {
            result = true;
        }
        if (str1.matches(LEFT) && str2.matches("leftEye")) {
            result = true;
        }
        if (str1.matches(RIGHT) && str2.matches("rightEye")) {
            result = true;
        }
        if (str1 == null && str2.matches("face")) {
            result = true;
        }
        return result;
    }

    // public static Map<String, String> objectToMap(Object object) {
    //     Map<String, String> map = new HashMap<>();

    //     // Get all fields of the object using reflection
    //     Field[] fields = object.getClass().getDeclaredFields();

    //     for (Field field : fields) {
    //        // field.setAccessible(true); // Make the private fields accessible

    //         try {
    //             Object fieldValue = field.get(object);
    //             if (fieldValue != null) {
    //                 // Convert field value to String and add it to the map
    //                 map.put(field.getName(), fieldValue.toString());
    //             }
    //         } catch (IllegalAccessException e) {
    //             e.printStackTrace();
    //         }
    //     }

    //     return map;
    // }

    private void queryPackage(Intent intent) throws ClientCheckedException {
        List activities = activity.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_ALL);
        // if(activities.size() == 0)
        // throw new ClientCheckedException("Supported apps not found!");
    }

    private Modality getModality(String modality) {
        Modality currentModality = null;
        if (modality.equals("Iris")) {
            currentModality = Modality.IRIS_DOUBLE;
        } else if (modality.equals("LeftHand")) {
            currentModality = Modality.FINGERPRINT_SLAB_LEFT;
        } else if (modality.equals("RightHand")) {
            currentModality = Modality.FINGERPRINT_SLAB_RIGHT;
        } else if (modality.equals("Thumbs")) {
            currentModality = Modality.FINGERPRINT_SLAB_THUMBS;
        } else if (modality.equals("Face")) {
            currentModality = Modality.FACE;
        } else if (modality.equals("Exception")) {
            currentModality = Modality.EXCEPTION_PHOTO;
        }

        return currentModality;
    }


    private String attributeToBioSubType(String attribute){
//        rightThumb, leftThumb,leftIndex, leftLittle, leftRing, leftMiddle,rightEye, leftEye,rightRing, rightIndex, rightMiddle, rightLittle
        if(attribute.equals("rightThumb")){
            return RIGHT_THUMB;
        }else if(attribute.equals("leftThumb")){
            return LEFT_THUMB;
        }else if(attribute.equals("leftIndex")){
            return LEFT_INDEX_FINGER;
        }else if(attribute.equals("leftLittle")){
            return LEFT_LITTLE_FINGER;
        }else if(attribute.equals("leftRing")){
            return LEFT_RING_FINGER;
        }else if(attribute.equals("leftMiddle")){
            return LEFT_MIDDLE_FINGER;
        }else if(attribute.equals("rightEye")){
            return RIGHT;
        }else if(attribute.equals("leftEye")){
            return LEFT;
        }else if(attribute.equals("rightRing")){
            return RIGHT_RING_FINGER;
        }else if(attribute.equals("rightIndex")){
            return RIGHT_INDEX_FINGER;
        }else if(attribute.equals("rightMiddle")){
            return RIGHT_MIDDLE_FINGER;
        }else if(attribute.equals("rightLittle")){
            return RIGHT_LITTLE_FINGER;
        }
        return "";
    }
    private boolean matchOperatorModality(String bioSubType,String modality) {
       boolean result =false;
        if (modality.equals("Iris")) {
            if(bioSubType!=null && (bioSubType.equals(LEFT)||bioSubType.equals(RIGHT))){
                result=true;

            }
        } else if (modality.equals("LeftHand")) {
            if(bioSubType!=null && (bioSubType.equals(LEFT_LITTLE_FINGER)||bioSubType.equals(LEFT_MIDDLE_FINGER)||bioSubType.equals(LEFT_INDEX_FINGER)||bioSubType.equals(LEFT_RING_FINGER))){
                result=true;

            }

        } else if (modality.equals("RightHand")) {
            if(bioSubType!=null && (bioSubType.equals(RIGHT_LITTLE_FINGER)||bioSubType.equals(RIGHT_MIDDLE_FINGER)||bioSubType.equals(RIGHT_INDEX_FINGER)||bioSubType.equals(RIGHT_RING_FINGER))){
                result=true;

            }
        } else if (modality.equals("Thumbs")) {
            if(bioSubType!=null && (bioSubType.equals(LEFT_THUMB)||bioSubType.equals(RIGHT_THUMB))){
                result=true;

            }
        }

        return result;
    }

    private void discoverSBI() {
        try {
            Log.i("SBI", "Started to discover SBI");
            Intent intent = new Intent();
            intent.setAction(RegistrationConstants.DISCOVERY_INTENT_ACTION);
            queryPackage(intent);
            DiscoverRequest discoverRequest = new DiscoverRequest();
            discoverRequest.setType(currentModality == Modality.EXCEPTION_PHOTO ? SingleType.FACE.value()
                    : currentModality.getSingleType().value());
            intent.putExtra(RegistrationConstants.SBI_INTENT_REQUEST_KEY,
                    objectMapper.writeValueAsBytes(discoverRequest));
            activity.startActivityForResult(intent, 1);
        } catch (Exception ex) {
            auditManagerService.audit(AuditEvent.DISCOVER_SBI_FAILED, Components.REGISTRATION, ex.getMessage());
            Log.e(TAG, ex.getMessage(), ex);
        }
    }

    private void info(String callbackId) {
        if (callbackId == null) {
            Log.e(TAG, "No SBI found");
            return;
        }

        try {
            Intent intent = new Intent();
            intent.setAction(callbackId + RegistrationConstants.D_INFO_INTENT_ACTION);
            queryPackage(intent);
            activity.startActivityForResult(intent, 2);
        } catch (ClientCheckedException ex) {
            auditManagerService.audit(AuditEvent.DEVICE_INFO_FAILED, Components.REGISTRATION, ex.getMessage());
            Log.e(TAG, ex.getMessage(), ex);
        }
    }

    private void rcapture(String callbackId, String deviceId) {
        if (deviceId == null || callbackId == null) {
            Log.e(TAG, "No SBI found!");
            return;
        }

        try {
            auditManagerService.auditWithArguments(
                    AuditEvent.REG_BIO_SCAN,
                    Components.REGISTRATION.getId(),
                    Components.REGISTRATION.getName(),
                    String.valueOf(currentModality)
            );
            Intent intent = new Intent();
            // callbackId = callbackId.replace("\\.info","");
            intent.setAction(callbackId + RegistrationConstants.R_CAPTURE_INTENT_ACTION);
            queryPackage(intent);
            Log.e(TAG, "Initiating capture request : ");
            CaptureRequest captureRequest = biometricsService.getRCaptureRequest(currentModality, deviceId,
                    getExceptionAttributes());
            if (fieldId.equals(OPERATOR_BIOMETRICS)) {
                userOnboardService.setCaptureTransactionId(captureRequest.getTransactionId());
            } else {
                registrationService.getRegistrationDto().setCaptureTransactionId(captureRequest.getTransactionId());
            }
            intent.putExtra("input", objectMapper.writeValueAsBytes(captureRequest));
            activity.startActivityForResult(intent, 3);
        } catch (Exception ex) {
            auditManagerService.audit(AuditEvent.R_CAPTURE_FAILED, Components.REGISTRATION, ex.getMessage());
            Log.e(TAG, ex.getMessage(), ex);
        }
    }

    public void parseDiscoverResponse(Bundle bundle) {
        if (deliverSbiAnswer(SBI_DISCOVER_REQUEST, bundle)) {
            return;
        }
        try {
            byte[] bytes = bundle.getByteArray(RegistrationConstants.SBI_INTENT_RESPONSE_KEY);
            callbackId = biometricsService.handleDiscoveryResponse(currentModality, bytes);
        } catch (BiometricsServiceException e) {
            auditManagerService.audit(AuditEvent.DISCOVER_SBI_PARSE_FAILED, Components.REGISTRATION, e.getMessage());
            Log.e(TAG, "Failed to parse discover response", e);
        }
        info(callbackId);
    }

    public void parseDeviceInfoResponse(Bundle bundle) {
        if (deliverSbiAnswer(SBI_INFO_REQUEST, bundle)) {
            return;
        }
        String callbackId = null;
        String serialNo = null;
        try {
            byte[] bytes = bundle.getByteArray(RegistrationConstants.SBI_INTENT_RESPONSE_KEY);
            String[] result = biometricsService.handleDeviceInfoResponse(currentModality, bytes);
            callbackId = result[0];
            serialNo = result[1];
            Log.i(TAG, callbackId + " --> " + serialNo);
        } catch (Exception e) {
            auditManagerService.audit(AuditEvent.DEVICE_INFO_PARSE_FAILED, Components.REGISTRATION, e.getMessage());
            Log.e(TAG, "Failed to parse device info response", e);
        }
        rcapture(callbackId, serialNo);
    }

//    public void removeDuplicatesFromOperatorBiometricList(String bioSubType){
//        List<BiometricsDto> biometricsDtoListCopy=new ArrayList<>();
//        biometricsDtoListCopy.addAll(userOnboardService.getOperatorBiometrics());
//        biometricsDtoListCopy.forEach(biometricsDto -> {
//            if(biometricsDto.getBioSubType()==null && bioSubType==null){
//                userOnboardService.getOperatorBiometrics().remove(biometricsDto);
//                return;
//            }
//            if(biometricsDto.getBioSubType().equals(bioSubType)){
//                userOnboardService.getOperatorBiometrics().remove(biometricsDto);
//            }
//        });
//
//    }



    public void removeDuplicatesFromOperatorBiometricList(Modality modality){
        List<BiometricsDto> biometricsDtoListCopy=new ArrayList<>();

        userOnboardService.getOperatorBiometrics().forEach(biometricsDto -> {
            if (modality==Modality.IRIS_DOUBLE) {
                if(biometricsDto.getBioSubType()!=null && (biometricsDto.getBioSubType().equals(LEFT)||biometricsDto.getBioSubType().equals(RIGHT))){

                }else{
                    biometricsDtoListCopy.add(biometricsDto);
                }
            } else if (modality==Modality.FINGERPRINT_SLAB_LEFT) {
                if(biometricsDto.getBioSubType()!=null &&(biometricsDto.getBioSubType().equals(LEFT_LITTLE_FINGER)||biometricsDto.getBioSubType().equals(LEFT_MIDDLE_FINGER)||biometricsDto.getBioSubType().equals(LEFT_INDEX_FINGER)||biometricsDto.getBioSubType().equals(LEFT_RING_FINGER))){


                }else{
                    biometricsDtoListCopy.add(biometricsDto);
                }
            } else if (modality==Modality.FINGERPRINT_SLAB_RIGHT) {
                if(biometricsDto.getBioSubType()!=null &&(biometricsDto.getBioSubType().equals(RIGHT_LITTLE_FINGER)||biometricsDto.getBioSubType().equals(RIGHT_MIDDLE_FINGER)||biometricsDto.getBioSubType().equals(RIGHT_INDEX_FINGER)||biometricsDto.getBioSubType().equals(RIGHT_RING_FINGER))){


                }else{
                    biometricsDtoListCopy.add(biometricsDto);
                }
            }else if (modality==Modality.FINGERPRINT_SLAB_THUMBS) {
                if(biometricsDto.getBioSubType()!=null &&(biometricsDto.getBioSubType().equals(LEFT_THUMB)||biometricsDto.getBioSubType().equals(RIGHT_THUMB))){


                }else{
                    biometricsDtoListCopy.add(biometricsDto);
                }
            }else if (modality==Modality.FACE) {
                if(biometricsDto.getBioSubType()==null){

                }else{
                    biometricsDtoListCopy.add(biometricsDto);
                }
            }
        });
        userOnboardService.getOperatorBiometrics().clear();
        userOnboardService.getOperatorBiometrics().addAll(biometricsDtoListCopy);


    }

    public void parseRCaptureResponse(Bundle bundle) {
        if (deliverSbiAnswer(SBI_CAPTURE_REQUEST, bundle)) {
            return;
        }
        try {
            Uri uri = bundle.getParcelable(RegistrationConstants.SBI_INTENT_RESPONSE_KEY);
            InputStream respData = activity.getContentResolver().openInputStream(uri);
            if (!parseRCaptureResponse(respData, null)) {
                completeCapture(CaptureOutcome.Status.FAILED.name());
            }
        } catch (Exception e) {
            auditManagerService.audit(AuditEvent.R_CAPTURE_PARSE_FAILED, Components.REGISTRATION, e.getMessage());
            Log.e(TAG, "Failed to read rcapture response", e);
            completeCapture(CaptureOutcome.Status.FAILED.name());
        }
    }

    /**
     * Checks an SBI capture response and stores the biometrics.
     *
     * @param respData         the rCapture response JSON
     * @param livenessEvidence LIVENESS_* packet evidence of a face that passed liveness, or {@code null}
     * @return {@code true} when the biometrics were stored and the Flutter call completed
     */
    private boolean parseRCaptureResponse(InputStream respData, Map<String, String> livenessEvidence) {
        try {
            boolean isOperatorOnboarding = fieldId.equals(OPERATOR_BIOMETRICS);
            String transactionId = isOperatorOnboarding
                    ? userOnboardService.getCaptureTransactionId()
                    : registrationService.getRegistrationDto().getCaptureTransactionId();
            List<BiometricsDto> biometricsDtoList = biometricsService.handleRCaptureResponse(currentModality, respData,
                    getExceptionAttributes(), transactionId, isOperatorOnboarding);
            if (livenessEvidence != null) {
                biometricsDtoList.forEach(dto -> {
                    dto.setLivenessEvidence(new HashMap<>(livenessEvidence));
                    // The built-in device signs bioSubType "" for FACE; ARC's face lookups expect null
                    if (dto.getBioSubType() != null && dto.getBioSubType().isEmpty()) {
                        dto.setBioSubType(null);
                    }
                });
            }
            // if attempts is zero, there is no need to maintain the counter
            if (fieldId.equals(OPERATOR_BIOMETRICS)) {
                removeDuplicatesFromOperatorBiometricList(currentModality);
                biometricsDtoList.forEach(biometricsDto -> {userOnboardService.getOperatorBiometrics().add(biometricsDto);});
                List<BiometricsDto> biometricsDtoListCopy=new ArrayList<>();
                biometricsDtoListCopy.addAll(biometricsDtoList);
                if(biometricsDtoListCopy!=null && OPERATOR_EXCEPTIONS!=null && !(OPERATOR_EXCEPTIONS.isEmpty())){
                    for (String exception : OPERATOR_EXCEPTIONS) {
                        for (BiometricsDto biometricsDto : biometricsDtoListCopy) {
                            if(biometricsDto.getBioSubType()==null){
                                break;
                            }

                            if (biometricsDto.getBioSubType().equals(attributeToBioSubType(exception))) {
                                userOnboardService.getOperatorBiometrics().remove(biometricsDto);
                                biometricsDtoList.remove(biometricsDto);
                            }
                        }
                    }
                }


                completeCapture("Ok");
            } else {
                currentAttempt = this.registrationService.getRegistrationDto().getBioAttempt(fieldId, currentModality);

                if (biometricsDtoList.isEmpty()) {
                    Toast.makeText(
                            activity.getApplicationContext(),
                            "No biometrics captured. Please try again.",
                            Toast.LENGTH_SHORT
                    ).show();
                    return false;
                }

                biometricsDtoList.forEach(dto -> {
                    try {
                        this.registrationService.getRegistrationDto().addBiometric(
                                fieldId,
                                (currentModality == Modality.EXCEPTION_PHOTO) || (currentModality == Modality.FACE)
                                        ? currentModality.getAttributes().get(0)
                                        : Modality.getBioAttribute(dto.getBioSubType()),
                                currentAttempt,
                                dto
                        );
                    } catch (Exception ex) {
                        Log.e(TAG, ex.getMessage(), ex);
                    }
                });
                completeCapture("Ok");
            }
            return true;
        } catch (BiometricsServiceException e) {
            auditManagerService.audit(AuditEvent.R_CAPTURE_PARSE_FAILED, Components.REGISTRATION, e.getMessage());
            Log.e(TAG, "R-Capture failed: " + e.getErrorCode(), e);
            String message = e.getMessage() != null ? e.getMessage() : "Biometric capture failed";
            Toast.makeText(activity.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            auditManagerService.audit(AuditEvent.R_CAPTURE_PARSE_FAILED, Components.REGISTRATION, e.getMessage());
            Log.e(TAG, "Failed to parse rcapture response", e);
        }
        return false;
    }

    private List<String> getExceptionAttributes() {
        return currentModality.getAttributes().stream()
                .filter(it -> {
                    try {
                        return this.registrationService.getRegistrationDto().isBioException(fieldId, currentModality,
                                it);
                    } catch (Exception e) {
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }
}
