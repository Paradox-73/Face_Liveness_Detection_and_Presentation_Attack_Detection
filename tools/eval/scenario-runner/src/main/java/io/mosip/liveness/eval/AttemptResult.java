package io.mosip.liveness.eval;

import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.audit.DiagnosticRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything recorded about one attempt: the engine's result plus stream and timing facts measured by the runner.
 * Holds no image data.
 */
final class AttemptResult {

    private final LivenessResult result;
    private final long streamMs;
    private final double wallMs;
    private final int framesStreamed;
    private final Map<String, Integer> framesPerPack;
    private final LatencyStats frameMs;
    private final Map<StageTimer.Stage, LatencyStats> stageMs;
    private final double bindMs;
    private final List<Playlist.PromptRecord> prompts;
    private final List<Double> planarityResiduals;
    private final boolean runnerLimit;
    private final String lastUi;

    private AttemptResult(Builder b) {
        this.result = Objects.requireNonNull(b.result, "result");
        this.streamMs = b.streamMs;
        this.wallMs = b.wallMs;
        this.framesStreamed = b.framesStreamed;
        this.framesPerPack = Collections.unmodifiableMap(b.framesPerPack);
        this.frameMs = b.frameMs;
        this.stageMs = Collections.unmodifiableMap(b.stageMs);
        this.bindMs = b.bindMs;
        this.prompts = Collections.unmodifiableList(new ArrayList<>(b.prompts));
        this.planarityResiduals = Collections.unmodifiableList(b.planarityResiduals);
        this.runnerLimit = b.runnerLimit;
        this.lastUi = b.lastUi;
    }

    LivenessResult result() {
        return result;
    }

    /**
     * Returns the stream time the attempt used.
     *
     * @return milliseconds of replayed stream
     */
    long streamMs() {
        return streamMs;
    }

    /**
     * Returns the wall-clock time the attempt took on this machine.
     *
     * @return milliseconds
     */
    double wallMs() {
        return wallMs;
    }

    int framesStreamed() {
        return framesStreamed;
    }

    Map<String, Integer> framesPerPack() {
        return framesPerPack;
    }

    /**
     * Returns the wall-clock time of each analysed frame ({@code onFrame} plus {@code tick}).
     *
     * @return per-frame latency in milliseconds
     */
    LatencyStats frameMs() {
        return frameMs;
    }

    Map<StageTimer.Stage, LatencyStats> stageMs() {
        return stageMs;
    }

    /**
     * Returns the wall-clock time of the binding check.
     *
     * @return milliseconds, or {@code NaN} if no capture was bound
     */
    double bindMs() {
        return bindMs;
    }

    List<Playlist.PromptRecord> prompts() {
        return prompts;
    }

    /**
     * Returns the head-turn planarity residuals the session reported through its diagnostic records.
     *
     * @return residuals in report order; empty if no head turn was measured
     */
    List<Double> planarityResiduals() {
        return planarityResiduals;
    }

    /**
     * Tells whether the runner cancelled the attempt at {@code max_stream_ms}.
     *
     * @return {@code true} if the runner's stream-time limit ended the attempt
     */
    boolean runnerLimit() {
        return runnerLimit;
    }

    /**
     * Returns the message key of the last UI state.
     *
     * @return for example {@code liveness.fail.pad}
     */
    String lastUi() {
        return lastUi;
    }

    /** Builder filled by {@link AttemptDriver}. */
    static final class Builder {
        private LivenessResult result;
        private long streamMs;
        private double wallMs;
        private int framesStreamed;
        private final Map<String, Integer> framesPerPack = new LinkedHashMap<>();
        private final LatencyStats frameMs = new LatencyStats();
        private final Map<StageTimer.Stage, LatencyStats> stageMs = new EnumMap<>(StageTimer.Stage.class);
        private double bindMs = Double.NaN;
        private List<Playlist.PromptRecord> prompts = Collections.emptyList();
        private final List<Double> planarityResiduals = new ArrayList<>();
        private boolean runnerLimit;
        private String lastUi = "";

        Builder result(LivenessResult result) {
            this.result = result;
            return this;
        }

        Builder streamMs(long streamMs) {
            this.streamMs = streamMs;
            return this;
        }

        Builder wallMs(double wallMs) {
            this.wallMs = wallMs;
            return this;
        }

        Builder frameStreamed(String packId) {
            framesStreamed++;
            framesPerPack.merge(packId, 1, Integer::sum);
            return this;
        }

        Builder frameAnalysed(double ms, StageTimer timer) {
            frameMs.add(ms);
            for (StageTimer.Stage s : StageTimer.Stage.values()) {
                if (timer.calls(s) > 0) {
                    stageMs.computeIfAbsent(s, k -> new LatencyStats()).add(timer.nanos(s) / 1e6);
                }
            }
            return this;
        }

        Builder bindMs(double ms) {
            this.bindMs = ms;
            return this;
        }

        Builder prompts(List<Playlist.PromptRecord> prompts) {
            this.prompts = prompts;
            return this;
        }

        Builder diagnostics(List<DiagnosticRecord> records) {
            Double previous = null;
            for (DiagnosticRecord r : records) {
                if (r.planarityResidual().isPresent()) {
                    double v = r.planarityResidual().getAsDouble();
                    if (previous == null || previous != v) {
                        planarityResiduals.add(v);
                    }
                    previous = v;
                }
            }
            return this;
        }

        Builder runnerLimit(boolean runnerLimit) {
            this.runnerLimit = runnerLimit;
            return this;
        }

        Builder lastUi(String lastUi) {
            this.lastUi = lastUi == null ? "" : lastUi;
            return this;
        }

        AttemptResult build() {
            return new AttemptResult(this);
        }
    }
}
