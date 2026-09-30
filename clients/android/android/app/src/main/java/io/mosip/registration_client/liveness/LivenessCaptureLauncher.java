package io.mosip.registration_client.liveness;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.Log;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.liveness.core.Workflow;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;
import io.mosip.registration_client.BuildConfig;

/**
 * Opens the native liveness capture screen from ARC's single {@code MainActivity} and hands its result back to
 * whoever asked (resident face capture, operator onboarding, face authentication).
 *
 * <p>One capture at a time: a second request while the screen is open is refused. {@code MainActivity} forwards
 * {@code onActivityResult} with {@link LivenessService#REQUEST_CODE} to {@link #onActivityResult}; every result,
 * including a cancellation or a missing result, reaches the callback, so a waiting Flutter call always completes.
 *
 * <p>Debug builds only: when {@value DebugReplay#SOURCE_FILE} exists in the app's external files folder
 * {@code liveness-replay/}, the screen replays that frame pack instead of opening the camera (automated tests and
 * demos never use the live camera). Release builds ignore it.
 */
@Singleton
public class LivenessCaptureLauncher {

    /** Receives the capture screen's result. */
    public interface Callback {
        /**
         * Called on the main thread with the result.
         *
         * @param result the result; never {@code null}
         */
        void onResult(LivenessCaptureResult result);
    }

    private static final String TAG = "Liveness";

    private final LivenessService liveness;
    private Activity activity;
    private String language = "eng";
    private Callback pending;

    /**
     * Creates the launcher.
     *
     * @param liveness the liveness facade
     */
    @Inject
    public LivenessCaptureLauncher(LivenessService liveness) {
        this.liveness = liveness;
    }

    /**
     * Sets the activity that starts the capture screen and receives its result.
     *
     * @param activity ARC's main activity
     */
    public synchronized void attach(Activity activity) {
        this.activity = activity;
    }

    /**
     * Sets the language of the capture screen.
     *
     * @param code ARC language code, for example {@code eng} or {@code ara}; blank keeps the current one
     */
    public synchronized void setLanguage(String code) {
        if (code != null && !code.trim().isEmpty()) {
            this.language = code.trim();
        }
    }

    /**
     * Returns the language of the capture screen.
     *
     * @return ARC language code
     */
    public synchronized String language() {
        return language;
    }

    /**
     * Opens the capture screen.
     *
     * @param workflow   the workflow
     * @param subjectRef registration ID or user ID
     * @param callback   receives the result
     * @return {@code false} when no activity is attached or another capture is open (nothing started)
     */
    public synchronized boolean launch(Workflow workflow, String subjectRef, Callback callback) {
        return launch(workflow, subjectRef, false, 0, callback);
    }

    /**
     * Opens the capture screen.
     *
     * @param workflow     the workflow
     * @param subjectRef   registration ID or user ID
     * @param photoWhenOff take a signed photo without liveness when the workflow's policy is {@code OFF} (the
     *                     exception photo) instead of closing at once
     * @param priorAttempts attempts the subject already used for this capture on earlier screens
     * @param callback     receives the result
     * @return {@code false} when no activity is attached or another capture is open (nothing started)
     */
    public synchronized boolean launch(Workflow workflow, String subjectRef, boolean photoWhenOff,
                                       int priorAttempts, Callback callback) {
        if (activity == null || pending != null) {
            Log.w(TAG, "Capture screen not opened: " + (activity == null ? "no activity" : "another capture is open"));
            return false;
        }
        LivenessCaptureRequest.Builder request = LivenessCaptureRequest.builder(workflow, subjectRef)
                .language(language).photoWhenOff(photoWhenOff).priorAttempts(priorAttempts);
        if (BuildConfig.DEBUG) {
            DebugReplay.apply(activity, request);
        }
        pending = callback;
        try {
            activity.startActivityForResult(liveness.newCaptureIntent(activity, request.build()),
                    LivenessService.REQUEST_CODE);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            pending = null;
            Log.e(TAG, "Capture screen could not be opened", e);
            return false;
        }
    }

    /**
     * Tells whether a capture screen is open.
     *
     * @return {@code true} while waiting for a result
     */
    public synchronized boolean isBusy() {
        return pending != null;
    }

    /**
     * Delivers the capture screen's result.
     *
     * @param requestCode request code of {@code onActivityResult}
     * @param resultCode  result code
     * @param data        result intent, may be {@code null}
     * @return {@code true} when the request code was the liveness one (handled here)
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != LivenessService.REQUEST_CODE) {
            return false;
        }
        Callback callback;
        synchronized (this) {
            callback = pending;
            pending = null;
        }
        LivenessCaptureResult result = liveness.parseResult(resultCode, data);
        Log.i(TAG, "Capture screen returned " + result);
        if (callback != null) {
            callback.onResult(result);
        }
        return true;
    }
}
