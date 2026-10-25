/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
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
 *
 * <p>Retry limit across screens (PS04 task 5): the liveness attempts a user used are kept per workflow and user ID
 * and passed to the next capture screen as prior attempts, so closing the screen and opening face authentication
 * again continues the count ("Attempt 3 of 3") instead of starting over. The count is kept whatever the screen's
 * last result was (a cancellation after counted failures keeps them) and is cleared by a liveness pass. When the
 * screen reports the configured block ({@code on_max_failure}):
 * <ul>
 *   <li>{@code BLOCK}: face authentication of that user in that workflow stays refused without opening the camera
 *       (the workflow's other authentication methods still apply);</li>
 *   <li>{@code BLOCK_AND_LOCK}: the account is locked by {@code FaceAuthenticator}, the precheck refuses the user
 *       while the lock lasts, and the count starts again after it. When the lock did not take (ARC's invalid-login
 *       limit unset), the block of {@code BLOCK} applies instead.</li>
 * </ul>
 * With {@link #persistTo} the counts and blocks are saved ({@link SavedAttempts}), so an app restart keeps them for
 * up to 12 hours.
 * This applies to operator and supervisor face authentication alike (sign-in, packet, exception, end of day and the
 * supervisor of a resident override).
 *
 * <p>With the attempts, the next screen also receives whether an earlier screen saw a suspected attack (counted PAD,
 * frame integrity or binding failure) and the uncounted dim-light results since the last counted failure, so
 * closing and reopening the screen gives no new "Improve lighting" tries ({@code passive.low_light_max_uncounted}).
 * A counted failure resets the dim-light count; a liveness pass clears everything.
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
    private final Map<String, AttemptCount> attempts = new HashMap<>();
    private SavedAttempts saved;

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
     * Reads the saved counts and saves every later change.
     *
     * @param saved where the counts are saved
     */
    public synchronized void persistTo(SavedAttempts saved) {
        this.saved = Objects.requireNonNull(saved, "saved");
        for (Map.Entry<String, String> e : saved.load(System.currentTimeMillis()).entrySet()) {
            AttemptCount count = AttemptCount.parse(e.getValue());
            if (count == null) {
                // Fail closed: an edited or corrupt entry blocks the user (for the keep period) instead of resetting.
                count = new AttemptCount(1, 1, true, false, 0);
                saved.put(e.getKey(), count.format(), System.currentTimeMillis());
            }
            attempts.put(e.getKey(), count);
        }
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
        FaceAuthOutcome blocked = blockedOutcome(userId, workflow);
        if (blocked != null) {
            Log.i(TAG, "Face authentication not started: attempts used up for " + workflow.id());
            done.accept(blocked);
            return;
        }
        boolean started = launcher.launch(nextRequest(userId, workflow), result -> {
            // Counted before the match, so a screen opened right after this one continues the count.
            recordAttempts(userId, workflow, result);
            matcher.execute(() -> finish(userId, workflow, result, done));
        });
        if (!started) {
            done.accept(FaceAuthOutcome.early(workflow, FaceAuthStatus.BUSY, null));
        }
    }

    private void finish(String userId, Workflow workflow, LivenessCaptureResult result,
                        Consumer<FaceAuthOutcome> done) {
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
        if (result.isLockRequired() && !authenticator.isLocked(userId)) {
            // No account lock took effect: without this block the attempts would never run out.
            keepBlocked(userId, workflow, result);
        }
        FaceAuthOutcome finalOutcome = outcome;
        main.post(() -> done.accept(finalOutcome));
    }

    /**
     * Returns the liveness attempts a user already used for a workflow on earlier capture screens.
     *
     * @param userId   user ID
     * @param workflow authentication workflow
     * @return counted failed attempts since the last liveness pass, 0 or more
     */
    public synchronized int usedAttempts(String userId, Workflow workflow) {
        AttemptCount count = attempts.get(attemptKey(userId, workflow));
        return count == null ? 0 : count.used;
    }

    /**
     * Tells whether an earlier capture screen of the user in the workflow saw a suspected attack since the last
     * liveness pass.
     *
     * @param userId   user ID
     * @param workflow authentication workflow
     * @return {@code true} after a counted PAD, frame integrity or binding failure
     */
    public synchronized boolean isSuspectedAttack(String userId, Workflow workflow) {
        AttemptCount count = attempts.get(attemptKey(userId, workflow));
        return count != null && count.attack;
    }

    /**
     * Returns the uncounted dim-light results of the user in the workflow since the last counted failure.
     *
     * @param userId   user ID
     * @param workflow authentication workflow
     * @return 0 or more
     */
    public synchronized int lowLightRetries(String userId, Workflow workflow) {
        AttemptCount count = attempts.get(attemptKey(userId, workflow));
        return count == null ? 0 : count.lowLight;
    }

    /** Starts the next capture screen's request with the state of the user's earlier screens in the workflow. */
    synchronized LivenessCaptureRequest.Builder nextRequest(String userId, Workflow workflow) {
        AttemptCount count = attempts.get(attemptKey(userId, workflow));
        LivenessCaptureRequest.Builder request = LivenessCaptureRequest.builder(workflow, userId);
        if (count != null) {
            request.priorAttempts(count.used).priorSuspectedAttack(count.attack).priorLowLight(count.lowLight);
        }
        return request;
    }

    /**
     * Keeps the attempts, the suspected-attack mark and the dim-light count the capture screen reported. A liveness
     * pass clears them; a block with {@code BLOCK_AND_LOCK} leaves the user to the account lock and clears them too.
     * A counted failure resets the dim-light count; a screen that counted a failure of its own restarted that count,
     * so its number replaces the stored one; otherwise the higher of the two is kept.
     */
    synchronized void recordAttempts(String userId, Workflow workflow, LivenessCaptureResult result) {
        String key = attemptKey(userId, workflow);
        if (result.isPass() || result.isLockRequired()) {
            store(key, null);
            return;
        }
        AttemptCount stored = attempts.get(key);
        int used = Math.max(stored == null ? 0 : stored.used, result.attemptsUsed());
        int max = Math.max(stored == null ? 0 : stored.max, result.maxAttempts());
        FailureReason reason = result.failureReason();
        boolean counts = reason != null && reason.countsAsAttempt();
        boolean blocked = (stored != null && stored.blocked)
                || (counts && (!result.isRetryAllowed() || (max > 0 && used >= max)));
        boolean attack = (stored != null && stored.attack) || (counts && !reason.allowsOverride());
        int storedUsed = stored == null ? 0 : stored.used;
        int storedLowLight = stored == null ? 0 : stored.lowLight;
        int lowLight = counts || blocked ? 0
                : result.attemptsUsed() > storedUsed ? result.lowLightRetries()
                : Math.max(storedLowLight, result.lowLightRetries());
        store(key, used > 0 || blocked || attack || lowLight > 0
                ? new AttemptCount(blocked ? Math.max(used, max) : used, max, blocked, attack, lowLight) : null);
    }

    private synchronized void keepBlocked(String userId, Workflow workflow, LivenessCaptureResult result) {
        int max = Math.max(result.maxAttempts(), result.attemptsUsed());
        FailureReason reason = result.failureReason();
        store(attemptKey(userId, workflow),
                new AttemptCount(max, max, true, reason != null && !reason.allowsOverride(), 0));
    }

    /** Keeps one count in memory and in the saved set; {@code null} removes it. */
    private void store(String key, AttemptCount count) {
        if (count == null) {
            attempts.remove(key);
        } else {
            attempts.put(key, count);
        }
        if (saved != null) {
            if (count == null) {
                saved.remove(key);
            } else {
                saved.put(key, count.format(), System.currentTimeMillis());
            }
        }
    }

    private synchronized FaceAuthOutcome blockedOutcome(String userId, Workflow workflow) {
        AttemptCount count = attempts.get(attemptKey(userId, workflow));
        if (count == null || !count.blocked) {
            return null;
        }
        return new FaceAuthOutcome(workflow, FaceAuthStatus.MAX_ATTEMPTS, MessageKeys.RETRY_MAX_AUTH, count.used,
                count.max);
    }

    private static String attemptKey(String userId, Workflow workflow) {
        return workflow.id() + '|' + normalise(userId);
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

    /** Liveness attempts of one user in one workflow. */
    private static final class AttemptCount {
        final int used;
        final int max;
        final boolean blocked;
        final boolean attack;
        final int lowLight;

        AttemptCount(int used, int max, boolean blocked, boolean attack, int lowLight) {
            this.used = used;
            this.max = max;
            this.blocked = blocked;
            this.attack = attack;
            this.lowLight = lowLight;
        }

        String format() {
            return used + "," + max + "," + blocked + "," + attack + "," + lowLight;
        }

        static AttemptCount parse(String text) {
            try {
                String[] p = text.split(",", -1);
                return new AttemptCount(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Boolean.parseBoolean(p[2]),
                        Boolean.parseBoolean(p[3]), Integer.parseInt(p[4]));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
