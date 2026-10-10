/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.api_services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowPackageManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.registration.clientmanager.constant.AuditEvent;
import io.mosip.registration.clientmanager.constant.Components;
import io.mosip.registration.clientmanager.constant.RegistrationConstants;
import io.mosip.registration.clientmanager.entity.Audit;
import io.mosip.registration.clientmanager.spi.AuditManagerService;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;
import io.mosip.registration_client.liveness.CaptureOutcome;
import io.mosip.registration_client.liveness.FaceCaptureLivenessGate;
import io.mosip.registration_client.liveness.LivenessCaptureLauncher;
import io.mosip.registration_client.model.BiometricsPigeon;

/**
 * Face captures by face device and liveness setting: with {@code mosip.registration.liveness.enable=N} a tablet
 * without a face SBI app takes the face with the built-in camera (no liveness), a tablet whose face device is a
 * vendor SBI app uses ARC's upstream SBI capture, and with liveness on the vendor app is reached through
 * {@code SbiIntentDevice}.
 */
@RunWith(RobolectricTestRunner.class)
public class BiometricsDetailsApiLivenessOffTest {

    private static final String FIELD = "individualBiometrics";
    private static final String SBI_APP = "com.acme.sbi";

    private final Map<String, String> config = new HashMap<>();
    private final List<LivenessCaptureRequest> screens = new ArrayList<>();
    private final List<String> flutter = new ArrayList<>();
    private final List<AuditEvent> audits = new ArrayList<>();
    private LivenessCaptureResult nextScreenResult;
    private Activity activity;
    private LivenessCaptureLauncher launcher;
    private FaceCaptureLivenessGate gate;
    private BiometricsDetailsApi api;

    @Before
    public void setUp() {
        LivenessService liveness = new ConfigLivenessService();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        launcher = new LivenessCaptureLauncher(liveness);
        launcher.attach(activity);
        gate = new FaceCaptureLivenessGate(liveness);
        api = new BiometricsDetailsApi(new RecordingAudit(), new ObjectMapper(), null, null, null, null);
        api.setActivity(activity);
        api.setLivenessGate(gate, launcher);
        // A live view: each test sets its keys after this.
        api.setLivenessConfig(key -> Optional.ofNullable(config.get(key)));
    }

    // ------------------------------------------------------------------ enable=N

    @Test
    public void livenessOffBuiltInCameraTakesAPhotoWithoutLiveness() {
        config.put(LivenessConfigKeys.ENABLE, "N");

        api.invokeDiscoverSbi(FIELD, "Face", result());

        assertEquals(1, screens.size());
        LivenessCaptureRequest request = screens.get(0);
        assertEquals(Workflow.REGISTRATION, request.workflow());
        assertTrue("signed photo without a liveness check", request.isPhotoWhenOff());
        assertFalse(request.isOverrideOffer());
        assertEquals(0, request.priorAttempts());
        assertNull("no SBI intent", sbiIntent());
    }

    @Test
    public void livenessOffPhotoCancelledCompletesTheCallWithoutLivenessOutcome() {
        config.put(LivenessConfigKeys.ENABLE, "N");
        api.invokeDiscoverSbi(FIELD, "Face", result());

        nextScreenResult = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.CANCELLED).build();
        launcher.onActivityResult(LivenessService.REQUEST_CODE, Activity.RESULT_CANCELED, null);

        assertEquals(Collections.singletonList(CaptureOutcome.Status.CANCELLED.name()), flutter);
        assertEquals(CaptureOutcome.Status.NOT_REQUIRED, gate.lastOutcome(FIELD).status());
        assertEquals("no attempt counted", 0, gate.usedAttempts(FIELD, FIELD));
    }

    @Test
    public void secondScanTapWhileTheScreenIsOpenIsBusyAndTheFirstStillCompletes() {
        config.put(LivenessConfigKeys.ENABLE, "N");
        api.invokeDiscoverSbi(FIELD, "Face", result());
        api.invokeDiscoverSbi(FIELD, "Face", result());
        assertEquals(Collections.singletonList("BUSY"), flutter);
        assertEquals(1, screens.size());

        nextScreenResult = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.CANCELLED).build();
        launcher.onActivityResult(LivenessService.REQUEST_CODE, Activity.RESULT_CANCELED, null);

        assertEquals("the first tap gets its answer", Arrays.asList("BUSY", CaptureOutcome.Status.CANCELLED.name()),
                flutter);
    }

    @Test
    public void livenessOffPhotoRefusedByArcIsNotReportedAsAnAttack() {
        config.put(LivenessConfigKeys.ENABLE, "N");
        api.invokeDiscoverSbi(FIELD, "Face", result());

        // No registration is open in this test, so ARC cannot store the photo.
        nextScreenResult = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.PASS)
                .reasonCodes(Collections.singletonList(LivenessCaptureResult.REASON_LIVENESS_NOT_REQUIRED))
                .capture("txn-1", new byte[] {1, 2, 3}, "{}", true).build();
        launcher.onActivityResult(LivenessService.REQUEST_CODE, Activity.RESULT_OK, new Intent());

        assertEquals(Collections.singletonList(CaptureOutcome.Status.FAILED.name()), flutter);
        assertEquals(CaptureOutcome.Status.NOT_REQUIRED, gate.lastOutcome(FIELD).status());
    }

    @Test
    public void livenessOffOperatorOnboardingAlsoGetsAFaceDevice() {
        config.put(LivenessConfigKeys.ENABLE, "N");

        api.invokeDiscoverSbi(FaceCaptureLivenessGate.OPERATOR_FIELD, "Face", result());

        assertEquals(1, screens.size());
        assertEquals(Workflow.ONBOARD_AUTH, screens.get(0).workflow());
        assertTrue(screens.get(0).isPhotoWhenOff());
    }

    @Test
    public void livenessOffSbiDeviceWithoutSbiAppFallsBackToTheBuiltInCamera() {
        config.put(LivenessConfigKeys.ENABLE, "N");
        config.put(LivenessConfigKeys.ANDROID_DEVICE, "SBI");

        api.invokeDiscoverSbi(FIELD, "Face", result());

        assertEquals(1, screens.size());
        assertTrue(screens.get(0).isPhotoWhenOff());
        assertNull(sbiIntent());
    }

    @Test
    public void livenessOffSbiDeviceWithSbiAppUsesArcsUpstreamCapture() throws Exception {
        config.put(LivenessConfigKeys.ENABLE, "N");
        config.put(LivenessConfigKeys.ANDROID_DEVICE, "SBI");
        installSbiApp();

        api.invokeDiscoverSbi(FIELD, "Face", result());

        assertTrue("no capture screen", screens.isEmpty());
        ShadowActivity.IntentForResult discover = sbiIntent();
        assertNotNull(discover);
        assertEquals(RegistrationConstants.DISCOVERY_INTENT_ACTION, discover.intent.getAction());
        assertEquals(1, discover.requestCode);
    }

    @Test
    public void livenessOffBuiltInDeviceKeepsTheCameraEvenWithAnSbiApp() throws Exception {
        config.put(LivenessConfigKeys.ENABLE, "N");
        installSbiApp();

        api.invokeDiscoverSbi(FIELD, "Face", result());

        assertEquals(1, screens.size());
        assertTrue(screens.get(0).isPhotoWhenOff());
        assertNull(sbiIntent());
    }

    @Test
    public void unknownFaceDeviceValueUsesTheBuiltInCamera() throws Exception {
        config.put(LivenessConfigKeys.ENABLE, "N");
        config.put(LivenessConfigKeys.ANDROID_DEVICE, "USB-WEBCAM");
        installSbiApp();

        api.invokeDiscoverSbi(FIELD, "Face", result());

        assertEquals(1, screens.size());
        assertNull(sbiIntent());
    }

    @Test
    public void otherModalitiesKeepArcsUpstreamCapture() {
        config.put(LivenessConfigKeys.ENABLE, "N");

        api.invokeDiscoverSbi(FIELD, "Iris", result());

        assertTrue(screens.isEmpty());
        assertNotNull(sbiIntent());
    }

    // ------------------------------------------------------------------ liveness on

    @Test
    public void livenessOnBuiltInCameraRunsTheLivenessScreen() {
        api.invokeDiscoverSbi(FIELD, "Face", result());

        assertEquals(1, screens.size());
        assertFalse(screens.get(0).isPhotoWhenOff());
        assertNull(sbiIntent());
    }

    @Test
    public void livenessOnSbiDeviceWithoutStreamIsRefusedByDefault() throws Exception {
        config.put(LivenessConfigKeys.ANDROID_DEVICE, "SBI");
        installSbiApp();

        api.invokeDiscoverSbi(FIELD, "Face", result());

        ShadowActivity.IntentForResult discover = awaitSbiIntent();
        assertEquals(RegistrationConstants.DISCOVERY_INTENT_ACTION, discover.intent.getAction());
        assertTrue("the vendor app, not the camera screen", screens.isEmpty());
        api.parseDiscoverResponse(sbiAnswer(discoverResponse()));
        awaitFlutter();

        assertEquals(Collections.singletonList(CaptureOutcome.Status.FAILED.name()), flutter);
        assertNull("no device info or capture after the no-stream refusal", sbiIntent());
        assertTrue(audits.contains(AuditEvent.R_CAPTURE_FAILED));
    }

    @Test
    public void livenessOnSbiDeviceCaptureOnlyGoesOnToTheVendorCapture() throws Exception {
        config.put(LivenessConfigKeys.ANDROID_DEVICE, "SBI");
        config.put(LivenessConfigKeys.NO_STREAM_POLICY, "CAPTURE_ONLY");
        installSbiApp();

        api.invokeDiscoverSbi(FIELD, "Face", result());
        awaitSbiIntent();
        api.parseDiscoverResponse(sbiAnswer(discoverResponse()));

        ShadowActivity.IntentForResult info = awaitSbiIntent();
        assertEquals(SBI_APP + RegistrationConstants.D_INFO_INTENT_ACTION, info.intent.getAction());
        assertEquals(2, info.requestCode);
        assertTrue(flutter.isEmpty());

        // ARC checks the signed device info; this test has no SBI service, so the info is refused.
        api.parseDeviceInfoResponse(sbiAnswer("{}"));
        awaitFlutter();
        assertEquals(Collections.singletonList(CaptureOutcome.Status.FAILED.name()), flutter);
        assertNull("no rCapture after a refused device info", sbiIntent());
    }

    @Test
    public void livenessOnSbiDeviceWithoutFaceDeviceFails() throws Exception {
        config.put(LivenessConfigKeys.ANDROID_DEVICE, "SBI");
        installSbiApp();

        api.invokeDiscoverSbi(FIELD, "Face", result());
        awaitSbiIntent();
        api.parseDiscoverResponse(sbiAnswer("[]"));
        awaitFlutter();

        assertEquals(Collections.singletonList(CaptureOutcome.Status.FAILED.name()), flutter);
        assertTrue(screens.isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    /** Installs a device app whose activity answers ARC's SBI discovery intent. */
    private void installSbiApp() throws Exception {
        ComponentName discover = new ComponentName(SBI_APP, SBI_APP + ".DiscoverActivity");
        ShadowPackageManager packages = shadowOf(activity.getPackageManager());
        packages.addActivityIfNotPresent(discover);
        IntentFilter filter = new IntentFilter(RegistrationConstants.DISCOVERY_INTENT_ACTION);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        packages.addIntentFilterForActivity(discover, filter);
    }

    /** The next SBI intent (request codes 1 to 3) the API started, skipping the capture screen. */
    private ShadowActivity.IntentForResult sbiIntent() {
        ShadowActivity shadow = shadowOf(activity);
        for (ShadowActivity.IntentForResult next = shadow.getNextStartedActivityForResult(); next != null;
             next = shadow.getNextStartedActivityForResult()) {
            if (next.requestCode != LivenessService.REQUEST_CODE) {
                return next;
            }
        }
        return null;
    }

    /** Runs the main looper until the worker thread has started its next SBI intent. */
    private ShadowActivity.IntentForResult awaitSbiIntent() throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            ShadowActivity.IntentForResult next = sbiIntent();
            if (next != null) {
                return next;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no SBI intent started");
    }

    private void awaitFlutter() throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (flutter.isEmpty() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
    }

    private static Bundle sbiAnswer(String json) {
        Bundle b = new Bundle();
        b.putByteArray(RegistrationConstants.SBI_INTENT_RESPONSE_KEY, json.getBytes(StandardCharsets.UTF_8));
        return b;
    }

    private static String discoverResponse() {
        String digitalId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"serialNo\":\"SN-9\",\"make\":\"ACME\",\"model\":\"FaceCam\",\"type\":\"Face\","
                        + "\"deviceProviderId\":\"acme\"}").getBytes(StandardCharsets.UTF_8));
        return "[{\"deviceId\":\"1\",\"deviceStatus\":\"Ready\",\"certification\":\"L0\","
                + "\"callbackId\":\"" + SBI_APP + "\",\"digitalId\":\"" + digitalId + "\","
                + "\"specVersion\":[\"0.9.5\"],\"purpose\":\"Registration\","
                + "\"error\":{\"errorCode\":\"0\",\"errorInfo\":\"Success\"}}]";
    }

    private BiometricsPigeon.Result<String> result() {
        return new BiometricsPigeon.Result<String>() {
            @Override
            public void success(@NonNull String status) {
                flutter.add(status);
            }

            @Override
            public void error(@NonNull Throwable error) {
                throw new AssertionError(error);
            }
        };
    }

    /** Liveness facade over this test's configuration: policies from {@link PolicyResolver}, screens recorded. */
    private final class ConfigLivenessService implements LivenessService {
        @Override
        public LivenessPolicy policy(Workflow workflow) {
            return new PolicyResolver(ConfigSource.of(config)).resolve(workflow).policy();
        }

        @Override
        public boolean isEnforced(Workflow workflow) {
            return policy(workflow).isEnabled();
        }

        @Override
        public Intent newCaptureIntent(Context context, LivenessCaptureRequest request) {
            screens.add(request);
            return request.writeTo(new Intent("liveness.capture"));
        }

        @Override
        public LivenessCaptureResult parseResult(int resultCode, Intent data) {
            return nextScreenResult != null ? nextScreenResult
                    : LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.CANCELLED).build();
        }

        @Override
        public void preload() {
        }

        @Override
        public boolean isBuiltInDeviceProvisioned() {
            return true;
        }
    }

    /** Keeps the audit events. */
    private final class RecordingAudit implements AuditManagerService {
        @Override
        public void audit(AuditEvent event, Components module) {
            audits.add(event);
        }

        @Override
        public void audit(AuditEvent event, Components module, String errorMsg) {
            audits.add(event);
        }

        @Override
        public void audit(AuditEvent event, String moduleId, String moduleName, String refId, String refIdType) {
            audits.add(event);
        }

        @Override
        public void audit(AuditEvent event, String moduleId, String moduleName) {
            audits.add(event);
        }

        @Override
        public void audit(AuditEvent event, String moduleId, String moduleName, String errorMsg) {
            audits.add(event);
        }

        @Override
        public void auditWithArguments(AuditEvent event, String moduleId, String moduleName, String... arguments) {
            audits.add(event);
        }

        @Override
        public boolean deleteAuditLogs() {
            return false;
        }

        @Override
        public List<Audit> getAuditLogs(long fromDateTime) {
            return Collections.emptyList();
        }
    }
}
