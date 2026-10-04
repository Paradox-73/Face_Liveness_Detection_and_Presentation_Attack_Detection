package io.mosip.liveness.core.api;

import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.device.DevicePadDecision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keys and values of the liveness evidence stored in the CBEFF BIR {@code others} map of the face record
 * (docs/design/audit-and-evidence.md section 2), identical on both clients. It applies to resident, officer and
 * supervisor faces.
 */
public final class PacketEvidenceKeys {

    /** {@code PASS}, {@code FAIL} or {@code OVERRIDE}. */
    public static final String DECISION = "LIVENESS_DECISION";
    /** Name of the {@link LivenessMethod}. */
    public static final String METHOD = "LIVENESS_METHOD";
    /** Passive score 0-100. */
    public static final String SCORE = "LIVENESS_SCORE";
    /** Challenges, for example {@code BLINK:OK,TURN_LEFT:OK}. */
    public static final String CHALLENGES = "LIVENESS_CHALLENGES";
    /** Engine tag {@code id@version}. */
    public static final String ENGINE = "LIVENESS_ENGINE";
    /** Comma-separated 8-hex-digit SHA-256 prefixes of the models, in engine order. */
    public static final String MODEL_SHA = "LIVENESS_MODEL_SHA";
    /** Session identifier. */
    public static final String SESSION = "LIVENESS_SESSION";
    /** Binding similarity as a 10-point band, for example {@code 80-89}. */
    public static final String BINDING = "LIVENESS_BINDING";
    /** SBI certification level of the device, {@code L0} or {@code L1}. */
    public static final String DEVICE_LEVEL = "LIVENESS_DEVICE_LEVEL";
    /** {@code PASS} when the signed capture carried a device PAD block that found no attack; absent otherwise. */
    public static final String DEVICE_PAD = "LIVENESS_DEVICE_PAD";

    /** Decision value for a pass. */
    public static final String DECISION_PASS = "PASS";
    /** Decision value for a failure. */
    public static final String DECISION_FAIL = "FAIL";
    /** Decision value for a capture accepted by supervisor override. */
    public static final String DECISION_OVERRIDE = "OVERRIDE";

    private PacketEvidenceKeys() {
    }

    /**
     * Builds the evidence entries of a result. Absent values are left out. A caller that accepts the capture by
     * supervisor override replaces {@link #DECISION} with {@link #DECISION_OVERRIDE}.
     *
     * @param result the session result
     * @return an unmodifiable map in a stable order
     */
    public static Map<String, String> of(LivenessResult result) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(DECISION, result.isPass() ? DECISION_PASS : DECISION_FAIL);
        m.put(METHOD, result.method().name());
        if (result.passiveScore().isPresent()) {
            m.put(SCORE, Integer.toString(result.passiveScore().getAsInt()));
        }
        if (!result.challenges().isEmpty()) {
            m.put(CHALLENGES, result.challengeEvidence());
        }
        m.put(ENGINE, result.engine().tag());
        if (!result.engine().models().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ModelInfo model : result.engine().models()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(model.sha256Prefix());
            }
            m.put(MODEL_SHA, sb.toString());
        }
        m.put(SESSION, result.sessionId());
        if (result.bindingSimilarity().isPresent()) {
            m.put(BINDING, AuditAttributes.bucket(result.bindingSimilarity().getAsInt()));
        }
        m.put(DEVICE_LEVEL, result.device().certification().code());
        if (result.devicePad().isPresent() && result.devicePad().get().decision() == DevicePadDecision.PASS) {
            m.put(DEVICE_PAD, DECISION_PASS);
        }
        return Collections.unmodifiableMap(m);
    }
}
