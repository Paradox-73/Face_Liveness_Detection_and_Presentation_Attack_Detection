package io.mosip.liveness.eval;

import io.mosip.liveness.core.api.ChallengeRecord;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the JSON line written for one attempt (schema {@value #SCHEMA}). Field names follow design section
 * 10.6 item 6; no field holds image data, embeddings or personal identifiers.
 */
final class AttemptRecord {

    /** Schema identifier written into every line. */
    static final String SCHEMA = "liveness-eval-attempt/1";

    private AttemptRecord() {
    }

    /** Facts about the run that are the same for every line. */
    static final class RunInfo {
        final String runId;
        final String modelBundle;
        final String modelSetSha256;
        final Map<String, Object> platform;

        RunInfo(String runId, String modelBundle, String modelSetSha256, Map<String, Object> platform) {
            this.runId = runId;
            this.modelBundle = modelBundle;
            this.modelSetSha256 = modelSetSha256;
            this.platform = platform;
        }
    }

    /** Position of an attempt within its transaction. */
    static final class TxnInfo {
        final String txnId;
        final long seed;
        final int session;
        final int maxAttempts;
        final String configHash;
        final String engineParamsHash;

        TxnInfo(String txnId, long seed, int session, int maxAttempts, String configHash, String engineParamsHash) {
            this.txnId = txnId;
            this.seed = seed;
            this.session = session;
            this.maxAttempts = maxAttempts;
            this.configHash = configHash;
            this.engineParamsHash = engineParamsHash;
        }
    }

    /**
     * Builds the record.
     *
     * @param run      run facts
     * @param sc       the scenario
     * @param txn      transaction facts
     * @param a        the attempt
     * @param txnFinal transaction outcome if this is the transaction's last attempt, otherwise {@code null}
     * @return an ordered map ready for {@link Json#write}
     */
    static Map<String, Object> of(RunInfo run, Scenario sc, TxnInfo txn, AttemptResult a, Outcome txnFinal) {
        LivenessResult r = a.result();
        Outcome outcome = Outcome.of(r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("run_id", run.runId);
        m.put("txn_id", txn.txnId);
        m.put("scenario", sc.id());
        m.put("kind", sc.kind().name().toLowerCase(Locale.ROOT));
        m.put("species", sc.species());
        m.put("subject_id", sc.subject());
        m.put("condition", sc.condition());
        m.put("split", "technology-regression");
        m.put("workflow", sc.workflow().id());
        m.put("seed", txn.seed);
        m.put("attempt_no", r.attempt());
        m.put("session_no", txn.session);
        m.put("max_attempts", txn.maxAttempts);
        m.put("last_in_txn", txnFinal != null);
        m.put("txn_outcome", txnFinal);
        m.put("outcome", outcome);
        m.put("decision", r.decision());
        m.put("failure_reason", r.failureReason().orElse(null));
        m.put("reason_codes", names(r.reasonCodes()));
        m.put("layer", Layer.of(r).id());
        m.put("no_response_reason", outcome == Outcome.NO_RESPONSE ? noResponseReason(r, a) : null);
        m.put("method", r.method());
        m.put("active_triggered", r.method() == LivenessMethod.PASSIVE_ACTIVE || r.method() == LivenessMethod.ACTIVE);
        m.put("passive_score", r.passiveScore().isPresent() ? r.passiveScore().getAsInt() : null);
        m.put("active_pad_score", r.activePadScore().isPresent() ? r.activePadScore().getAsInt() : null);
        m.put("binding_similarity", r.bindingSimilarity().isPresent() ? r.bindingSimilarity().getAsInt() : null);
        m.put("planarity_residuals", a.planarityResiduals());
        m.put("challenges", challenges(r.challenges()));
        m.put("prompts", prompts(a.prompts()));
        m.put("t_passive_ms", r.passiveMs());
        m.put("t_active_ms", r.activeMs());
        m.put("t_total_ms", r.totalMs());
        m.put("stream_ms", a.streamMs());
        m.put("wall_ms", LatencyStats.round(a.wallMs()));
        m.put("frames_streamed", a.framesStreamed());
        m.put("frames_analysed", a.frameMs().count());
        m.put("frames_per_pack", a.framesPerPack());
        m.put("frame_ms", a.frameMs().toMap());
        m.put("stage_ms", stages(a));
        m.put("bind_ms", LatencyStats.round(a.bindMs()));
        m.put("device_error", r.deviceError().orElse(null));
        m.put("engine_error", r.engineError().orElse(null));
        m.put("last_ui", a.lastUi());
        m.put("expected_outcome", sc.expect().outcome());
        m.put("expected_layers", layerIds(sc));
        m.put("model_bundle", run.modelBundle);
        m.put("model_set_sha256", run.modelSetSha256);
        m.put("config_hash", txn.configHash);
        m.put("engine_params_hash", txn.engineParamsHash);
        m.put("platform", run.platform);
        return m;
    }

    private static String noResponseReason(LivenessResult r, AttemptResult a) {
        if (a.runnerLimit()) {
            return "RUNNER_TIME_LIMIT";
        }
        if (r.deviceError().isPresent()) {
            return "DEVICE_" + r.deviceError().get().name();
        }
        if (r.engineError().isPresent()) {
            return "ENGINE_" + r.engineError().get().name();
        }
        return r.reasonCodes().isEmpty() ? r.decision().name() : String.join("+", names(r.reasonCodes()));
    }

    private static List<String> names(Iterable<? extends Enum<?>> values) {
        List<String> out = new ArrayList<>();
        for (Enum<?> e : values) {
            out.add(e.name());
        }
        return out;
    }

    private static List<Object> challenges(List<ChallengeRecord> records) {
        List<Object> out = new ArrayList<>();
        for (ChallengeRecord c : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", c.type());
            m.put("outcome", c.outcome());
            m.put("onset_ms", c.onsetMs().isPresent() ? c.onsetMs().getAsLong() : null);
            m.put("hold_ms", c.holdMs().isPresent() ? c.holdMs().getAsLong() : null);
            m.put("duration_ms", c.durationMs());
            out.add(m);
        }
        return out;
    }

    private static List<Object> prompts(List<Playlist.PromptRecord> prompts) {
        List<Object> out = new ArrayList<>();
        for (Playlist.PromptRecord p : prompts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", p.type);
            m.put("pack", p.packId);
            m.put("at_ms", p.atMs);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> stages(AttemptResult a) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<StageTimer.Stage, LatencyStats> e : a.stageMs().entrySet()) {
            m.put(e.getKey().name().toLowerCase(Locale.ROOT), e.getValue().toMap());
        }
        return m;
    }

    private static List<String> layerIds(Scenario sc) {
        List<String> out = new ArrayList<>();
        for (Layer l : sc.expect().layers()) {
            out.add(l.id());
        }
        return out;
    }
}
