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

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.liveness.core.ui.Severity;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.R;
import io.mosip.registration.livenessmanager.SupervisorOverride;

/**
 * The capture screen takes its language from the request (ARC's language), not from the device, and closes with
 * a cancellation when liveness is off for the workflow. Liveness is switched off through a debug config override
 * so that no camera or engine is needed. The rendering tests show engine states on the override-offer screen,
 * which stays open without a camera: the face outline (not the smiley) while the engine waits for a neutral face,
 * and the status colours shared with the desktop client. The request carries the earlier screens' suspected attack
 * and dim-light count, and a screen cancelled before its first attempt ended reports that count back.
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
    public void priorAttackAndDimLightCountRoundTripThroughTheIntent() {
        LivenessCaptureRequest back = LivenessCaptureRequest.fromIntent(LivenessCaptureRequest
                .builder(Workflow.REGISTRATION, "rid").priorAttempts(1).priorSuspectedAttack(true).priorLowLight(2)
                .build().writeTo(new Intent(context, LivenessCaptureActivity.class)));
        assertEquals(1, back.priorAttempts());
        assertTrue(back.isPriorSuspectedAttack());
        assertEquals(2, back.priorLowLight());

        LivenessCaptureRequest none = LivenessCaptureRequest.fromIntent(new Intent(context,
                LivenessCaptureActivity.class).putExtra(LivenessCaptureRequest.EXTRA_WORKFLOW, "registration"));
        assertFalse("a missing extra is no earlier attack", none.isPriorSuspectedAttack());
        assertEquals("a missing extra is no earlier dim-light result", 0, none.priorLowLight());

        // adb passes strings; a negative count is kept as 0.
        LivenessCaptureRequest adb = LivenessCaptureRequest.fromIntent(new Intent()
                .putExtra(LivenessCaptureRequest.EXTRA_PRIOR_SUSPECTED_ATTACK, "true")
                .putExtra(LivenessCaptureRequest.EXTRA_PRIOR_LOW_LIGHT, "3"));
        assertTrue(adb.isPriorSuspectedAttack());
        assertEquals(3, adb.priorLowLight());
        assertEquals(0, LivenessCaptureRequest.fromIntent(new Intent()
                .putExtra(LivenessCaptureRequest.EXTRA_PRIOR_LOW_LIGHT, -2)).priorLowLight());
    }

    @Test
    public void cancelBeforeTheFirstAttemptEndsKeepsTheEarlierDimLightCount() {
        // Liveness is on; without the camera permission no attempt starts, so Cancel ends the screen at once.
        ActivityController<LivenessCaptureActivity> controller = launch(LivenessCaptureRequest
                .builder(Workflow.REGISTRATION, "rid").priorAttempts(1).priorLowLight(2));
        LivenessCaptureActivity activity = controller.get();
        assertFalse(activity.isFinishing());

        activity.onBackPressed();

        assertTrue(activity.isFinishing());
        LivenessCaptureResult result = LivenessCaptureResult.fromIntent(Shadows.shadowOf(activity).getResultIntent());
        assertEquals(Decision.CANCELLED, result.decision());
        assertEquals("the next screen continues from the same dim-light count", 2, result.lowLightRetries());
        controller.destroy();
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

    @Test
    public void faceOvalUsesTheFaceOutlineNotTheSmiley() {
        assertEquals(R.drawable.ic_liveness_face, LivenessCaptureActivity.iconOf(PromptIcon.FACE_OVAL));
        assertEquals(R.drawable.ic_liveness_smile, LivenessCaptureActivity.iconOf(PromptIcon.SMILE));
        for (PromptIcon icon : PromptIcon.values()) {
            if (icon != PromptIcon.SMILE) {
                assertTrue("smiley shown for " + icon,
                        LivenessCaptureActivity.iconOf(icon) != R.drawable.ic_liveness_smile);
            }
        }
    }

    @Test
    public void faceOvalShownWhileTheEngineWaitsForANeutralFace() {
        LivenessCaptureActivity activity = openScreen();
        activity.render(LivenessUiState.builder(UiPhase.HOLD_STILL, MessageKeys.FEEDBACK_LOOK_STRAIGHT)
                .promptIcon(PromptIcon.FACE_OVAL).severity(Severity.INFO).progress(0.9).build());

        TextView prompt = activity.findViewById(R.id.liveness_prompt);
        assertEquals("Look directly at the camera and hold still.", prompt.getText().toString());
        View icon = activity.findViewById(R.id.liveness_icon);
        assertEquals(R.drawable.ic_liveness_face, icon.getTag(R.id.liveness_icon));

        activity.render(LivenessUiState.builder(UiPhase.CHALLENGE, MessageKeys.CHALLENGE_SMILE)
                .promptIcon(PromptIcon.SMILE).severity(Severity.INFO).build());
        assertEquals(R.drawable.ic_liveness_smile, icon.getTag(R.id.liveness_icon));
    }

    @Test
    public void faceOvalDrawableIsTheDesktopFaceOutline() throws Exception {
        String xml = read(new File("src/main/res/drawable/ic_liveness_face.xml"));
        String smile = read(new File("src/main/res/drawable/ic_liveness_smile.xml"));
        assertTrue(xml, xml.contains("android:fillType=\"evenOdd\""));
        // Outer oval and inner oval of the desktop FACE_OVAL icon: a ring, no eyes or mouth.
        assertTrue(xml, xml.contains("M12,2C7.6,2 5,5.6 5,10.5S7.6,22 12,22s7,-6.6 7,-11.5S16.4,2 12,2z"));
        assertFalse(pathOf(xml).equals(pathOf(smile)));
    }

    @Test
    public void statusColoursFollowTheStateSeverity() {
        LivenessCaptureActivity activity = openScreen();
        TextView prompt = activity.findViewById(R.id.liveness_prompt);
        OvalGuideView guide = activity.findViewById(R.id.liveness_guide);
        View icon = activity.findViewById(R.id.liveness_icon);
        Object[][] cases = {
                {Severity.INFO, R.color.liveness_status_neutral_text, R.color.liveness_oval_neutral,
                        R.color.liveness_status_neutral_accent},
                {Severity.SUCCESS, R.color.liveness_status_success_text, R.color.liveness_oval_success,
                        R.color.liveness_status_success_accent},
                {Severity.WARNING, R.color.liveness_status_attention_text, R.color.liveness_oval_attention,
                        R.color.liveness_status_attention_accent},
                {Severity.ERROR, R.color.liveness_status_failure_text, R.color.liveness_oval_failure,
                        R.color.liveness_status_failure_accent},
        };
        for (Object[] c : cases) {
            activity.render(LivenessUiState.builder(UiPhase.CHALLENGE, MessageKeys.CHALLENGE_BLINK)
                    .promptIcon(PromptIcon.BLINK).severity((Severity) c[0]).build());
            assertEquals(c[0] + " prompt", color((Integer) c[1]), prompt.getCurrentTextColor());
            assertEquals(c[0] + " oval", color((Integer) c[2]), guide.outlineColor());
            assertEquals(c[0] + " icon circle", color((Integer) c[3]),
                    icon.getBackgroundTintList().getDefaultColor());
        }
    }

    @Test
    public void statusColoursMatchTheDesktopPalette() throws Exception {
        File cssFile = new File(
                "../../../desktop/registration/registration-client/src/main/resources/liveness-panel.css");
        Assume.assumeTrue("desktop client not in this checkout", cssFile.isFile());
        String css = read(cssFile);
        String[][] states = {
                {"", "neutral"}, {".liveness-panel.liveness-success ", "success"},
                {".liveness-panel.liveness-warning ", "attention"}, {".liveness-panel.liveness-error ", "failure"},
        };
        for (String[] st : states) {
            String name = st[1];
            assertEquals(name + " banner", cssColor(css, st[0] + ".liveness-banner", "-fx-background-color"),
                    color("liveness_status_" + name + "_banner"));
            assertEquals(name + " border", cssColor(css, st[0] + ".liveness-banner", "-fx-border-color"),
                    color("liveness_status_" + name + "_accent"));
            assertEquals(name + " text", cssColor(css, st[0] + ".liveness-prompt", "-fx-text-fill"),
                    color("liveness_status_" + name + "_text"));
            assertEquals(name + " oval", cssColor(css, st[0] + ".liveness-oval", "-fx-stroke"),
                    color("liveness_oval_" + name));
        }
        assertEquals(cssColor(css, ".liveness-guidance .label", "-fx-text-fill"),
                color(R.color.liveness_status_guidance));
        assertEquals(cssColor(css, ".liveness-icon-box", "-fx-background-color"), color(R.color.liveness_primary));
    }

    @Test
    public void statusColoursKeepTextContrast() {
        for (String name : new String[] {"neutral", "success", "attention", "failure"}) {
            int banner = color("liveness_status_" + name + "_banner");
            assertAtLeast(name + " prompt", 4.5, contrast(color("liveness_status_" + name + "_text"), banner));
            assertAtLeast(name + " guidance", 4.5, contrast(color(R.color.liveness_status_guidance), banner));
            assertAtLeast(name + " icon", 4.5, contrast(color(R.color.liveness_text),
                    color("liveness_status_" + name + "_accent")));
            // Non-text: the oval over the dimmed preview needs 3:1.
            assertAtLeast(name + " oval", 3.0, contrast(color("liveness_oval_" + name), 0xFF000000));
        }
        assertAtLeast("retry button", 4.5, contrast(color(R.color.liveness_text), color(R.color.liveness_primary)));
        int panel = 0xFF000000 | color(R.color.liveness_panel);
        assertAtLeast("used attempt dot", 3.0, contrast(color(R.color.liveness_dot_used), panel));
        assertAtLeast("step text", 4.5, contrast(color(R.color.liveness_text_secondary), panel));
    }

    private LivenessCaptureActivity openScreen() {
        LivenessCaptureActivity activity = launch(overridePolicy(LivenessCaptureRequest.builder(
                Workflow.REGISTRATION, "rid").overrideOffer(true))).get();
        assertFalse(activity.isFinishing());
        return activity;
    }

    private int color(int id) {
        return context.getColor(id);
    }

    private int color(String name) {
        int id = context.getResources().getIdentifier(name, "color", context.getPackageName());
        assertTrue("no colour " + name, id != 0);
        return context.getColor(id);
    }

    private static String read(File file) throws java.io.IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String pathOf(String vectorXml) {
        Matcher m = Pattern.compile("android:pathData=\"([^\"]+)\"").matcher(vectorXml);
        assertTrue(m.find());
        return m.group(1);
    }

    /** Colour of a property in the rule whose selector is exactly {@code selector}, as opaque ARGB. */
    private static int cssColor(String css, String selector, String property) {
        Matcher rule = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\s*\\{([^}]*)}").matcher(css);
        assertTrue("no CSS rule " + selector, rule.find());
        Matcher value = Pattern.compile(Pattern.quote(property) + ":\\s*#([0-9A-Fa-f]{6})").matcher(rule.group(1));
        assertTrue("no " + property + " in " + selector, value.find());
        return 0xFF000000 | Integer.parseInt(value.group(1), 16);
    }

    /** WCAG 2.1 contrast ratio of two opaque colours. */
    private static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int argb) {
        return 0.2126 * channel((argb >> 16) & 0xFF) + 0.7152 * channel((argb >> 8) & 0xFF)
                + 0.0722 * channel(argb & 0xFF);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static void assertAtLeast(String what, double min, double actual) {
        assertTrue(what + " contrast " + actual + " below " + min, actual >= min);
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
