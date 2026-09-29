package io.mosip.registration.livenessmanager.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowActivity;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;

/**
 * The capture screen takes its language from the request (ARC's language), not from the device, and closes with
 * a cancellation when liveness is off for the workflow. Liveness is switched off through a debug config override
 * so that no camera or engine is needed.
 */
@RunWith(RobolectricTestRunner.class)
public class LivenessCaptureActivityTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void arabicRequestShowsArabicTextRightToLeft() {
        Activity activity = launchWithLivenessOff("ara").get();

        TextView title = activity.findViewById(io.mosip.registration.livenessmanager.R.id.liveness_title);
        assertEquals("التحقق من الوجه", title.getText().toString());
        View panel = activity.findViewById(io.mosip.registration.livenessmanager.R.id.liveness_panel);
        assertEquals(View.LAYOUT_DIRECTION_RTL, panel.getLayoutDirection());
    }

    @Test
    public void englishRequestShowsEnglishTextLeftToRight() {
        Activity activity = launchWithLivenessOff("eng").get();

        TextView title = activity.findViewById(io.mosip.registration.livenessmanager.R.id.liveness_title);
        assertEquals("Face verification", title.getText().toString());
        View panel = activity.findViewById(io.mosip.registration.livenessmanager.R.id.liveness_panel);
        assertEquals(View.LAYOUT_DIRECTION_LTR, panel.getLayoutDirection());
    }

    @Test
    public void livenessOffClosesWithCancellation() {
        Activity activity = launchWithLivenessOff("eng").get();

        assertTrue(activity.isFinishing());
        ShadowActivity shadow = Shadows.shadowOf(activity);
        assertEquals(Activity.RESULT_CANCELED, shadow.getResultCode());
        LivenessCaptureResult result = LivenessCaptureResult.fromIntent(shadow.getResultIntent());
        assertEquals(Decision.CANCELLED, result.decision());
        assertEquals(Workflow.REGISTRATION, result.workflow());
    }

    private ActivityController<LivenessCaptureActivity> launchWithLivenessOff(String language) {
        Intent intent = LivenessCaptureRequest.builder(Workflow.REGISTRATION, "subject").language(language)
                .configOverride(LivenessConfigKeys.ENABLE, LivenessConfigKeys.NO).build()
                .writeTo(new Intent(context, LivenessCaptureActivity.class));
        return Robolectric.buildActivity(LivenessCaptureActivity.class, intent).setup();
    }
}
