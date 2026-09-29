package io.mosip.registration.livenessmanager;

import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.liveness.core.config.OnMaxFailure;

/**
 * The outcome of the capture screen, returned to the caller as {@link Intent} extras
 * ({@code setResult}). Every path sets one, including cancellation and errors before the first session, so the
 * waiting ARC call always completes.
 *
 * <p>On a pass it carries the signed face capture (ISO/IEC 19794-5 record and the SBI 0.9.5 {@code rCapture}
 * response for ARC's {@code Biometrics095Service.handleRCaptureResponse}) and the packet evidence map
 * ({@link PacketEvidenceKeys}, for the BIR {@code others} entries). No scores are carried except the evidence
 * bucket values the packet stores anyway.
 */
public final class LivenessCaptureResult {

    static final String EXTRA_DECISION = "liveness.result.decision";
    static final String EXTRA_FAILURE = "liveness.result.failure";
    static final String EXTRA_REASONS = "liveness.result.reasons";
    static final String EXTRA_MESSAGE = "liveness.result.message";
    static final String EXTRA_WORKFLOW = "liveness.result.workflow";
    static final String EXTRA_SESSION = "liveness.result.session";
    static final String EXTRA_ATTEMPTS = "liveness.result.attempts";
    static final String EXTRA_MAX_ATTEMPTS = "liveness.result.max_attempts";
    static final String EXTRA_RETRY = "liveness.result.retry_allowed";
    static final String EXTRA_OVERRIDE = "liveness.result.override_allowed";
    static final String EXTRA_LOCK = "liveness.result.lock_required";
    static final String EXTRA_ON_MAX = "liveness.result.on_max_failure";
    static final String EXTRA_TOKEN_NONCE = "liveness.result.token_nonce";
    static final String EXTRA_TRANSACTION = "liveness.result.transaction";
    static final String EXTRA_ISO = "liveness.result.face_iso";
    static final String EXTRA_RCAPTURE = "liveness.result.rcapture";
    static final String EXTRA_PROVISIONED = "liveness.result.device_provisioned";
    static final String EXTRA_EVIDENCE = "liveness.result.evidence";

    private final Decision decision;
    private final FailureReason failureReason;
    private final List<String> reasonCodes;
    private final String messageKey;
    private final Workflow workflow;
    private final String sessionId;
    private final int attemptsUsed;
    private final int maxAttempts;
    private final boolean retryAllowed;
    private final boolean overrideAllowed;
    private final boolean lockRequired;
    private final OnMaxFailure onMaxFailure;
    private final String tokenNonce;
    private final String transactionId;
    private final byte[] faceIso;
    private final String rCaptureResponse;
    private final boolean deviceProvisioned;
    private final Map<String, String> evidence;

    private LivenessCaptureResult(Builder b) {
        this.decision = b.decision;
        this.failureReason = b.failureReason;
        this.reasonCodes = Collections.unmodifiableList(new ArrayList<>(b.reasonCodes));
        this.messageKey = b.messageKey;
        this.workflow = b.workflow;
        this.sessionId = b.sessionId;
        this.attemptsUsed = b.attemptsUsed;
        this.maxAttempts = b.maxAttempts;
        this.retryAllowed = b.retryAllowed;
        this.overrideAllowed = b.overrideAllowed;
        this.lockRequired = b.lockRequired;
        this.onMaxFailure = b.onMaxFailure;
        this.tokenNonce = b.tokenNonce;
        this.transactionId = b.transactionId;
        this.faceIso = b.faceIso;
        this.rCaptureResponse = b.rCaptureResponse;
        this.deviceProvisioned = b.deviceProvisioned;
        this.evidence = Collections.unmodifiableMap(new LinkedHashMap<>(b.evidence));
    }

    /**
     * Starts a result.
     *
     * @param workflow the workflow
     * @param decision the decision
     * @return a builder
     */
    public static Builder builder(Workflow workflow, Decision decision) {
        return new Builder(workflow, decision);
    }

    /**
     * Reads a result from the screen's result intent.
     *
     * @param data the intent returned to {@code onActivityResult}; {@code null} means the screen ended without a
     *             result (for example the process was killed)
     * @return the result; {@link Decision#CANCELLED} when no result was set
     */
    public static LivenessCaptureResult fromIntent(Intent data) {
        Bundle x = data == null ? null : data.getExtras();
        if (x == null || !x.containsKey(EXTRA_DECISION)) {
            return builder(Workflow.REGISTRATION, Decision.CANCELLED).failureReason(FailureReason.CANCELLED)
                    .build();
        }
        Workflow workflow = Workflow.fromId(x.getString(EXTRA_WORKFLOW, "")).orElse(Workflow.REGISTRATION);
        Builder b = builder(workflow, Decision.valueOf(x.getString(EXTRA_DECISION)));
        String failure = x.getString(EXTRA_FAILURE);
        if (failure != null) {
            b.failureReason(FailureReason.valueOf(failure));
        }
        ArrayList<String> reasons = x.getStringArrayList(EXTRA_REASONS);
        if (reasons != null) {
            b.reasonCodes(reasons);
        }
        b.messageKey(x.getString(EXTRA_MESSAGE)).sessionId(x.getString(EXTRA_SESSION))
                .attempts(x.getInt(EXTRA_ATTEMPTS), x.getInt(EXTRA_MAX_ATTEMPTS))
                .retryAllowed(x.getBoolean(EXTRA_RETRY)).overrideAllowed(x.getBoolean(EXTRA_OVERRIDE))
                .lockRequired(x.getBoolean(EXTRA_LOCK))
                .capture(x.getString(EXTRA_TRANSACTION), x.getByteArray(EXTRA_ISO), x.getString(EXTRA_RCAPTURE),
                        x.getBoolean(EXTRA_PROVISIONED))
                .tokenNonce(x.getString(EXTRA_TOKEN_NONCE));
        String onMax = x.getString(EXTRA_ON_MAX);
        if (onMax != null) {
            b.onMaxFailure(OnMaxFailure.valueOf(onMax));
        }
        Bundle ev = x.getBundle(EXTRA_EVIDENCE);
        if (ev != null) {
            for (String k : ev.keySet()) {
                b.evidence(k, ev.getString(k));
            }
        }
        return b.build();
    }

    /**
     * Writes this result into an intent for {@code setResult}.
     *
     * @return a new intent with the extras
     */
    public Intent toIntent() {
        Intent i = new Intent();
        i.putExtra(EXTRA_DECISION, decision.name());
        if (failureReason != null) {
            i.putExtra(EXTRA_FAILURE, failureReason.name());
        }
        i.putStringArrayListExtra(EXTRA_REASONS, new ArrayList<>(reasonCodes));
        i.putExtra(EXTRA_MESSAGE, messageKey);
        i.putExtra(EXTRA_WORKFLOW, workflow.id());
        i.putExtra(EXTRA_SESSION, sessionId);
        i.putExtra(EXTRA_ATTEMPTS, attemptsUsed);
        i.putExtra(EXTRA_MAX_ATTEMPTS, maxAttempts);
        i.putExtra(EXTRA_RETRY, retryAllowed);
        i.putExtra(EXTRA_OVERRIDE, overrideAllowed);
        i.putExtra(EXTRA_LOCK, lockRequired);
        if (onMaxFailure != null) {
            i.putExtra(EXTRA_ON_MAX, onMaxFailure.name());
        }
        i.putExtra(EXTRA_TOKEN_NONCE, tokenNonce);
        i.putExtra(EXTRA_TRANSACTION, transactionId);
        i.putExtra(EXTRA_ISO, faceIso);
        i.putExtra(EXTRA_RCAPTURE, rCaptureResponse);
        i.putExtra(EXTRA_PROVISIONED, deviceProvisioned);
        Bundle ev = new Bundle();
        for (Map.Entry<String, String> e : evidence.entrySet()) {
            ev.putString(e.getKey(), e.getValue());
        }
        i.putExtra(EXTRA_EVIDENCE, ev);
        return i;
    }

    /**
     * Returns the decision.
     *
     * @return {@code PASS} only when liveness passed and the face was captured and bound to the session
     */
    public Decision decision() {
        return decision;
    }

    /**
     * Tells whether liveness passed.
     *
     * @return {@code true} for {@link Decision#PASS}
     */
    public boolean isPass() {
        return decision == Decision.PASS;
    }

    /**
     * Returns why the last attempt failed.
     *
     * @return the reason, or {@code null} on a pass
     */
    public FailureReason failureReason() {
        return failureReason;
    }

    /**
     * Returns the reason codes of the last attempt (for example {@code PAD_SCORE}, {@code FROZEN}).
     *
     * @return reason code names
     */
    public List<String> reasonCodes() {
        return reasonCodes;
    }

    /**
     * Returns the message key of the last state shown.
     *
     * @return a {@code liveness.*} key, or {@code null}
     */
    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns the workflow.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return workflow;
    }

    /**
     * Returns the ID of the last session.
     *
     * @return the session ID, or {@code null} if no session ran
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns the attempts used (failures that count, see {@code FailureReason.countsAsAttempt()}).
     *
     * @return attempts used
     */
    public int attemptsUsed() {
        return attemptsUsed;
    }

    /**
     * Returns the attempt limit of the policy.
     *
     * @return {@code max_retries}
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Tells whether another attempt was allowed when the screen closed.
     *
     * @return {@code true} if the user cancelled while a retry was still possible
     */
    public boolean isRetryAllowed() {
        return retryAllowed;
    }

    /**
     * Tells whether a supervisor override may accept the face ({@code SUPERVISOR_OVERRIDE}, resident workflows,
     * never after a suspected attack).
     *
     * @return {@code true} if the caller may offer the override
     */
    public boolean isOverrideAllowed() {
        return overrideAllowed;
    }

    /**
     * Tells whether the user must be locked out ({@code BLOCK_AND_LOCK} after the last attempt of an
     * authentication workflow).
     *
     * @return {@code true} if the caller must apply the invalid-login lock
     */
    public boolean isLockRequired() {
        return lockRequired;
    }

    /**
     * Returns the behaviour after the last attempt.
     *
     * @return the policy value, or {@code null} if no policy was resolved
     */
    public OnMaxFailure onMaxFailure() {
        return onMaxFailure;
    }

    /**
     * Returns the nonce of the pass token the capture was bound to.
     *
     * @return the nonce on a pass, else {@code null}
     */
    public String tokenNonce() {
        return tokenNonce;
    }

    /**
     * Returns the SBI transaction ID of the capture.
     *
     * @return the transaction ID on a pass, else {@code null}
     */
    public String transactionId() {
        return transactionId;
    }

    /**
     * Returns the captured face as an ISO/IEC 19794-5 record (JPEG 2000 image).
     *
     * @return the record on a pass, else {@code null}; not copied
     */
    public byte[] faceIso() {
        return faceIso;
    }

    /**
     * Returns the signed SBI 0.9.5 {@code rCapture} response.
     *
     * @return the JSON on a pass, else {@code null}
     */
    public String rCaptureResponse() {
        return rCaptureResponse;
    }

    /**
     * Tells whether the built-in device key has an issued DEVICE certificate. Without one ARC's SBI trust check
     * rejects the capture.
     *
     * @return {@code true} if provisioned
     */
    public boolean isDeviceProvisioned() {
        return deviceProvisioned;
    }

    /**
     * Returns the packet evidence ({@code LIVENESS_*} keys of {@link PacketEvidenceKeys}).
     *
     * @return key-value pairs; empty unless a session ended
     */
    public Map<String, String> evidence() {
        return evidence;
    }

    @Override
    public String toString() {
        return "LivenessCaptureResult[" + workflow.id() + ", " + decision
                + (failureReason != null ? "/" + failureReason : "") + ", reasons=" + reasonCodes
                + ", attempts=" + attemptsUsed + "/" + maxAttempts + ", capture=" + (faceIso != null) + "]";
    }

    /** Builder of {@link LivenessCaptureResult}. */
    public static final class Builder {

        private final Workflow workflow;
        private final Decision decision;
        private FailureReason failureReason;
        private final List<String> reasonCodes = new ArrayList<>();
        private String messageKey;
        private String sessionId;
        private int attemptsUsed;
        private int maxAttempts;
        private boolean retryAllowed;
        private boolean overrideAllowed;
        private boolean lockRequired;
        private OnMaxFailure onMaxFailure;
        private String tokenNonce;
        private String transactionId;
        private byte[] faceIso;
        private String rCaptureResponse;
        private boolean deviceProvisioned;
        private final Map<String, String> evidence = new LinkedHashMap<>();

        private Builder(Workflow workflow, Decision decision) {
            this.workflow = Objects.requireNonNull(workflow, "workflow");
            this.decision = Objects.requireNonNull(decision, "decision");
        }

        /**
         * Sets the failure reason.
         *
         * @param reason the reason
         * @return this builder
         */
        public Builder failureReason(FailureReason reason) {
            this.failureReason = reason;
            return this;
        }

        /**
         * Adds reason codes.
         *
         * @param codes reason code names
         * @return this builder
         */
        public Builder reasonCodes(List<String> codes) {
            reasonCodes.addAll(codes);
            return this;
        }

        /**
         * Sets the message key of the last state.
         *
         * @param key message key
         * @return this builder
         */
        public Builder messageKey(String key) {
            this.messageKey = key;
            return this;
        }

        /**
         * Sets the session ID.
         *
         * @param id session ID
         * @return this builder
         */
        public Builder sessionId(String id) {
            this.sessionId = id;
            return this;
        }

        /**
         * Sets the attempt counters.
         *
         * @param used attempts used
         * @param max  attempt limit
         * @return this builder
         */
        public Builder attempts(int used, int max) {
            this.attemptsUsed = used;
            this.maxAttempts = max;
            return this;
        }

        /**
         * Sets whether a retry is allowed.
         *
         * @param allowed whether a retry is allowed
         * @return this builder
         */
        public Builder retryAllowed(boolean allowed) {
            this.retryAllowed = allowed;
            return this;
        }

        /**
         * Sets whether a supervisor override is allowed.
         *
         * @param allowed whether an override is allowed
         * @return this builder
         */
        public Builder overrideAllowed(boolean allowed) {
            this.overrideAllowed = allowed;
            return this;
        }

        /**
         * Sets whether the user must be locked.
         *
         * @param required whether a lock is required
         * @return this builder
         */
        public Builder lockRequired(boolean required) {
            this.lockRequired = required;
            return this;
        }

        /**
         * Sets the behaviour after the last attempt.
         *
         * @param value policy value
         * @return this builder
         */
        public Builder onMaxFailure(OnMaxFailure value) {
            this.onMaxFailure = value;
            return this;
        }

        /**
         * Sets the pass-token nonce.
         *
         * @param nonce the nonce
         * @return this builder
         */
        public Builder tokenNonce(String nonce) {
            this.tokenNonce = nonce;
            return this;
        }

        /**
         * Sets the signed capture.
         *
         * @param transactionId    SBI transaction ID
         * @param faceIso          ISO/IEC 19794-5 record
         * @param rCaptureResponse SBI response JSON
         * @param provisioned      whether the device key has an issued certificate
         * @return this builder
         */
        public Builder capture(String transactionId, byte[] faceIso, String rCaptureResponse, boolean provisioned) {
            this.transactionId = transactionId;
            this.faceIso = faceIso;
            this.rCaptureResponse = rCaptureResponse;
            this.deviceProvisioned = provisioned;
            return this;
        }

        /**
         * Adds one packet evidence entry.
         *
         * @param key   evidence key
         * @param value value
         * @return this builder
         */
        public Builder evidence(String key, String value) {
            if (key != null && value != null) {
                evidence.put(key, value);
            }
            return this;
        }

        /**
         * Adds packet evidence entries.
         *
         * @param values key-value pairs
         * @return this builder
         */
        public Builder evidence(Map<String, String> values) {
            values.forEach(this::evidence);
            return this;
        }

        /**
         * Builds the result.
         *
         * @return the result
         */
        public LivenessCaptureResult build() {
            return new LivenessCaptureResult(this);
        }
    }
}
