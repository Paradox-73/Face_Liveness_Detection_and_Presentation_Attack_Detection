package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.ModelInfo;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.audit.DiagnosticSink;

import java.util.Collection;
import java.util.Objects;

/**
 * Writes one diagnostic record per session state change when {@code diagnostic.enable=Y} (design section 4.17):
 * session identifier, state, PAD score, integrity flags, planarity residual, binding similarity, frame rate and
 * model versions. It never writes images, embeddings, user identifiers or registration identifiers: the record
 * type has no field for them. A failing sink is ignored so diagnostics can never break a session.
 */
public final class DiagnosticLog {

    private final DiagnosticSink sink;
    private final boolean enabled;
    private final String sessionId;
    private final EngineInfo engine;

    /**
     * Creates a log for one session.
     *
     * @param sink      destination
     * @param enabled   whether diagnostics are on ({@code diagnostic.enable})
     * @param sessionId session identifier
     * @param engine    engine, for the model versions
     */
    public DiagnosticLog(DiagnosticSink sink, boolean enabled, String sessionId, EngineInfo engine) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.enabled = enabled;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Tells whether records are written.
     *
     * @return {@code true} if diagnostics are on
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Writes the record of a state change.
     *
     * @param state      state entered
     * @param elapsedMs  time since the session started
     * @param padScore   current PAD score 0-100, or -1 if none
     * @param flags      integrity and quality flags raised so far
     * @param planarity  last planarity residual, or {@code NaN}
     * @param similarity last same-person or binding similarity 0-100, or -1
     * @param fps        measured frame rate, or 0
     * @param detail     short technical detail, or an empty string
     */
    public void stateChanged(SessionState state, long elapsedMs, int padScore, Collection<ReasonCode> flags,
            double planarity, int similarity, double fps, String detail) {
        if (!enabled) {
            return;
        }
        DiagnosticRecord.Builder b = DiagnosticRecord.builder(sessionId, state, Math.max(0, elapsedMs));
        if (padScore >= 0 && padScore <= 100) {
            b.padScore(padScore);
        }
        for (ReasonCode f : flags) {
            b.flag(f);
        }
        if (planarity >= 0 && !Double.isInfinite(planarity)) {
            b.planarityResidual(planarity);
        }
        if (similarity >= 0 && similarity <= 100) {
            b.bindingSimilarity(similarity);
        }
        if (fps > 0 && !Double.isInfinite(fps)) {
            b.fps(Math.round(fps * 10) / 10.0);
        }
        for (ModelInfo m : engine.models()) {
            b.model(m.id(), m.version());
        }
        if (detail != null && !detail.isEmpty()) {
            b.detail(detail.length() > DiagnosticRecord.MAX_DETAIL_LENGTH
                    ? detail.substring(0, DiagnosticRecord.MAX_DETAIL_LENGTH) : detail);
        }
        write(b.build());
    }

    /**
     * Writes a record built by the caller, if diagnostics are on.
     *
     * @param record the record
     */
    public void write(DiagnosticRecord record) {
        if (!enabled) {
            return;
        }
        try {
            sink.log(record);
        } catch (RuntimeException e) {
            // Diagnostics must never affect the session.
        }
    }
}
