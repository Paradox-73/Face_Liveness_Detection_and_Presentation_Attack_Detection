package io.mosip.liveness.eval;

import io.mosip.liveness.core.ChallengeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs scenarios end to end through the runner with {@link ScriptedEngine} instead of the ONNX models. */
class ScenarioRunnerTest {

    @TempDir
    Path packs;

    private ScriptedEngine engine;
    private List<Map<String, String>> requestedParams;
    private int timeoutFrames = 20;

    @BeforeEach
    void setUp() throws IOException {
        pack("main", 30);
        pack("blink", 6);
        pack("smile", 6);
        pack("blank", 10);
        pack("dark", 10);
        pack("short", 4);
        StageTimer timer = new StageTimer();
        engine = new ScriptedEngine(timer);
        requestedParams = new ArrayList<>();
    }

    /** Writes a pack whose frame files contain the text "id#index", which ScriptedEngine reads back. */
    private void pack(String id, int frames) throws IOException {
        Path dir = Files.createDirectories(packs.resolve(id));
        for (int i = 0; i < frames; i++) {
            Files.write(dir.resolve(String.format("frame_%05d.jpg", i)),
                    (id + "#" + i).getBytes(StandardCharsets.US_ASCII));
        }
    }

    private Result run(String yaml) throws Exception {
        return run(yaml, true);
    }

    private Result run(String yaml, boolean trackLowLight) throws Exception {
        Scenario sc = ScenarioLoader.parse(new StringReader(yaml));
        StageTimer timer = new StageTimer();
        engine = new ScriptedEngine(timer);
        engine.challenge = ChallengeType.BLINK;
        engine.timeoutFrames = timeoutFrames;
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("os", "test");
        ScenarioRunner runner = new ScenarioRunner(p -> {
            requestedParams.add(p);
            return new AttemptDriver(engine, timer, ScriptedEngine.decoder());
        }, new FramePackStore(packs), Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(),
                new AttemptRecord.RunInfo("run-1", "1.0.0", "abc", platform), new PrintStream(log, true, "UTF-8"),
                trackLowLight);
        StringWriter jsonl = new StringWriter();
        SummaryTable summary = new SummaryTable();
        runner.run(sc, jsonl, summary);
        return new Result(jsonl.toString(), summary, log.toString("UTF-8"));
    }

    private static final class Result {
        final List<String> lines;
        final SummaryTable summary;
        final String log;

        Result(String jsonl, SummaryTable summary, String log) {
            this.lines = Arrays.asList(jsonl.trim().split("\n"));
            this.summary = summary;
            this.log = log;
        }

        String last() {
            return lines.get(lines.size() - 1);
        }
    }

    @Test
    void bonaFideAnswersThePromptAndPasses() throws Exception {
        Result r = run("id: bf\nkind: bona_fide\nsubject: S9\nstream:\n  main: main\n  reaction_ms: 0\n"
                + "  actions: {BLINK: blink, SMILE: smile}\nexpect: {outcome: accept}\n");
        assertEquals(1, r.lines.size());
        String line = r.last();
        assertTrue(line.contains("\"outcome\":\"ACCEPT\""), line);
        assertTrue(line.contains("\"txn_outcome\":\"ACCEPT\""), line);
        assertTrue(line.contains("\"prompts\":[{\"type\":\"BLINK\",\"pack\":\"blink\""), line);
        assertTrue(line.contains("\"frames_per_pack\":{\"main\":3,\"blink\":2}"), line);
        assertTrue(line.contains("\"subject_id\":\"S9\""), line);
        assertTrue(line.contains("\"layer\":\"none\""), line);
        assertEquals(0, r.summary.mismatches());
        assertEquals(1, engine.sessionsOpened);
    }

    @Test
    void wrongClipReplayIsRejectedOnEveryAttempt() throws Exception {
        Result r = run("id: atk\nkind: attack\nspecies: SYN-A9\nstream:\n  main: main\n  on_prompt: mismatch\n"
                + "  actions: {BLINK: blink, SMILE: smile}\nexpect: {outcome: reject, layers: [challenge]}\n");
        assertEquals(3, r.lines.size(), "shipped max_retries is 3");
        for (int i = 0; i < 3; i++) {
            String line = r.lines.get(i);
            assertTrue(line.contains("\"attempt_no\":" + (i + 1)), line);
            assertTrue(line.contains("\"outcome\":\"REJECT\""), line);
            assertTrue(line.contains("\"pack\":\"smile\""), line);
            assertEquals(i == 2, line.contains("\"last_in_txn\":true"), line);
        }
        assertTrue(r.last().contains("\"txn_outcome\":\"REJECT\""));
        assertTrue(r.last().contains("\"reason_codes\":[\"CHALLENGE_TIMEOUT\"]"));
        assertEquals(0, r.summary.mismatches());
        assertTrue(r.log.contains("REJECT"));
    }

    @Test
    void scenarioConfigChangesTheRetryBudget() throws Exception {
        Result r = run("id: atk\nkind: attack\nspecies: X\nconfig: {max_retries: 2}\nstream: {main: main}\n"
                + "expect: {outcome: reject}\n");
        assertEquals(2, r.lines.size());
        assertTrue(r.last().contains("\"max_attempts\":2"));
    }

    @Test
    void noFaceIsANoResponseAndDoesNotUseUpAttempts() throws Exception {
        Result r = run("id: q\nkind: other\nstream: {main: blank}\nexpect: {outcome: no_response, layers: [quality]}\n");
        assertEquals(3 + ScenarioRunner.EXTRA_SESSIONS, r.lines.size());
        assertTrue(r.last().contains("\"txn_outcome\":\"NO_RESPONSE\""));
        assertTrue(r.last().contains("\"no_response_reason\":\"NO_FACE\""), r.last());
        assertEquals(0, r.summary.mismatches());
    }

    @Test
    void streamEndIsADeviceError() throws Exception {
        Result r = run("id: q\nkind: other\nrepeat: 1\nstream: {main: short, at_end: end}\n"
                + "expect: {outcome: no_response, layers: [error]}\n");
        assertTrue(r.last().contains("\"no_response_reason\":\"DEVICE_DISCONNECTED\""), r.last());
        assertEquals(0, r.summary.mismatches());
    }

    @Test
    void runnerTimeLimitCancelsAStuckAttempt() throws Exception {
        timeoutFrames = 1_000;
        Result r = run("id: q\nkind: other\nmax_stream_ms: 1000\nconfig: {max_retries: 1}\nstream: {main: main}\n");
        assertEquals(1 + ScenarioRunner.EXTRA_SESSIONS, r.lines.size(), "cancelled sessions do not count");
        assertTrue(r.last().contains("\"no_response_reason\":\"RUNNER_TIME_LIMIT\""), r.last());
        assertTrue(r.last().contains("\"stream_ms\":1000"), r.last());
    }

    @Test
    void expectationMismatchesAreCounted() throws Exception {
        Result r = run("id: atk\nkind: attack\nspecies: X\nrepeat: 2\nstream:\n  main: main\n  reaction_ms: 0\n"
                + "  actions: {BLINK: blink}\nexpect: {outcome: reject}\n");
        assertEquals(2, r.summary.mismatches());
        assertTrue(r.summary.toMarkdown().contains("**NO**"));
        assertTrue(r.log.contains("MISMATCH"));
    }

    @Test
    void wrongLayerIsAMismatchEvenWithTheRightOutcome() throws Exception {
        Result r = run("id: atk\nkind: attack\nspecies: X\nstream: {main: main}\n"
                + "expect: {outcome: reject, layers: [integrity]}\n");
        assertEquals(1, r.summary.mismatches());
    }

    @Test
    void engineParametersArePassedThrough() throws Exception {
        run("id: a\nkind: other\nengine_params: {planarity.min_residual: 0.05}\nstream: {main: blank}\n");
        assertEquals("0.05", requestedParams.get(0).get("planarity.min_residual"));
    }

    @Test
    void recordsHoldNoFrameContent() throws Exception {
        Result r = run("id: bf\nkind: bona_fide\nstream:\n  main: main\n  actions: {BLINK: blink}\n");
        for (String line : r.lines) {
            assertFalse(line.contains("main#"), "frame bytes must never reach the records: " + line);
            assertFalse(line.contains("\"image"), line);
            assertFalse(line.contains("embedding"), line);
        }
    }

    @Test
    void lowLightEndingsAreTrackedAndTheFourthPadFailureCounts() throws Exception {
        Result r = run("id: dim\nkind: bona_fide\nstream: {main: dark}\nexpect: {outcome: accept}\n");
        assertEquals(Arrays.asList(0, 1, 2, 3), engine.lowLightSeen.subList(0, 4));
        for (int i = 0; i < 3; i++) {
            String line = r.lines.get(i);
            assertTrue(line.contains("\"outcome\":\"NO_RESPONSE\""), line);
            assertTrue(line.contains("\"no_response_reason\":\"LOW_LIGHT\""), line);
        }
        assertTrue(r.lines.get(3).contains("\"outcome\":\"REJECT\""), r.lines.get(3));
        assertTrue(r.lines.get(3).contains("\"reason_codes\":[\"PAD_SCORE\"]"), r.lines.get(3));
        assertTrue(r.lines.size() > 4, "a counted failure restarts the low-light count, so more sessions follow");
        assertEquals(Integer.valueOf(0), engine.lowLightSeen.get(4));
    }

    @Test
    void noLowLightTrackingPassesNoCountToTheSessions() throws Exception {
        Result r = run("id: dim\nkind: bona_fide\nstream: {main: dark}\nexpect: {outcome: accept}\n", false);
        assertEquals(3, r.lines.size());
        for (int seen : engine.lowLightSeen) {
            assertEquals(io.mosip.liveness.core.api.SessionRequest.LOW_LIGHT_NOT_TRACKED, seen);
        }
        for (String line : r.lines) {
            assertTrue(line.contains("\"reason_codes\":[\"PAD_SCORE\"]"), line);
        }
    }
}
