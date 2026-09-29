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
    }

    @Test
    public void captureIntentTargetsTheScreenAndCarriesTheRequest() {
        LivenessCaptureRequest request = LivenessCaptureRequest.builder(Workflow.EOD_AUTH, "supervisor1")
                .language("ara").replay("mockmds-still", 12).configOverride("k", "v").build();
        Intent intent = service(new HashMap<>()).newCaptureIntent(context, request);
        assertEquals(LivenessCaptureActivity.class.getName(), intent.getComponent().getClassName());

        LivenessCaptureRequest back = LivenessCaptureRequest.fromIntent(intent);
        assertEquals(Workflow.EOD_AUTH, back.workflow());
        assertEquals("supervisor1", back.subjectRef());
        assertEquals("ara", back.language());
        assertEquals(LivenessCaptureRequest.Source.REPLAY, back.source());
        assertEquals("mockmds-still", back.replayPack());
        assertEquals(12, back.replayFps());
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
