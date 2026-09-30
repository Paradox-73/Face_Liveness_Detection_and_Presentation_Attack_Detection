package io.mosip.registration.livenessmanager.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.R;
import io.mosip.registration.livenessmanager.SupervisorOverride;

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

    @Test
    public void overrideOfferIsRefusedUnderTheShippedBlockPolicy() {
        Activity activity = launch(LivenessCaptureRequest.builder(Workflow.REGISTRATION, "rid").overrideOffer(true))
                .get();

        assertTrue(activity.isFinishing());
        LivenessCaptureResult result = LivenessCaptureResult.fromIntent(Shadows.shadowOf(activity).getResultIntent());
        assertEquals(Decision.ERROR, result.decision());
        assertNull(result.overrideRequestedBy());
    }

    @Test
    public void overridePhotoIsRefusedUnderTheShippedBlockPolicy() {
        Activity activity = launch(LivenessCaptureRequest.builder(Workflow.REGISTRATION, "rid")
                .override(new SupervisorOverride("supervisor1", java.util.Collections.emptyMap()))).get();

        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
    }

    @Test
    public void overrideOfferAsksForTheSupervisorAndReturnsTheRequest() {
        Activity activity = launch(overridePolicy(LivenessCaptureRequest.builder(Workflow.REGISTRATION, "rid")
                .overrideOffer(true))).get();
        assertFalse(activity.isFinishing());
        View button = activity.findViewById(R.id.liveness_override);
        assertEquals(View.VISIBLE, button.getVisibility());
        TextView prompt = activity.findViewById(R.id.liveness_prompt);
        assertTrue(prompt.getText().toString().startsWith("Face capture could not be completed after 3 attempts"));

        button.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        EditText supervisor = findEditText(dialog.getWindow().getDecorView());
        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();
        assertFalse("a supervisor ID is required", activity.isFinishing());
        assertNotNull(supervisor.getError());

        supervisor.setText("supervisor1");
        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(activity.isFinishing());
        LivenessCaptureResult result = LivenessCaptureResult.fromIntent(Shadows.shadowOf(activity).getResultIntent());
        assertEquals("supervisor1", result.overrideRequestedBy());
        assertTrue(result.isOverrideAllowed());
    }

    @Test
    public void diagnosticModeShowsTheMetricsPanel() {
        Activity activity = launch(overridePolicy(LivenessCaptureRequest.builder(Workflow.REGISTRATION, "rid")
                .overrideOffer(true)).configOverride(LivenessConfigKeys.DIAGNOSTIC_ENABLE, LivenessConfigKeys.YES))
                .get();

        TextView diagnostics = activity.findViewById(R.id.liveness_diagnostics);
        assertEquals(View.VISIBLE, diagnostics.getVisibility());
        String text = diagnostics.getText().toString();
        assertTrue(text, text.contains("Diagnostics: registration"));
        assertTrue(text, text.contains("retry rate"));
    }

    @Test
    public void diagnosticPanelIsHiddenByDefault() {
        Activity activity = launch(overridePolicy(LivenessCaptureRequest.builder(Workflow.REGISTRATION, "rid")
                .overrideOffer(true))).get();
        assertEquals(View.GONE, activity.findViewById(R.id.liveness_diagnostics).getVisibility());
    }

    private static LivenessCaptureRequest.Builder overridePolicy(LivenessCaptureRequest.Builder request) {
        return request.configOverride(LivenessConfigKeys.workflowKey(Workflow.REGISTRATION,
                LivenessConfigKeys.WORKFLOW_ON_MAX_FAILURE_SUFFIX), "SUPERVISOR_OVERRIDE");
    }

    private ActivityController<LivenessCaptureActivity> launch(LivenessCaptureRequest.Builder request) {
        Intent intent = request.build().writeTo(new Intent(context, LivenessCaptureActivity.class));
        return Robolectric.buildActivity(LivenessCaptureActivity.class, intent).setup();
    }

    private static EditText findEditText(View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditText(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private ActivityController<LivenessCaptureActivity> launchWithLivenessOff(String language) {
        Intent intent = LivenessCaptureRequest.builder(Workflow.REGISTRATION, "subject").language(language)
                .configOverride(LivenessConfigKeys.ENABLE, LivenessConfigKeys.NO).build()
                .writeTo(new Intent(context, LivenessCaptureActivity.class));
        return Robolectric.buildActivity(LivenessCaptureActivity.class, intent).setup();
    }
}
