package io.mosip.registration.livenessmanager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.audit.AuditSink;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.config.PolicyMode;
import io.mosip.registration.livenessmanager.ui.LivenessCaptureActivity;

/** The facade other ARC code uses, and the intent contract of the capture screen. */
@RunWith(RobolectricTestRunner.class)
public class LivenessServiceTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void policyComesFromTheSyncedConfiguration() {
        Map<String, String> values = new HashMap<>();
        values.put(LivenessConfigKeys.policyKey(Workflow.PACKET_AUTH), "PASSIVE_ONLY");
        LivenessService service = service(values);
        assertEquals(PolicyMode.PASSIVE_ONLY, service.policy(Workflow.PACKET_AUTH).mode());
        assertTrue(service.isEnforced(Workflow.REGISTRATION));
        assertFalse("exception photo is off by default", service.isEnforced(Workflow.EXCEPTION_PHOTO));

        values.put(LivenessConfigKeys.ENABLE, "N");
        LivenessService off = service(values);
        assertFalse(off.isEnforced(Workflow.REGISTRATION));
        assertFalse(off.isEnforced(Workflow.EOD_AUTH));
        assertTrue("the built-in camera is the default face device", off.isBuiltInCameraFaceDevice());

        values.put(LivenessConfigKeys.ANDROID_DEVICE, "SBI");
        assertFalse(service(values).isBuiltInCameraFaceDevice());
    }

    @Test
    public void builtInCameraIsTheFaceDeviceUnlessSbiIsSelected() {
        Map<String, String> values = new HashMap<>();
        assertTrue("missing", service(values).isBuiltInCameraFaceDevice());
        for (String value : new String[] {"", "  ", "BUILTIN", " builtin ", "VENDOR_X", "camera"}) {
            values.put(LivenessConfigKeys.ANDROID_DEVICE, value);
            assertTrue("'" + value + "' means the built-in camera, as for face capture routing",
                    service(values).isBuiltInCameraFaceDevice());
        }
        for (String value : new String[] {"SBI", " sbi "}) {
            values.put(LivenessConfigKeys.ANDROID_DEVICE, value);
            assertFalse("'" + value + "' selects the vendor face app", service(values).isBuiltInCameraFaceDevice());
        }
    }

    @Test
    public void dimLightCountRoundTripsThroughTheResultIntent() {
        LivenessCaptureResult dim = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.FAIL_QUALITY)
                .failureReason(FailureReason.QUALITY).attempts(1, 3).retryAllowed(true).lowLightRetries(2).build();
        assertEquals(2, LivenessCaptureResult.fromIntent(dim.toIntent()).lowLightRetries());
        assertEquals(2, dim.toBuilder().build().lowLightRetries());
        Intent withoutCount = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.CANCELLED).build()
                .toIntent();
        withoutCount.removeExtra("liveness.result.low_light");
        assertEquals("missing means none", 0, LivenessCaptureResult.fromIntent(withoutCount).lowLightRetries());
        assertEquals(0, LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.FAIL_QUALITY)
                .lowLightRetries(-1).build().lowLightRetries());
    }

    @Test
    public void captureIntentTargetsTheScreenAndCarriesTheRequest() {
        LivenessCaptureRequest request = LivenessCaptureRequest.builder(Workflow.EOD_AUTH, "supervisor1")
                .language("ara").replay("mockmds-still", 12).replayFollow(true).replayMasked(true)
                .photoWhenOff(true).priorAttempts(2).configOverride("k", "v").build();
        Intent intent = service(new HashMap<>()).newCaptureIntent(context, request);
        assertEquals(LivenessCaptureActivity.class.getName(), intent.getComponent().getClassName());

        LivenessCaptureRequest back = LivenessCaptureRequest.fromIntent(intent);
        assertEquals(Workflow.EOD_AUTH, back.workflow());
        assertEquals("supervisor1", back.subjectRef());
        assertEquals("ara", back.language());
        assertEquals(LivenessCaptureRequest.Source.REPLAY, back.source());
        assertEquals("mockmds-still", back.replayPack());
        assertEquals(12, back.replayFps());
        assertTrue(back.isReplayFollow());
        assertTrue(back.isReplayMasked());
        assertTrue(back.isPhotoWhenOff());
        assertEquals(2, back.priorAttempts());
        assertEquals("v", back.configOverrides().get("k"));
        assertTrue(back.isSupervisorFlow());
    }

    @Test
    public void requestFromAdbExtrasUsesSafeDefaults() {
        Intent intent = new Intent();
        intent.putExtra(LivenessCaptureRequest.EXTRA_WORKFLOW, "not-a-workflow");
        intent.putExtra(LivenessCaptureRequest.EXTRA_REPLAY_FPS, "8");
        LivenessCaptureRequest r = LivenessCaptureRequest.fromIntent(intent);
        assertEquals(Workflow.REGISTRATION, r.workflow());
        assertEquals("eng", r.language());
        assertEquals(LivenessCaptureRequest.Source.CAMERA, r.source());
        assertEquals(LivenessCaptureRequest.Source.CAMERA, LivenessCaptureRequest.fromIntent(null).source());
    }

    @Test
    public void passResultRoundTripsThroughTheIntent() {
        byte[] iso = {1, 2, 3};
        LivenessCaptureResult result = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.PASS)
                .sessionId("s-9").attempts(0, 3).onMaxFailure(OnMaxFailure.BLOCK).tokenNonce("n")
                .capture("tx-9", iso, "{\"biometrics\":[]}", true)
                .evidence(PacketEvidenceKeys.DECISION, PacketEvidenceKeys.DECISION_PASS).build();

        LivenessCaptureResult back = service(new HashMap<>()).parseResult(Activity.RESULT_OK, result.toIntent());
        assertTrue(back.isPass());
        assertEquals("s-9", back.sessionId());
        assertEquals("tx-9", back.transactionId());
        assertArrayEquals(iso, back.faceIso());
        assertEquals("{\"biometrics\":[]}", back.rCaptureResponse());
        assertTrue(back.isDeviceProvisioned());
        assertEquals(OnMaxFailure.BLOCK, back.onMaxFailure());
        assertEquals(PacketEvidenceKeys.DECISION_PASS, back.evidence().get(PacketEvidenceKeys.DECISION));
    }

    @Test
    public void failureResultKeepsReasonsAndRecoveryFlags() {
        LivenessCaptureResult result = LivenessCaptureResult.builder(Workflow.LOGIN_AUTH, Decision.FAIL_PAD)
                .failureReason(FailureReason.INTEGRITY).reasonCodes(Arrays.asList("FROZEN", "PLANAR"))
                .messageKey("liveness.fail.pad").attempts(3, 3).lockRequired(true)
                .onMaxFailure(OnMaxFailure.BLOCK_AND_LOCK).build();
        LivenessCaptureResult back = LivenessCaptureResult.fromIntent(result.toIntent());
        assertEquals(Decision.FAIL_PAD, back.decision());
        assertEquals(FailureReason.INTEGRITY, back.failureReason());
        assertEquals(Arrays.asList("FROZEN", "PLANAR"), back.reasonCodes());
        assertEquals("liveness.fail.pad", back.messageKey());
        assertEquals(3, back.attemptsUsed());
        assertTrue(back.isLockRequired());
        assertFalse(back.isRetryAllowed());
        assertNull(back.faceIso());
    }

    @Test
    public void missingResultIsACancellation() {
        LivenessCaptureResult r = service(new HashMap<>()).parseResult(Activity.RESULT_CANCELED, null);
        assertEquals(Decision.CANCELLED, r.decision());
        assertEquals(FailureReason.CANCELLED, r.failureReason());
    }

    private LivenessService service(Map<String, String> values) {
        return new DefaultLivenessService(LivenessRuntime.create(context, ConfigSource.of(values), AuditSink.noop()));
    }
}
