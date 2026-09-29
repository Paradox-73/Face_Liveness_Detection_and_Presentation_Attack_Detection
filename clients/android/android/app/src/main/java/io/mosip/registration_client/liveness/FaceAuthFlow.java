package io.mosip.registration_client.liveness;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.liveness.core.Workflow;
import io.mosip.registration.livenessmanager.auth.FaceAuthOutcome;
import io.mosip.registration.livenessmanager.auth.FaceAuthStatus;
import io.mosip.registration.livenessmanager.auth.FaceAuthenticator;

/**
 * One face authentication from the Flutter screens: checks that need no camera, the capture screen (liveness),
 * then the match, which runs off the main thread. The outcome is delivered on the main thread.
 *
 * <p>Sign-in: a successful {@code login_auth} leaves a one-time grant for that user, valid
 * {@value #LOGIN_GRANT_MS} ms, which {@code AuthenticationApi.loginWithFace} consumes to open the session. The
 * session therefore opens only after this flow verified the face in this process.
 */
@Singleton
public class FaceAuthFlow {

    /** Validity of a sign-in grant. */
    static final long LOGIN_GRANT_MS = 60_000L;

    private static final String TAG = "Liveness";

    private final FaceAuthenticator authenticator;
    private final LivenessCaptureLauncher launcher;
    private final ExecutorService matcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "liveness-face-match");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Long> loginGrants = new HashMap<>();

    /**
     * Creates the flow.
     *
     * @param authenticator liveness-then-match rules
     * @param launcher      capture screen launcher
     */
    @Inject
    public FaceAuthFlow(FaceAuthenticator authenticator, LivenessCaptureLauncher launcher) {
        this.authenticator = authenticator;
        this.launcher = launcher;
    }

    /**
     * Tells whether face authentication can be offered.
     *
     * @param userId   user ID
     * @param workflow authentication workflow
     * @return {@code true} when the capture would start
     */
    public boolean isAvailable(String userId, Workflow workflow) {
        return authenticator.isAvailable(userId, workflow);
    }

    /**
     * Runs one face authentication.
     *
     * @param userId   the user to verify
     * @param workflow authentication workflow
     * @param done     receives the outcome on the main thread
     */
    public void authenticate(String userId, Workflow workflow, Consumer<FaceAuthOutcome> done) {
        FaceAuthOutcome refused = authenticator.precheck(userId, workflow);
        if (refused != null) {
            Log.i(TAG, "Face authentication not started: " + refused);
            done.accept(refused);
            return;
        }
        boolean started = launcher.launch(workflow, userId, result -> matcher.execute(() -> {
            FaceAuthOutcome outcome;
            try {
                outcome = authenticator.complete(userId, workflow, result);
            } catch (RuntimeException e) {
                Log.e(TAG, "Face authentication failed", e);
                outcome = FaceAuthOutcome.early(workflow, FaceAuthStatus.ENGINE_UNAVAILABLE, null);
            }
            if (outcome.isSuccess() && workflow == Workflow.LOGIN_AUTH) {
                grantLogin(userId);
            }
            FaceAuthOutcome finalOutcome = outcome;
            main.post(() -> done.accept(finalOutcome));
        }));
        if (!started) {
            done.accept(FaceAuthOutcome.early(workflow, FaceAuthStatus.BUSY, null));
        }
    }

    /**
     * Consumes the sign-in grant of a user.
     *
     * @param userId user ID
     * @return {@code true} when a face sign-in of this user succeeded less than {@value #LOGIN_GRANT_MS} ms ago
     *         and was not used yet
     */
    public synchronized boolean consumeLoginGrant(String userId) {
        Long granted = loginGrants.remove(normalise(userId));
        return granted != null && System.currentTimeMillis() - granted <= LOGIN_GRANT_MS;
    }

    private synchronized void grantLogin(String userId) {
        loginGrants.put(normalise(userId), System.currentTimeMillis());
    }

    private static String normalise(String userId) {
        return userId == null ? "" : userId.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
