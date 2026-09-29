package io.mosip.registration_client.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowActivity;

import java.util.ArrayList;
import java.util.List;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;

@RunWith(RobolectricTestRunner.class)
public class LivenessCaptureLauncherTest {

    private final FakeLivenessService liveness = new FakeLivenessService();
    private final List<LivenessCaptureResult> results = new ArrayList<>();
    private Activity activity;
    private LivenessCaptureLauncher launcher;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        launcher = new LivenessCaptureLauncher(liveness);
    }

    @Test
    public void nothingStartsWithoutAnActivity() {
        assertFalse(launcher.launch(Workflow.REGISTRATION, "rid", results::add));
        assertTrue(liveness.requests.isEmpty());
    }

    @Test
    public void launchesOnceWithWorkflowSubjectAndLanguage() {
        launcher.attach(activity);
        launcher.setLanguage("ara");
        launcher.setLanguage(" ");

        assertTrue(launcher.launch(Workflow.PACKET_AUTH, "officer1", results::add));

        LivenessCaptureRequest request = liveness.requests.get(0);
        assertEquals(Workflow.PACKET_AUTH, request.workflow());
        assertEquals("officer1", request.subjectRef());
        assertEquals("ara", request.language());
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertEquals(LivenessService.REQUEST_CODE, started.requestCode);
        assertEquals("packet_auth", started.intent.getStringExtra(LivenessCaptureRequest.EXTRA_WORKFLOW));
        assertTrue(launcher.isBusy());
        assertFalse("one capture at a time", launcher.launch(Workflow.REGISTRATION, "rid", results::add));
    }

    @Test
    public void everyResultOfTheCaptureScreenReachesTheCaller() {
        launcher.attach(activity);
        launcher.launch(Workflow.REGISTRATION, "rid", results::add);
        liveness.nextResult = LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.FAIL_PAD).build();

        assertTrue(launcher.onActivityResult(LivenessService.REQUEST_CODE, Activity.RESULT_CANCELED, null));

        assertEquals(1, results.size());
        assertEquals(Decision.FAIL_PAD, results.get(0).decision());
        assertFalse(launcher.isBusy());
        assertTrue(launcher.launch(Workflow.REGISTRATION, "rid", results::add));
    }

    @Test
    public void otherRequestCodesAreLeftToTheSbiHandling() {
        launcher.attach(activity);
        launcher.launch(Workflow.REGISTRATION, "rid", results::add);
        assertFalse(launcher.onActivityResult(3, Activity.RESULT_OK, new Intent()));
        assertTrue(results.isEmpty());
        assertTrue(launcher.isBusy());
    }

    @Test
    public void failedStartFreesTheLauncher() {
        launcher.attach(Robolectric.buildActivity(NoCaptureScreenActivity.class).setup().get());
        assertFalse(launcher.launch(Workflow.REGISTRATION, "rid", results::add));
        assertFalse(launcher.isBusy());
    }

    @Test
    public void replaySelectionLineIsParsed() {
        DebugReplay.Selection s = DebugReplay.parse(" s3-main  10 ");
        assertEquals("s3-main", s.pack);
        assertEquals(10, s.fps);
        assertEquals(15, DebugReplay.parse("m-stream-static").fps);
        assertEquals(15, DebugReplay.parse("pack fast").fps);
        assertNull(DebugReplay.parse("  "));
        assertNull(DebugReplay.parse(null));
    }

    /** An activity whose capture screen cannot be found. */
    public static class NoCaptureScreenActivity extends Activity {
        @Override
        public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
            throw new ActivityNotFoundException("none");
        }
    }
}
