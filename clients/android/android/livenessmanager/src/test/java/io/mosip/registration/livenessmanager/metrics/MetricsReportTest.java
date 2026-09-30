package io.mosip.registration.livenessmanager.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.mosip.liveness.core.engine.LivenessMetrics;

/** Metrics log line, diagnostic summary and the rotating metrics file. */
public class MetricsReportTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void lineHasCountsTimesAndRatesAndOnlyNonZeroFailures() {
        LivenessMetrics metrics = new LivenessMetrics();
        metrics.sessionStarted(1);
        metrics.sessionStarted(2);
        metrics.passivePassed(800);
        metrics.challengeCompleted(1200);
        metrics.challengeCompleted(1800);
        metrics.frameProcessed(30);
        metrics.frameProcessed(50);

        String line = MetricsReport.line(metrics.snapshot(), Instant.parse("2026-09-30T10:00:00Z"));

        assertTrue(line, line.startsWith("2026-09-30T10:00:00Z liveness sessions=2 retries=1 "));
        assertTrue(line, line.contains(" challenges_passed=2 "));
        assertTrue(line, line.contains(" time.challenge.avg_ms=1500 "));
        assertTrue(line, line.contains(" time.passive.avg_ms=800 "));
        assertTrue(line, line.contains(" time.frame.avg_ms=40 time.frame.max_ms=50 "));
        assertTrue(line, line.contains(" rate.retry=0.500 rate.failure=0.000"));
        assertFalse("zero failure counters are left out", line.contains("failures."));
        assertFalse(line.contains("\n"));
    }

    @Test
    public void summaryHasThreeShortLines() {
        LivenessMetrics metrics = new LivenessMetrics();
        metrics.sessionStarted(1);
        metrics.frameProcessed(20);

        List<String> lines = MetricsReport.summary(metrics.snapshot());

        assertEquals(3, lines.size());
        assertEquals("sessions 1 | passes 0 | retries 0 | frames 1", lines.get(0));
        assertTrue(lines.get(1), lines.get(1).contains("frame 20 (max 20)"));
        assertEquals("retry rate 0% | failure rate 0%", lines.get(2));
    }

    @Test
    public void logAppendsAndRotates() throws IOException {
        File file = new File(folder.getRoot(), "liveness/metrics.log");
        List<IOException> errors = new ArrayList<>();
        MetricsLog log = new MetricsLog(file, 20, errors::add);

        log.write("first line of the metrics log");
        log.write("second line");
        assertEquals("rotation happens before a write once the file is too big", "second line\n",
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertEquals("first line of the metrics log\n", new String(
                Files.readAllBytes(new File(file.getPath() + ".1").toPath()), StandardCharsets.UTF_8));
        assertTrue(errors.isEmpty());
        log.close();
    }

    @Test
    public void writeFailureReachesTheErrorCallbackOnly() throws IOException {
        File blocker = folder.newFile("not-a-folder");
        List<IOException> errors = new ArrayList<>();
        MetricsLog log = new MetricsLog(new File(blocker, "metrics.log"), 1024, errors::add);

        log.write("line");

        assertEquals(1, errors.size());
        log.close();
    }
}
