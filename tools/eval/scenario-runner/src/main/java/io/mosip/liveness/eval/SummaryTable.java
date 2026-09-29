package io.mosip.liveness.eval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One row per transaction plus a latency footer, rendered as a Markdown table ({@code summary.md}) and printed to
 * the console.
 */
final class SummaryTable {

    /** One transaction. */
    static final class Row {
        final String scenario;
        final Scenario.Kind kind;
        final String species;
        final long seed;
        final List<Outcome> attempts;
        final List<String> layers;
        final List<String> passiveScores;
        final String lastReasons;
        final Outcome expected;
        final boolean layerOk;

        Row(String scenario, Scenario.Kind kind, String species, long seed, List<Outcome> attempts, List<String> layers,
            List<String> passiveScores, String lastReasons, Outcome expected, boolean layerOk) {
            this.scenario = scenario;
            this.kind = kind;
            this.species = species;
            this.seed = seed;
            this.attempts = attempts;
            this.layers = layers;
            this.passiveScores = passiveScores;
            this.lastReasons = lastReasons;
            this.expected = expected;
            this.layerOk = layerOk;
        }

        Outcome outcome() {
            return Outcome.ofTransaction(attempts);
        }

        /**
         * Tells whether the transaction met the scenario's expectation.
         *
         * @return {@code true} if no outcome was expected, or the outcome and layers matched
         */
        boolean ok() {
            return (expected == null || expected == outcome()) && layerOk;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final LatencyStats frameMs = new LatencyStats();
    private final Map<StageTimer.Stage, LatencyStats> stageMs = new EnumMap<>(StageTimer.Stage.class);

    void add(Row row) {
        rows.add(row);
    }

    /**
     * Adds an attempt's frame timings to the footer.
     *
     * @param a the attempt
     */
    void addTimings(AttemptResult a) {
        frameMs.addAll(a.frameMs());
        for (Map.Entry<StageTimer.Stage, LatencyStats> e : a.stageMs().entrySet()) {
            stageMs.computeIfAbsent(e.getKey(), k -> new LatencyStats()).addAll(e.getValue());
        }
    }

    List<Row> rows() {
        return rows;
    }

    /**
     * Counts transactions that did not meet their expectation.
     *
     * @return the number of mismatches
     */
    int mismatches() {
        int n = 0;
        for (Row r : rows) {
            if (!r.ok()) {
                n++;
            }
        }
        return n;
    }

    LatencyStats frameMs() {
        return frameMs;
    }

    /**
     * Renders the table.
     *
     * @return Markdown text
     */
    String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("| scenario | kind | species | seed | attempts | outcome | expected | ok | layer per attempt "
                + "| passive score per attempt | reasons (last attempt) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| ").append(r.scenario)
                    .append(" | ").append(r.kind.name().toLowerCase(Locale.ROOT))
                    .append(" | ").append(r.species)
                    .append(" | ").append(r.seed)
                    .append(" | ").append(r.attempts.size())
                    .append(" | ").append(r.outcome())
                    .append(" | ").append(r.expected == null ? "-" : r.expected.name())
                    .append(" | ").append(r.ok() ? "yes" : "**NO**")
                    .append(" | ").append(String.join(", ", r.layers))
                    .append(" | ").append(String.join(", ", r.passiveScores))
                    .append(" | ").append(r.lastReasons)
                    .append(" |\n");
        }
        sb.append('\n').append(footer());
        return sb.toString();
    }

    private String footer() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Transactions: %d, expectation mismatches: %d.%n%n", rows.size(),
                mismatches()));
        sb.append(String.format(Locale.ROOT,
                "Per analysed frame (decode + analysis + PAD, wall clock): n=%d, mean %.1f ms, p50 %.1f ms, "
                        + "p95 %.1f ms, max %.1f ms.%n", frameMs.count(), frameMs.mean(), frameMs.percentile(50),
                frameMs.percentile(95), frameMs.max()));
        for (Map.Entry<StageTimer.Stage, LatencyStats> e : stageMs.entrySet()) {
            LatencyStats s = e.getValue();
            sb.append(String.format(Locale.ROOT, "- %s: n=%d, mean %.1f ms, p50 %.1f ms, p95 %.1f ms%n",
                    e.getKey().name().toLowerCase(Locale.ROOT), s.count(), s.mean(), s.percentile(50),
                    s.percentile(95)));
        }
        return sb.toString();
    }
}
