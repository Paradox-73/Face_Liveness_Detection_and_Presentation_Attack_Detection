package io.mosip.registration.livenessmanager.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.audit.DiagnosticSink;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.policy.PolicyResolver;
import io.mosip.liveness.core.ui.LivenessUiState;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.liveness.core.ui.PromptIcon;
import io.mosip.liveness.core.ui.Severity;
import io.mosip.liveness.core.ui.UiPhase;
import io.mosip.registration.livenessmanager.BuildConfig;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessRuntime;
import io.mosip.registration.livenessmanager.R;
import io.mosip.registration.livenessmanager.config.OverlayConfigSource;
import io.mosip.registration.livenessmanager.device.BuiltInCameraL0Device;
import io.mosip.registration.livenessmanager.device.CameraXFrameSource;
import io.mosip.registration.livenessmanager.device.ReplayFrameSource;
import io.mosip.registration.livenessmanager.device.ReplayPromptFollower;
import io.mosip.registration.livenessmanager.session.CaptureRunner;
import io.mosip.registration.livenessmanager.session.LivenessRunner;
import io.mosip.registration.livenessmanager.session.PhotoCaptureRunner;

/**
 * The native face capture screen: full-screen camera preview, oval face guide, prompt with icon, challenge step,
 * progress, attempt dots and Retry / Cancel buttons. It renders only the engine's {@link LivenessUiState}, so the
 * behaviour matches the desktop client, and returns a {@link LivenessCaptureResult} on every path.
 *
 * <p>Start it with {@code LivenessService.newCaptureIntent} and request code {@code LivenessService.REQUEST_CODE}.
 * Result code {@link Activity#RESULT_OK} means a pass with a signed capture; {@link Activity#RESULT_CANCELED}
 * carries the failure or cancellation details.
 *
 * <p>Accessibility: every state has text, an icon and a colour (never colour alone); the prompt is a polite live
 * region and each new prompt is announced for TalkBack; text sizes are in {@code sp}, so the system font size
 * applies; buttons are at least 48 dp high.
 */
public final class LivenessCaptureActivity extends AppCompatActivity {

    private static final String TAG = "Liveness";
    private static final int PERMISSION_REQUEST = 61;
    private static final long SUCCESS_DISPLAY_MS = 900L;
    private static final String REPLAY_ASSETS = "liveness-replay";

    private final Handler main = new Handler(Looper.getMainLooper());
    private LivenessCaptureRequest request;
    /** This screen's context in the language the caller asked for (ARC's language, not the device's). */
    private Context ui;
    private LivenessRuntime runtime;
    private ConfigSource config;
    private MessageResolver messages;
    private CaptureRunner runner;
    private LivenessCaptureResult lastResult;
    private boolean finishing;
    private String lastPrompt;
    /** Debug replay that answers challenge prompts; replaced for every attempt's stream. */
    private volatile ReplayPromptFollower follower;

    private PreviewView preview;
    private ImageView replayView;
    private OvalGuideView guide;
    private ImageView icon;
    private TextView prompt;
    private TextView guidance;
    private TextView step;
    private ProgressBar progress;
    private LinearLayout attempts;
    private MaterialButton retry;
    private MaterialButton cancel;
    private MaterialButton settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        request = LivenessCaptureRequest.fromIntent(getIntent());
        if (!BuildConfig.DEBUG && request.source() == LivenessCaptureRequest.Source.REPLAY) {
            request = LivenessCaptureRequest.builder(request.workflow(), request.subjectRef())
                    .language(request.language()).build();
        }
        ui = localized(MessageResolver.localeOf(request.language()));
        View root = LayoutInflater.from(ui).inflate(R.layout.liveness_capture_activity, null);
        root.setLayoutDirection(ui.getResources().getConfiguration().getLayoutDirection());
        setContentView(root);
        bindViews();

        runtime = LivenessRuntime.get(this);
        config = BuildConfig.DEBUG && !request.configOverrides().isEmpty()
                ? new OverlayConfigSource(runtime.config(), request.configOverrides()) : runtime.config();
        messages = new MessageResolver(ui.getResources(), config, request.language());
        showHeader();

        LivenessPolicy policy = config == runtime.config() ? runtime.resolve(request.workflow()).policy()
                : new PolicyResolver(config).resolve(request.workflow()).policy();
        if (!policy.isEnabled() && !request.isPhotoWhenOff()) {
            Log.w(TAG, "Liveness is off for " + request.workflow().id() + "; capture screen closed");
            finishWith(Activity.RESULT_CANCELED, LivenessCaptureResult.builder(request.workflow(), Decision.CANCELLED)
                    .failureReason(FailureReason.CANCELLED).build());
            return;
        }
        runner = policy.isEnabled() ? createRunner(policy) : createPhotoRunner();
        if (needsCameraPermission()) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, PERMISSION_REQUEST);
        } else {
            runner.start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST || runner == null) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            runner.start();
        } else {
            showPermissionError();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (runner != null && !isFinishing()) {
            // The camera stops with the screen; end the attempt cleanly instead of waiting for the frame watchdog.
            runner.cancel();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (runner != null) {
            runner.close();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        cancelCapture();
    }

    // ------------------------------------------------------------------ setup

    private void bindViews() {
        preview = findViewById(R.id.liveness_preview);
        replayView = findViewById(R.id.liveness_replay);
        guide = findViewById(R.id.liveness_guide);
        icon = findViewById(R.id.liveness_icon);
        prompt = findViewById(R.id.liveness_prompt);
        guidance = findViewById(R.id.liveness_guidance);
        step = findViewById(R.id.liveness_step);
        progress = findViewById(R.id.liveness_progress);
        attempts = findViewById(R.id.liveness_attempts);
        retry = findViewById(R.id.liveness_retry);
        cancel = findViewById(R.id.liveness_cancel);
        settings = findViewById(R.id.liveness_settings);
        View panel = findViewById(R.id.liveness_panel);
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> guide.setBottomInset(b - t));
        retry.setOnClickListener(v -> onRetry());
        cancel.setOnClickListener(v -> cancelCapture());
        settings.setOnClickListener(v -> openAppSettings());
    }

    private void showHeader() {
        TextView title = findViewById(R.id.liveness_title);
        if (request.isSupervisorFlow()) {
            title.setText(messages.text(MessageKeys.TITLE_SUPERVISOR, null));
        }
        TextView badge = findViewById(R.id.liveness_badge);
        List<String> labels = new ArrayList<>();
        if (request.source() == LivenessCaptureRequest.Source.REPLAY) {
            labels.add(ui.getString(R.string.liveness_ui_replay_badge));
        }
        if (!isOnline()) {
            labels.add(messages.text(MessageKeys.OFFLINE_BADGE, null));
        }
        if (!labels.isEmpty()) {
            badge.setText(String.join("  \u00b7  ", labels));
            badge.setVisibility(View.VISIBLE);
        }
    }

    private LivenessRunner createRunner(LivenessPolicy policy) {
        BuiltInCameraL0Device device = createDevice();
        DiagnosticSink diagnostics = policy.isDiagnosticEnabled() ? runtime.diagnostics() : DiagnosticSink.noop();
        String blocked = runtime.isBlockedEmulator() ? MessageKeys.ERROR_EMULATOR : null;
        return new LivenessRunner(policy, request.subjectRef(), runtime.engines(), device, runtime.decoder(),
                runtime.audit(), diagnostics, runtime.clock(), blocked, request.priorAttempts(),
                new LivenessRunner.Listener() {
            @Override
            public void onUiState(LivenessUiState state) {
                main.post(() -> render(state));
            }

            @Override
            public void onAttemptEnded(LivenessCaptureResult result) {
                main.post(() -> attemptEnded(result));
            }
        });
    }

    /** The workflow's liveness is off, but the caller needs a signed photo (the exception photo). */
    private PhotoCaptureRunner createPhotoRunner() {
        Log.i(TAG, "Liveness is off for " + request.workflow().id() + "; taking a photo without a liveness check");
        return new PhotoCaptureRunner(request.workflow(), createDevice(), new LivenessRunner.Listener() {
            @Override
            public void onUiState(LivenessUiState state) {
                main.post(() -> render(state));
            }

            @Override
            public void onAttemptEnded(LivenessCaptureResult result) {
                main.post(() -> attemptEnded(result));
            }
        });
    }

    /** The built-in camera as an SBI L0 face device, streaming from CameraX or (debug builds) a replay pack. */
    private BuiltInCameraL0Device createDevice() {
        String serial = runtime.deviceSerial();
        boolean replay = request.source() == LivenessCaptureRequest.Source.REPLAY;
        if (replay) {
            preview.setVisibility(View.GONE);
            replayView.setVisibility(View.VISIBLE);
        }
        androidx.camera.core.Preview.SurfaceProvider surface = preview.getSurfaceProvider();
        BuiltInCameraL0Device.StreamFactory streams = streamRequest -> replay ? openReplay(serial)
                : new CameraXFrameSource(this, this, surface, runtime.clock(), serial);
        return new BuiltInCameraL0Device(serial,
                request.workflow().isAuth() ? DevicePurpose.AUTH : DevicePurpose.REGISTRATION, streams,
                () -> replay || hasCameraPermission(), runtime.decoder(), runtime.deviceSigner(), runtime.clock(),
                BuildConfig.DEBUG ? "Developer" : "Production");
    }

    private FaceFrameSource openReplay(String serial) throws io.mosip.liveness.core.device.DeviceException {
        String pack = request.replayPack().isEmpty() ? "mockmds-still" : request.replayPack();
        if (request.isReplayFollow()) {
            return openFollowingReplay(serial, pack);
        }
        List<byte[]> frames = readPack(pack);
        Log.i(TAG, "Replay " + pack + ": " + frames.size() + " frames at " + request.replayFps() + " fps");
        return new ReplayFrameSource(frames, request.replayFps(), true, runtime.clock(), serial, this::showReplayFrame);
    }

    /** Replays {@code <prefix>-main} and switches to the movement packs when the screen prompts for them. */
    private FaceFrameSource openFollowingReplay(String serial, String prefix)
            throws io.mosip.liveness.core.device.DeviceException {
        List<byte[]> neutral = readPack(prefix + "-main");
        Map<PromptIcon, List<byte[]>> movements = new EnumMap<>(PromptIcon.class);
        String[][] names = {{"BLINK", "blink"}, {"SMILE", "smile"}, {"TURN_LEFT", "turn-left"},
                {"TURN_RIGHT", "turn-right"}};
        for (String[] n : names) {
            File folder = replayFolder(prefix + "-" + n[1]);
            if (folder != null && folder.isDirectory()) {
                movements.put(PromptIcon.valueOf(n[0]), ReplayFrameSource.readDirectory(folder));
            }
        }
        ReplayFrameSource source = new ReplayFrameSource(neutral, request.replayFps(), true, runtime.clock(), serial,
                this::showReplayFrame);
        follower = new ReplayPromptFollower(source, neutral, movements, main::postDelayed,
                ReplayPromptFollower.DEFAULT_REACTION_MS);
        Log.i(TAG, "Replay " + prefix + "-main following prompts " + movements.keySet() + " at "
                + request.replayFps() + " fps");
        return source;
    }

    private List<byte[]> readPack(String pack) throws io.mosip.liveness.core.device.DeviceException {
        File folder = replayFolder(pack);
        return folder != null && folder.isDirectory() ? ReplayFrameSource.readDirectory(folder)
                : ReplayFrameSource.readAssets(getAssets(), REPLAY_ASSETS + "/" + pack);
    }

    private File replayFolder(String pack) {
        File external = getExternalFilesDir(REPLAY_ASSETS);
        return external == null ? null : new File(external, pack);
    }

    /**
     * Returns a context whose resources and theme use the given locale. The language comes from the intent, which
     * is only readable from {@code onCreate}, so the layout is inflated with this context instead of changing the
     * activity's own configuration.
     */
    private Context localized(Locale locale) {
        Configuration c = new Configuration(getResources().getConfiguration());
        c.setLocale(locale);
        c.setLayoutDirection(locale);
        ContextThemeWrapper wrapper = new ContextThemeWrapper(this, R.style.Theme_Liveness);
        wrapper.applyOverrideConfiguration(c);
        return wrapper;
    }

    // ------------------------------------------------------------------ rendering

    private void render(LivenessUiState state) {
        if (finishing) {
            return;
        }
        ReplayPromptFollower f = follower;
        if (f != null) {
            f.onPrompt(state.promptIcon());
        }
        String text = messages.text(state.messageKey(), state.args());
        prompt.setText(text);
        if (!text.equals(lastPrompt)) {
            lastPrompt = text;
            prompt.announceForAccessibility(text);
            // State changes in Logcat let tests and demos follow the screen (message key only, no image data).
            Log.i(TAG, "Screen state " + state.phase() + " " + state.messageKey());
        }
        List<String> lines = new ArrayList<>();
        for (String key : state.guidanceKeys()) {
            List<String> args = MessageKeys.RETRY_AVAILABLE.equals(key)
                    ? java.util.Arrays.asList(String.valueOf(state.attempt()), String.valueOf(state.maxAttempts()))
                    : state.args();
            lines.add(messages.text(key, args));
        }
        guidance.setText(String.join("\n", lines));
        guidance.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);
        if (state.stepCount() > 0) {
            step.setText(messages.label(R.string.liveness_ui_step, String.valueOf(state.stepIndex()),
                    String.valueOf(state.stepCount())));
            step.setVisibility(View.VISIBLE);
        } else {
            step.setVisibility(View.GONE);
        }
        int color = ContextCompat.getColor(this, colorOf(state.severity()));
        icon.setImageResource(iconOf(state.promptIcon()));
        icon.setImageTintList(ColorStateList.valueOf(color));
        guide.setSeverity(state.severity());
        guide.setProgress(state.progress());
        progress.setProgress((int) Math.round(state.progress() * 100));
        renderAttempts(state.attempt(), state.maxAttempts(), state.phase());
        boolean running = state.phase() != UiPhase.FAILED && state.phase() != UiPhase.MAX_ATTEMPTS
                && state.phase() != UiPhase.DEVICE_ERROR && state.phase() != UiPhase.CANCELLED
                && state.phase() != UiPhase.SUCCESS;
        if (running) {
            retry.setVisibility(View.GONE);
            settings.setVisibility(View.GONE);
        }
    }

    private void renderAttempts(int attempt, int max, UiPhase phase) {
        attempts.removeAllViews();
        if (max <= 1) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        boolean ended = phase == UiPhase.FAILED || phase == UiPhase.MAX_ATTEMPTS;
        for (int i = 1; i <= max; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (12 * density), (int) (12 * density));
            lp.setMarginEnd((int) (8 * density));
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.liveness_dot);
            boolean used = i < attempt || (ended && i == attempt);
            int tint = used ? R.color.liveness_dot_used
                    : (i == attempt ? R.color.liveness_text : R.color.liveness_dot_free);
            dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, tint)));
            attempts.addView(dot);
        }
        attempts.setContentDescription(messages.label(R.string.liveness_ui_attempts, String.valueOf(attempt),
                String.valueOf(max)));
    }

    private void attemptEnded(LivenessCaptureResult result) {
        lastResult = result;
        if (finishing) {
            return;
        }
        if (result.isPass()) {
            retry.setVisibility(View.GONE);
            cancel.setEnabled(false);
            main.postDelayed(() -> finishWith(Activity.RESULT_OK, result), SUCCESS_DISPLAY_MS);
            return;
        }
        retry.setVisibility(result.isRetryAllowed() ? View.VISIBLE : View.GONE);
        if (result.isRetryAllowed()) {
            retry.requestFocus();
        }
    }

    private void showPermissionError() {
        LivenessUiState state = LivenessUiState.builder(UiPhase.DEVICE_ERROR, MessageKeys.ERROR_CAMERA_PERMISSION)
                .promptIcon(PromptIcon.CAMERA_OFF).severity(Severity.ERROR).retryAvailable(true).build();
        render(state);
        retry.setVisibility(View.VISIBLE);
        settings.setVisibility(View.VISIBLE);
        lastResult = LivenessCaptureResult.builder(request.workflow(), Decision.ERROR)
                .failureReason(FailureReason.DEVICE).messageKey(MessageKeys.ERROR_CAMERA_PERMISSION)
                .reasonCodes(java.util.Collections.singletonList("CAMERA_PERMISSION_DENIED")).build();
    }

    private void showReplayFrame(byte[] jpeg) {
        if (request.isReplayMasked()) {
            return;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap != null) {
            main.post(() -> {
                if (!finishing) {
                    replayView.setImageBitmap(bitmap);
                }
            });
        }
    }

    // ------------------------------------------------------------------ actions

    private void onRetry() {
        retry.setVisibility(View.GONE);
        settings.setVisibility(View.GONE);
        if (needsCameraPermission()) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, PERMISSION_REQUEST);
            return;
        }
        if (lastResult != null && lastResult.reasonCodes().contains("CAMERA_PERMISSION_DENIED")) {
            lastResult = null;
            runner.start();
            return;
        }
        if (!runner.retry()) {
            retry.setVisibility(View.GONE);
        }
    }

    private void cancelCapture() {
        if (runner != null) {
            runner.cancel();
        }
        LivenessCaptureResult r = lastResult != null ? lastResult
                : LivenessCaptureResult.builder(request == null ? io.mosip.liveness.core.Workflow.REGISTRATION
                : request.workflow(), Decision.CANCELLED).failureReason(FailureReason.CANCELLED).build();
        finishWith(Activity.RESULT_CANCELED, r);
    }

    private void finishWith(int resultCode, LivenessCaptureResult result) {
        if (finishing) {
            return;
        }
        finishing = true;
        Log.i(TAG, "Capture screen result " + (resultCode == Activity.RESULT_OK ? "OK" : "CANCELED") + ": "
                + result);
        setResult(resultCode, result.toIntent());
        finish();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    // ------------------------------------------------------------------ helpers

    private boolean needsCameraPermission() {
        return request.source() == LivenessCaptureRequest.Source.CAMERA && !hasCameraPermission();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || cm.getActiveNetwork() == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    static int iconOf(PromptIcon icon) {
        switch (icon) {
            case BLINK:
                return R.drawable.ic_liveness_eye;
            case SMILE:
            case FACE_OVAL:
                return R.drawable.ic_liveness_smile;
            case TURN_LEFT:
            case GAZE_LEFT:
                return R.drawable.ic_liveness_arrow_left;
            case TURN_RIGHT:
            case GAZE_RIGHT:
                return R.drawable.ic_liveness_arrow_right;
            case LOOK_UP:
                return R.drawable.ic_liveness_arrow_up;
            case LOOK_DOWN:
                return R.drawable.ic_liveness_arrow_down;
            case CHECK:
                return R.drawable.ic_liveness_check;
            case HOLD_STILL:
                return R.drawable.ic_liveness_hold;
            case WARNING:
                return R.drawable.ic_liveness_warning;
            case ERROR:
                return R.drawable.ic_liveness_error;
            case CAMERA_OFF:
                return R.drawable.ic_liveness_camera_off;
            case LOCKED:
                return R.drawable.ic_liveness_lock;
            default:
                return R.drawable.ic_liveness_progress;
        }
    }

    static int colorOf(Severity severity) {
        switch (severity) {
            case SUCCESS:
                return R.color.liveness_success;
            case WARNING:
                return R.color.liveness_warning;
            case ERROR:
                return R.color.liveness_error;
            default:
                return R.color.liveness_info;
        }
    }
}
