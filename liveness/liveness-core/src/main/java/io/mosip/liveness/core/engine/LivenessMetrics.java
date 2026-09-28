package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.api.LivenessResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anonymised liveness metrics (design section 4.17, G7/G8): counters and timers kept in memory by the engine and
 * shared by its sessions. Recorded only when {@code metrics.enable=Y}.
 *
 * <p>Metric names are fixed strings plus enum names ({@code liveness.failures.PAD}); they never contain user
 * IDs, registration IDs, device serial numbers or session identifiers (test T-CORE-MET-02). Clients may export a
 * {@link #snapshot()} to their own metrics system. Thread-safe.
 */
public final class LivenessMetrics {

    /** Sessions started. */
    public static final String SESSIONS = "liveness.sessions";
    /** Sessions started as a retry (attempt 2 or later). */
    public static final String RETRIES = "liveness.retries";
    /** Sessions that passed. */
    public static final String PASSES = "liveness.passes";
    /** Sessions accepted by the passive check alone. */
    public static final String PASSIVE_PASSES = "liveness.passive_passes";
    /** Sessions escalated to challenges. */
    public static final String ACTIVE_TRIGGERS = "liveness.active_triggers";
    /** Prefix of the failure counters; the suffix is the {@link FailureReason} name. */
    public static final String FAILURES_PREFIX = "liveness.failures.";
    /** Challenges completed successfully. */
    public static final String CHALLENGES_PASSED = "liveness.challenges_passed";
    /** Frames decoded and analysed (face analysis, PAD and embeddings). */
    public static final String FRAMES_ANALYSED = "liveness.frames_analysed";

    private long sessions;
    private long retries;
    private long passes;
    private long passivePasses;
    private long activeTriggers;
    private long challengesPassed;
    private final long[] failures = new long[FailureReason.values().length];
    private final Timer total = new Timer();
    private final Timer passive = new Timer();
    private final Timer challenge = new Timer();
    private final Timer frame = new Timer();

    /** Creates a collector with all counters at zero. */
    public LivenessMetrics() {
        // Counters start at zero.
    }

    /**
     * Records a session start.
     *
     * @param attempt 1-based attempt
     */
    public synchronized void sessionStarted(int attempt) {
        sessions++;
        if (attempt > 1) {
            retries++;
        }
    }

    /**
     * Records a passive pass.
     *
     * @param passiveMs acquisition and passive time
     */
    public synchronized void passivePassed(long passiveMs) {
        passivePasses++;
        passive.add(passiveMs);
    }

    /** Records an escalation to challenges. */
    public synchronized void activeTriggered() {
        activeTriggers++;
    }

    /**
     * Records a completed challenge.
     *
     * @param durationMs prompt-to-completion time
     */
    public synchronized void challengeCompleted(long durationMs) {
        challengesPassed++;
        challenge.add(durationMs);
    }

    /**
     * Records the processing time of one analysed frame (decode, face analysis, PAD and embeddings).
     *
     * @param ms processing time in milliseconds
     */
    public synchronized void frameProcessed(long ms) {
        frame.add(ms);
    }

    /**
     * Records the end of a session.
     *
     * @param result the result
     */
    public synchronized void sessionEnded(LivenessResult result) {
        if (result.isPass()) {
            passes++;
        } else {
            failures[result.failureReason().get().ordinal()]++;
        }
        total.add(result.totalMs());
    }

    /**
     * Returns the current values.
     *
     * @return an immutable snapshot
     */
    public synchronized Snapshot snapshot() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put(SESSIONS, sessions);
        counters.put(RETRIES, retries);
        counters.put(PASSES, passes);
        counters.put(PASSIVE_PASSES, passivePasses);
        counters.put(ACTIVE_TRIGGERS, activeTriggers);
        counters.put(CHALLENGES_PASSED, challengesPassed);
        counters.put(FRAMES_ANALYSED, frame.count);
        long failed = 0;
        for (FailureReason r : FailureReason.values()) {
            counters.put(FAILURES_PREFIX + r.name(), failures[r.ordinal()]);
            failed += failures[r.ordinal()];
        }
        long ended = passes + failed;
        return new Snapshot(counters, new double[] {total.mean(), passive.mean(), challenge.mean(), frame.mean()},
                total.max, frame.max, ratio(retries, sessions),
                ratio(failed - failures[FailureReason.CANCELLED.ordinal()], ended));
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 0 : (double) a / b;
    }

    /** Sum, count and maximum of one timer. */
    private static final class Timer {
        private long sum;
        private long count;
        private long max;

        void add(long ms) {
            sum += ms;
            count++;
            max = Math.max(max, ms);
        }

        double mean() {
            return count == 0 ? 0 : (double) sum / count;
        }
    }

    /** Point-in-time copy of the metrics. */
    public static final class Snapshot {

        private final Map<String, Long> counters;
        private final double averageTotalMs;
        private final double averagePassiveMs;
        private final double averageChallengeMs;
        private final double averageFrameMs;
        private final long maxTotalMs;
        private final long maxFrameMs;
        private final double retryRate;
        private final double failureRate;

        /** Means are total, passive, challenge and frame time, in that order. */
        Snapshot(Map<String, Long> counters, double[] means, long maxTotalMs, long maxFrameMs, double retryRate,
                double failureRate) {
            this.counters = Collections.unmodifiableMap(counters);
            this.averageTotalMs = means[0];
            this.averagePassiveMs = means[1];
            this.averageChallengeMs = means[2];
            this.averageFrameMs = means[3];
            this.maxTotalMs = maxTotalMs;
            this.maxFrameMs = maxFrameMs;
            this.retryRate = retryRate;
            this.failureRate = failureRate;
        }

        /**
         * Returns the mean processing time of an analysed frame.
         *
         * @return milliseconds
         */
        public double averageFrameMs() {
            return averageFrameMs;
        }

        /**
         * Returns the longest processing time of an analysed frame.
         *
         * @return milliseconds
         */
        public long maxFrameMs() {
            return maxFrameMs;
        }

        /**
         * Returns every counter by name.
         *
         * @return an unmodifiable map
         */
        public Map<String, Long> counters() {
            return counters;
        }

        /**
         * Returns one counter.
         *
         * @param name counter name
         * @return its value, 0 if unknown
         */
        public long counter(String name) {
            Long v = counters.get(name);
            return v == null ? 0 : v;
        }

        /**
         * Returns the mean session time of ended sessions.
         *
         * @return milliseconds
         */
        public double averageTotalMs() {
            return averageTotalMs;
        }

        /**
         * Returns the mean passive time of passive passes.
         *
         * @return milliseconds
         */
        public double averagePassiveMs() {
            return averagePassiveMs;
        }

        /**
         * Returns the mean challenge completion time.
         *
         * @return milliseconds
         */
        public double averageChallengeMs() {
            return averageChallengeMs;
        }

        /**
         * Returns the longest session time.
         *
         * @return milliseconds
         */
        public long maxTotalMs() {
            return maxTotalMs;
        }

        /**
         * Returns the share of sessions that were retries.
         *
         * @return 0-1
         */
        public double retryRate() {
            return retryRate;
        }

        /**
         * Returns the share of ended sessions that failed (cancellations are left out of the failures).
         *
         * @return 0-1
         */
        public double failureRate() {
            return failureRate;
        }

        @Override
        public String toString() {
            return "LivenessMetrics" + counters + ", avgTotalMs=" + averageTotalMs + ", retryRate=" + retryRate
                    + ", failureRate=" + failureRate;
        }
    }
}
