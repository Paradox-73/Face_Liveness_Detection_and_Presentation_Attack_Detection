package io.mosip.liveness.core.audit;

import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditTest {

    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");

    @Test
    void eventIdsAreLv01ToLv17InOrder() {
        AuditEventId[] ids = AuditEventId.values();
        assertEquals(17, ids.length);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.length; i++) {
            String expected = String.format("REG-EVT-LV%02d", i + 1);
            assertEquals(expected, ids[i].id());
            assertTrue(ids[i].id().length() <= 64);
            assertTrue(ids[i].eventName().startsWith("LIVENESS_"));
            assertTrue(seen.add(ids[i].id()));
            assertEquals(ids[i], AuditEventId.fromId(expected).orElse(null));
        }
        assertEquals("LIVENESS_PAD_ATTACK_SUSPECTED", AuditEventId.fromId("REG-EVT-LV08").get().eventName());
        assertEquals("LIVENESS_OVERRIDE_USED", AuditEventId.fromId("REG-EVT-LV16").get().eventName());
        assertFalse(AuditEventId.fromId("REG-EVT-104").isPresent());
        assertEquals("REG-MOD-153", AuditEventId.DESKTOP_COMPONENT_ID);
        assertEquals("REG-MOD-111", AuditEventId.ANDROID_COMPONENT_ID);
    }

    @Test
    void descriptionJsonKeepsOrderAndEscapes() {
        LivenessAuditEvent e = LivenessAuditEvent.builder(AuditEventId.LIVENESS_SESSION_STARTED, Workflow.REGISTRATION,
                "s1", T0)
                .put(AuditAttributes.ATTEMPT, 1)
                .put(AuditAttributes.ENGINE, "mosip-onnx-default@1.0.0")
                .put("note", "a\"b\\c\nd\u0001")
                .build();
        assertEquals("{\"workflow\":\"registration\",\"sessionId\":\"s1\",\"attempt\":\"1\","
                + "\"engine\":\"mosip-onnx-default@1.0.0\",\"note\":\"a\\\"b\\\\c\\nd\\u0001\"}", e.descriptionJson());
        assertEquals("REG-EVT-LV01 " + e.descriptionJson(), e.toString());
        assertEquals(3, e.attributes().size());
    }

    @Test
    void payloadLimitsKeepImagesOut() {
        LivenessAuditEvent.Builder b = LivenessAuditEvent.builder(AuditEventId.LIVENESS_PASSED, Workflow.LOGIN_AUTH, "s",
                T0);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= LivenessAuditEvent.MAX_VALUE_LENGTH; i++) {
            big.append('x');
        }
        assertThrows(IllegalArgumentException.class, () -> b.put("image", big.toString()));
        assertThrows(IllegalArgumentException.class, () -> b.put(AuditAttributes.WORKFLOW, "x"));
        assertThrows(IllegalArgumentException.class, () -> b.put("", "x"));
        for (int i = 0; i < 10; i++) {
            b.put("k" + i, big.substring(1));
        }
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    void scoresAreBucketed() {
        assertEquals("0-9", AuditAttributes.bucket(0));
        assertEquals("80-89", AuditAttributes.bucket(89));
        assertEquals("90-100", AuditAttributes.bucket(90));
        assertEquals("90-100", AuditAttributes.bucket(100));
        assertThrows(IllegalArgumentException.class, () -> AuditAttributes.bucket(101));
    }

    @Test
    void diagnosticLineHasOnlyTechnicalFields() {
        DiagnosticRecord r = DiagnosticRecord.builder("s1", SessionState.PASSIVE, 812).padScore(91).flag(ReasonCode.FROZEN)
                .fps(14.5).model("minifasnet_v2", "1.0.0").build();
        assertEquals("liveness session=s1 state=PASSIVE elapsedMs=812 pad=91 flags=FROZEN fps=14.5 models=minifasnet_v2@1.0.0",
                r.toLogLine());
        assertEquals("liveness session=s2 state=OPENING elapsedMs=0",
                DiagnosticRecord.builder("s2", SessionState.OPENING, 0).build().toLogLine());
        assertThrows(IllegalArgumentException.class, () -> DiagnosticRecord.builder("s", SessionState.IDLE, 0).padScore(-1));
    }

    @Test
    void noopSinksAcceptEverything() {
        AuditSink.noop().emit(LivenessAuditEvent.builder(AuditEventId.LIVENESS_TIMEOUT, Workflow.EOD_AUTH, "s", T0).build());
        DiagnosticSink.noop().log(DiagnosticRecord.builder("s", SessionState.FAILED, 1).build());
    }
}
