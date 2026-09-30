package io.mosip.registration.livenessmanager.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.mosip.liveness.core.engine.LivenessMetrics;

/**
 * Text forms of the anonymised liveness metrics ({@link LivenessMetrics.Snapshot}): one line for the metrics log
 * and a few short lines for the diagnostic view of the capture screen.
 *
 * <p>The values are counts, averages and rates over all sessions of the process: sessions, retries, passes,
 * failures per reason, average total / passive / challenge / per-frame processing time, retry rate and failure
 * rate. They carry no user ID, registration ID, device serial, image or score of a single capture; the names
 * are those the desktop client publishes to its {@code logs/metrics.log}.
 */
public final class MetricsReport {

    private MetricsReport() {
    }

    /**
     * Formats one log line: {@code <time> liveness sessions=.. retries=.. ... failure_rate=..}. Failure counters
     * that are zero are left out.
     *
     * @param snapshot metrics values
     * @param at       time of the snapshot
     * @return the line, without a line break
     */
    public static String line(LivenessMetrics.Snapshot snapshot, Instant at) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder b = new StringBuilder(Objects.requireNonNull(at, "at").toString()).append(" liveness");
        for (Map.Entry<String, Long> e : snapshot.counters().entrySet()) {
            String name = e.getKey();
            if (name.startsWith(LivenessMetrics.FAILURES_PREFIX) && e.getValue() == 0L) {
                continue;
            }
            b.append(' ').append(shortName(name)).append('=').append(e.getValue());
        }
        b.append(" time.total.avg_ms=").append(round(snapshot.averageTotalMs()))
                .append(" time.total.max_ms=").append(snapshot.maxTotalMs())
                .append(" time.passive.avg_ms=").append(round(snapshot.averagePassiveMs()))
                .append(" time.challenge.avg_ms=").append(round(snapshot.averageChallengeMs()))
                .append(" time.frame.avg_ms=").append(round(snapshot.averageFrameMs()))
                .append(" time.frame.max_ms=").append(snapshot.maxFrameMs())
                .append(" rate.retry=").append(rate(snapshot.retryRate()))
                .append(" rate.failure=").append(rate(snapshot.failureRate()));
        return b.toString();
    }

    /**
     * Formats the diagnostic view's lines (technical English, like the desktop diagnostic line).
     *
     * @param snapshot metrics values
     * @return three lines: counts, times, rates
     */
    public static List<String> summary(LivenessMetrics.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> lines = new ArrayList<>();
        lines.add("sessions " + snapshot.counter(LivenessMetrics.SESSIONS)
                + " | passes " + snapshot.counter(LivenessMetrics.PASSES)
                + " | retries " + snapshot.counter(LivenessMetrics.RETRIES)
                + " | frames " + snapshot.counter(LivenessMetrics.FRAMES_ANALYSED));
        lines.add("avg ms: total " + round(snapshot.averageTotalMs())
                + " | passive " + round(snapshot.averagePassiveMs())
                + " | challenge " + round(snapshot.averageChallengeMs())
                + " | frame " + round(snapshot.averageFrameMs()) + " (max " + snapshot.maxFrameMs() + ")");
        lines.add("retry rate " + percent(snapshot.retryRate()) + " | failure rate " + percent(snapshot.failureRate()));
        return lines;
    }

    private static String shortName(String counter) {
        return counter.startsWith("liveness.") ? counter.substring("liveness.".length()) : counter;
    }

    private static long round(double value) {
        return Math.round(value);
    }

    private static String rate(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0);
    }
}
