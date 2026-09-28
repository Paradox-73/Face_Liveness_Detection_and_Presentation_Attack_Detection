package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.ChallengeEchoStatus;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceErrorCode;

import java.util.Objects;

/**
 * Builds the payload of each liveness audit event (design section 4.17), so both clients write the same
 * attributes. Payloads carry decisions, 10-point score buckets, reason codes, challenge types, timings, device
 * certification and engine or model versions; never images, embeddings, raw scores or nonces (only a SHA-256
 * prefix of a nonce).
 */
public final class AuditPayloadBuilder {

    private final Workflow workflow;
    private final String sessionId;
    private final Clock clock;

    /**
     * Creates a builder for one session.
     *
     * @param workflow  workflow of the session
     * @param sessionId session identifier; empty for events outside a session
     * @param clock     clock for the event time
     */
    public AuditPayloadBuilder(Workflow workflow, String sessionId, Clock clock) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private LivenessAuditEvent.Builder event(AuditEventId id) {
        return LivenessAuditEvent.builder(id, workflow, sessionId, clock.now());
    }

    /**
     * LV01: the session opened.
     *
     * @param attempt 1-based attempt
     * @param device  streaming device
     * @param engine  engine running the session
     * @return the event
     */
    public LivenessAuditEvent sessionStarted(int attempt, DeviceDescriptor device, EngineInfo engine) {
        return event(AuditEventId.LIVENESS_SESSION_STARTED).put(AuditAttributes.ATTEMPT, attempt)
                .put(AuditAttributes.DEVICE_SERIAL, truncate(device.serialNumber()))
                .put(AuditAttributes.CERTIFICATION, device.certification().code())
                .put(AuditAttributes.ENGINE, truncate(engine.tag())).build();
    }

    /**
     * LV02: the passive check accepted the face.
     *
     * @param score  passive score 0-100 (written as a bucket)
     * @param frames frames used
     * @param ms     time of acquisition and passive phase
     * @return the event
     */
    public LivenessAuditEvent passivePassed(int score, int frames, long ms) {
        return event(AuditEventId.LIVENESS_PASSIVE_PASSED).put(AuditAttributes.SCORE_BUCKET, AuditAttributes.bucket(score))
                .put(AuditAttributes.FRAMES, frames).put(AuditAttributes.ELAPSED_MS, ms).build();
    }

    /**
     * LV03: challenges started.
     *
     * @param score  passive score 0-100 (written as a bucket)
     * @param reason {@code BELOW_THRESHOLD} or {@code POLICY}
     * @return the event
     */
    public LivenessAuditEvent activeTriggered(int score, ReasonCode reason) {
        return event(AuditEventId.LIVENESS_ACTIVE_TRIGGERED).put(AuditAttributes.SCORE_BUCKET, AuditAttributes.bucket(score))
                .put(AuditAttributes.REASON, reason.name()).build();
    }

    /**
     * LV04: a challenge prompt was shown.
     *
     * @param type  challenge type
     * @param index 0-based index
     * @param nonce random value drawn for this prompt; only its SHA-256 prefix is written
     * @return the event
     */
    public LivenessAuditEvent challengeIssued(ChallengeType type, int index, String nonce) {
        return event(AuditEventId.LIVENESS_CHALLENGE_ISSUED).put(AuditAttributes.CHALLENGE_TYPE, type.name())
                .put(AuditAttributes.CHALLENGE_INDEX, index)
                .put(AuditAttributes.NONCE_PREFIX, FrameFingerprint.sha256Prefix(nonce)).build();
    }

    /**
     * LV05: a challenge action was verified.
     *
     * @param type    challenge type
     * @param onsetMs prompt-to-onset time
     * @param holdMs  hold time
     * @return the event
     */
    public LivenessAuditEvent challengePassed(ChallengeType type, long onsetMs, long holdMs) {
        return event(AuditEventId.LIVENESS_CHALLENGE_PASSED).put(AuditAttributes.CHALLENGE_TYPE, type.name())
                .put(AuditAttributes.ONSET_MS, onsetMs).put(AuditAttributes.HOLD_MS, holdMs).build();
    }

    /**
     * LV06: a challenge failed.
     *
     * @param type   challenge type
     * @param reason reason code, for example {@code WRONG_ACTION}
     * @return the event
     */
    public LivenessAuditEvent challengeFailed(ChallengeType type, ReasonCode reason) {
        return event(AuditEventId.LIVENESS_CHALLENGE_FAILED).put(AuditAttributes.CHALLENGE_TYPE, type.name())
                .put(AuditAttributes.REASON, reason.name()).build();
    }

    /**
     * LV07: a phase timed out.
     *
     * @param phase state in which the timeout happened
     * @return the event
     */
    public LivenessAuditEvent timeout(SessionState phase) {
        return event(AuditEventId.LIVENESS_TIMEOUT).put(AuditAttributes.PHASE, phase.name()).build();
    }

    /**
     * LV08: a presentation or injection attack is suspected.
     *
     * @param reason {@code PAD_SCORE}, {@code FROZEN}, {@code LOOP}, {@code PLANAR}, {@code FACE_SWITCH},
     *               {@code DEVICE_PAD} or another integrity code
     * @return the event
     */
    public LivenessAuditEvent padAttackSuspected(ReasonCode reason) {
        return event(AuditEventId.LIVENESS_PAD_ATTACK_SUSPECTED).put(AuditAttributes.REASON, reason.name()).build();
    }

    /**
     * LV09: the signed capture did not match the session.
     *
     * @param verdict the failed binding verdict
     * @return the event
     */
    public LivenessAuditEvent bindingFailed(BindingVerdict verdict) {
        LivenessAuditEvent.Builder b = event(AuditEventId.LIVENESS_BINDING_FAILED);
        if (verdict.similarity().isPresent()) {
            b.put(AuditAttributes.SIMILARITY_BUCKET, AuditAttributes.bucket(verdict.similarity().getAsInt()));
        }
        if (verdict.gapMs().isPresent()) {
            b.put(AuditAttributes.GAP_MS, verdict.gapMs().getAsLong());
        }
        ChallengeEchoStatus echo = verdict.challengeEcho();
        b.put(AuditAttributes.CHALLENGE_ECHO_OK, echo == ChallengeEchoStatus.NOT_REQUESTED ? "n/a"
                : Boolean.toString(echo == ChallengeEchoStatus.MATCHED));
        StringBuilder codes = new StringBuilder();
        for (ReasonCode c : verdict.reasonCodes()) {
            codes.append(codes.length() > 0 ? "," : "").append(c.name());
        }
        return b.put(AuditAttributes.REASON, truncate(codes.toString())).build();
    }

    /**
     * LV10: the session passed.
     *
     * @param method  liveness method
     * @param totalMs total session time
     * @return the event
     */
    public LivenessAuditEvent passed(LivenessMethod method, long totalMs) {
        return event(AuditEventId.LIVENESS_PASSED).put(AuditAttributes.METHOD, method.name())
                .put(AuditAttributes.TOTAL_MS, totalMs).build();
    }

    /**
     * LV11: the attempt limit was reached.
     *
     * @param attempts     attempts used
     * @param onMaxFailure configured behaviour
     * @return the event
     */
    public LivenessAuditEvent maxRetries(int attempts, OnMaxFailure onMaxFailure) {
        return event(AuditEventId.LIVENESS_MAX_RETRIES).put(AuditAttributes.ATTEMPTS, attempts)
                .put(AuditAttributes.ON_MAX_FAILURE, onMaxFailure.name()).build();
    }

    /**
     * LV12: a device error ended the session.
     *
     * @param code device error code
     * @return the event
     */
    public LivenessAuditEvent deviceError(DeviceErrorCode code) {
        return event(AuditEventId.LIVENESS_DEVICE_ERROR).put(AuditAttributes.ERROR_CODE, code.name()).build();
    }

    /**
     * LV13: an engine error ended the session.
     *
     * @param code       engine error code
     * @param errorClass simple class name of the exception (never its message), or {@code null}
     * @return the event
     */
    public LivenessAuditEvent engineError(EngineErrorCode code, String errorClass) {
        LivenessAuditEvent.Builder b = event(AuditEventId.LIVENESS_ENGINE_ERROR)
                .put(AuditAttributes.ERROR_CODE, code.name());
        if (errorClass != null) {
            b.put(AuditAttributes.ERROR_CLASS, truncate(errorClass));
        }
        return b.build();
    }

    /**
     * LV14: a model failed its hash, signature or version check.
     *
     * @param modelId model identifier
     * @return the event
     */
    public LivenessAuditEvent modelIntegrityFailed(String modelId) {
        return event(AuditEventId.LIVENESS_MODEL_INTEGRITY_FAILED).put(AuditAttributes.MODEL_ID, truncate(modelId)).build();
    }

    /**
     * LV15: configuration applied, a value clamped to its floor, or a model version changed.
     *
     * @param changed changed keys or versions, comma-separated (cut to 256 characters)
     * @return the event
     */
    public LivenessAuditEvent configApplied(String changed) {
        return event(AuditEventId.LIVENESS_CONFIG_APPLIED).put(AuditAttributes.CHANGED, truncate(changed)).build();
    }

    /**
     * LV16: a supervisor override was used.
     *
     * @param supervisorId supervisor user ID
     * @return the event
     */
    public LivenessAuditEvent overrideUsed(String supervisorId) {
        return event(AuditEventId.LIVENESS_OVERRIDE_USED).put(AuditAttributes.SUPERVISOR_ID, truncate(supervisorId)).build();
    }

    /**
     * LV17: diagnostic mode was switched.
     *
     * @param userId  user who switched it
     * @param enabled new setting
     * @return the event
     */
    public LivenessAuditEvent diagnosticMode(String userId, boolean enabled) {
        return event(AuditEventId.LIVENESS_DIAGNOSTIC_MODE).put(AuditAttributes.USER_ID, truncate(userId))
                .put(AuditAttributes.CHANGED, enabled ? "Y" : "N").build();
    }

    private static String truncate(String s) {
        return s.length() <= LivenessAuditEvent.MAX_VALUE_LENGTH ? s : s.substring(0, LivenessAuditEvent.MAX_VALUE_LENGTH);
    }
}
