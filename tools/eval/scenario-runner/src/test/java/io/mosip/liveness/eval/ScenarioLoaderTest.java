package io.mosip.liveness.eval;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLoaderTest {

    private static Scenario parse(String yaml) {
        return ScenarioLoader.parse(new StringReader(yaml));
    }

    private static String error(String yaml) {
        return assertThrows(IllegalArgumentException.class, () -> parse(yaml)).getMessage();
    }

    @Test
    void readsAFullScenario() {
        Scenario sc = parse("id: atk-x\nkind: attack\nspecies: SYN-A7\nsubject: S3\ncondition: C1\n"
                + "workflow: login_auth\nseeds: [7, 9]\nfps: 10\nmax_stream_ms: 20000\n"
                + "config:\n  active.challenge_types: BLINK,SMILE\n  mosip.registration.liveness.max_retries: 2\n"
                + "  active.enable: true\n"
                + "engine_params:\n  planarity.min_residual: 0.03\n"
                + "stream:\n  main: m\n  at_end: loop\n  reaction_ms: 250\n  actions:\n    BLINK: b\n    SMILE: s\n"
                + "expect:\n  outcome: reject\n  layers: [passive_pad, challenge]\n");
        assertEquals("atk-x", sc.id());
        assertEquals(Scenario.Kind.ATTACK, sc.kind());
        assertEquals("SYN-A7", sc.species());
        assertEquals(Workflow.LOGIN_AUTH, sc.workflow());
        assertEquals(Arrays.asList(7L, 9L), sc.seeds());
        assertEquals(10, sc.fps());
        assertEquals(20_000L, sc.maxStreamMs());
        assertEquals("BLINK,SMILE", sc.config().get(LivenessConfigKeys.ACTIVE_CHALLENGE_TYPES));
        assertEquals("2", sc.config().get(LivenessConfigKeys.MAX_RETRIES));
        assertEquals(LivenessConfigKeys.YES, sc.config().get(LivenessConfigKeys.ACTIVE_ENABLE));
        assertEquals("0.03", sc.engineParams().get("planarity.min_residual"));
        assertEquals(Scenario.AtEnd.LOOP, sc.stream().atEnd());
        assertEquals(Scenario.PromptMode.MATCH, sc.stream().onPrompt());
        assertEquals(250L, sc.stream().reactionMs());
        assertEquals("b", sc.stream().actions().get(ChallengeType.BLINK));
        assertEquals(Outcome.REJECT, sc.expect().outcome());
        assertTrue(sc.expect().layers().contains(Layer.PASSIVE_PAD));
        assertEquals(3, sc.stream().packIds().size());
    }

    @Test
    void appliesDefaults() {
        Scenario sc = parse("id: bf-1\nkind: bona_fide\nstream:\n  main: m\n");
        assertEquals("BF", sc.species());
        assertEquals(Workflow.REGISTRATION, sc.workflow());
        assertEquals(Arrays.asList(1L), sc.seeds());
        assertEquals(ScenarioLoader.DEFAULT_FPS, sc.fps());
        assertEquals(Scenario.AtEnd.PINGPONG, sc.stream().atEnd());
        assertEquals(Scenario.PromptMode.IGNORE, sc.stream().onPrompt());
        assertEquals(ScenarioLoader.DEFAULT_REACTION_MS, sc.stream().reactionMs());
        assertNull(sc.expect().outcome());
    }

    @Test
    void seedAndRepeatGiveConsecutiveSeeds() {
        Scenario sc = parse("id: a\nkind: other\nseed: 40\nrepeat: 3\nstream: {main: m}\n");
        assertEquals(Arrays.asList(40L, 41L, 42L), sc.seeds());
        assertEquals("OTHER", sc.species());
    }

    @Test
    void rejectsTyposAndInconsistentFields() {
        assertTrue(error("id: a\nkind: attack\nspecies: X\nstream: {main: m}\nexpected: {outcome: reject}\n")
                .contains("unknown key in scenario: expected"));
        assertTrue(error("id: a\nkind: attack\nstream: {main: m}\n").contains("species is required"));
        assertTrue(error("id: A B\nkind: other\nstream: {main: m}\n").contains("id must be"));
        assertTrue(error("id: a\nkind: human\nstream: {main: m}\n").contains("kind"));
        assertTrue(error("id: a\nkind: other\nworkflow: payroll\nstream: {main: m}\n").contains("unknown workflow"));
        assertTrue(error("id: a\nkind: other\nseeds: [1]\nrepeat: 2\nstream: {main: m}\n").contains("either"));
        assertTrue(error("id: a\nkind: other\nfps: 0\nstream: {main: m}\n").contains("fps"));
        assertTrue(error("id: a\nkind: other\nstream: {main: m, on_prompt: match}\n").contains("needs stream.actions"));
        assertTrue(error("id: a\nkind: other\nstream: {main: m, on_prompt: mismatch, actions: {BLINK: b}}\n")
                .contains("at least two"));
        assertTrue(error("id: a\nkind: other\nstream: {main: m, on_prompt: fixed}\n").contains("fixed_pack"));
        assertTrue(error("id: a\nkind: other\nstream: {main: m, actions: {WINK: w}}\n").contains("stream.actions"));
        assertTrue(error("id: a\nkind: other\nengine_params: {x: fast}\nstream: {main: m}\n").contains("number"));
        assertTrue(error("id: a\nkind: other\nstream: {main: m}\nexpect: {outcome: maybe}\n").contains("expect.outcome"));
        assertTrue(error("id: a\nkind: other\n").contains("stream must be a mapping"));
    }

    @Test
    void expandsShortConfigKeys() {
        assertEquals(LivenessConfigKeys.PREFIX + "max_retries", ScenarioLoader.fullKey("max_retries"));
        assertEquals("mosip.registration.mds.allowed_certifications",
                ScenarioLoader.fullKey("mosip.registration.mds.allowed_certifications"));
    }
}
