/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.SessionState;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.BindingVerdict;
import io.mosip.liveness.core.api.ChallengeEchoStatus;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.api.LivenessResult;
import io.mosip.liveness.core.audit.AuditAttributes;
import io.mosip.liveness.core.audit.AuditEventId;
import io.mosip.liveness.core.audit.DiagnosticRecord;
import io.mosip.liveness.core.audit.LivenessAuditEvent;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.OnMaxFailure;
import io.mosip.liveness.core.device.DeviceErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit payloads (T-CORE-AUD-01), diagnostics (AUD-02), metrics (MET-01..05), retries (TX-01..02), parameters. */
class ReportingTest {

    private final FakeClock clock = new FakeClock();
    private final AuditPayloadBuilder audit = new AuditPayloadBuilder(Workflow.PACKET_AUTH, "sess-1", clock);

    @Test
    void everyAuditEventCarriesItsAttributes() {
        List<LivenessAuditEvent> events = Arrays.asList(
                audit.sessionStarted(2, SessionHarness.device(true, true), SessionHarness.engineInfo()),
                audit.passivePassed(96, 8, 1234),
                audit.activeTriggered(64, ReasonCode.BELOW_THRESHOLD),
                audit.challengeIssued(ChallengeType.BLINK, 0, "a-random-nonce-value"),
                audit.challengePassed(ChallengeType.BLINK, 420, 150),
                audit.challengeFailed(ChallengeType.SMILE, ReasonCode.WRONG_ACTION),
                audit.timeout(SessionState.CHALLENGE_OBSERVE),
                audit.padAttackSuspected(ReasonCode.LOOP),
                audit.bindingFailed(BindingVerdict.failed(FailureReason.BINDING, ReasonCode.SIMILARITY_LOW,
                        ReasonCode.OUTSIDE_TIME_WINDOW).similarity(41).gapMs(9000)
                        .challengeEcho(ChallengeEchoStatus.MISMATCHED).build()),
                audit.passed(LivenessMethod.PASSIVE_ACTIVE, 6100),
                audit.maxRetries(3, OnMaxFailure.BLOCK_AND_LOCK),
                audit.deviceError(DeviceErrorCode.STALLED),
                audit.engineError(EngineErrorCode.INFERENCE, "EngineException"),
                audit.modelIntegrityFailed("minifasnet_v2"),
                audit.configApplied("mosip.registration.liveness.passive.threshold"),
                audit.overrideUsed("supervisor1"),
                audit.diagnosticMode("admin", true));
        for (int i = 0; i < events.size(); i++) {
            assertEquals(AuditEventId.values()[i], events.get(i).eventId());
            String json = events.get(i).descriptionJson();
            assertTrue(json.startsWith("{\"workflow\":\"packet_auth\",\"sessionId\":\"sess-1\""), json);
            assertTrue(json.length() <= LivenessAuditEvent.MAX_DESCRIPTION_LENGTH);
        }
        assertEquals("90-100", events.get(1).attributes().get(AuditAttributes.SCORE_BUCKET));
        assertFalse(events.get(1).descriptionJson().contains("96"), "raw scores never reach audits");
        assertEquals(FrameFingerprint.sha256Prefix("a-random-nonce-value"),
                events.get(3).attributes().get(AuditAttributes.NONCE_PREFIX));
        assertFalse(events.get(3).descriptionJson().contains("a-random-nonce-value"));
        Map<String, String> binding = events.get(8).attributes();
        assertEquals("40-49", binding.get(AuditAttributes.SIMILARITY_BUCKET));
        assertEquals("false", binding.get(AuditAttributes.CHALLENGE_ECHO_OK));
        assertEquals("OUTSIDE_TIME_WINDOW,SIMILARITY_LOW", binding.get(AuditAttributes.REASON));
        assertEquals("n/a", audit.bindingFailed(BindingVerdict.failed(FailureReason.BINDING, ReasonCode.TOKEN_MISSING)
                .build()).attributes().get(AuditAttributes.CHALLENGE_ECHO_OK));
        assertEquals("L0", events.get(0).attributes().get(AuditAttributes.CERTIFICATION));
        assertFalse(audit.engineError(EngineErrorCode.INTERNAL, null).attributes().containsKey(AuditAttributes.ERROR_CLASS));
        char[] longText = new char[400];
        Arrays.fill(longText, 'k');
        assertEquals(256, audit.configApplied(new String(longText)).attributes().get(AuditAttributes.CHANGED).length());
    }

    /** T-CORE-AUD-01: a whole session's audits carry no image bytes, subject reference or raw score. */
    @Test
    void sessionAuditsHoldNoBiometricsOrIdentifiers() {
        SessionHarness h = SessionScenarioTest.escalated(TestPolicies.passiveFirst(Workflow.REGISTRATION));
        h.runActive(false);
        h.captureAndBind();
        assertEquals(SessionState.PASSED, h.state());
        for (LivenessAuditEvent e : h.listener.audits) {
            String json = e.descriptionJson();
            assertFalse(json.contains(SessionHarness.SUBJECT), json);
            assertTrue(json.length() < 400, json);
            for (String v : e.attributes().values()) {
                assertTrue(v.length() <= 64, "short values only: " + v);
            }
        }
    }

    /** T-CORE-AUD-02: diagnostic lines hold technical signals only. */
    @Test
    void diagnosticLinesHoldNoIdentifiers() {
        SessionHarness h = new SessionHarness(TestPolicies.passiveFirst(Workflow.REGISTRATION).toBuilder()
                .diagnosticEnabled(true).build());
        h.start();
        h.runPassive(TestFaces.neutral());
        h.captureAndBind();
        assertTrue(h.diagnostics.size() >= 7, "one record per state change");
        for (DiagnosticRecord r : h.diagnostics) {
            String line = r.toLogLine();
            assertFalse(line.contains(SessionHarness.SUBJECT), line);
            assertTrue(line.startsWith("liveness session=" + h.session.sessionId()), line);
            assertTrue(line.contains("models=minifasnet_v2@1.0.0"), line);
        }
        assertEquals(SessionState.PASSED, h.diagnostics.get(h.diagnostics.size() - 1).state());
        assertTrue(h.diagnostics.get(h.diagnostics.size() - 1).padScore().isPresent());

        SessionHarness off = new SessionHarness(TestPolicies.passiveFirst(Workflow.REGISTRATION)).start();
        off.runPassive(TestFaces.neutral());
        assertTrue(off.diagnostics.isEmpty(), "diagnostics are off by default");
    }

    @Test
    void diagnosticLogSwallowsSinkFailures() {
        DiagnosticLog log = new DiagnosticLog(r -> {
            throw new IllegalStateException("disk full");
        }, true, "s", SessionHarness.engineInfo());
        log.stateChanged(SessionState.PASSIVE, 10, 55, Collections.singletonList(ReasonCode.FROZEN), 0.03, 70, 14.2,
                "reason=X");
        List<DiagnosticRecord> out = new ArrayList<>();
        DiagnosticLog ok = new DiagnosticLog(out::add, true, "s", SessionHarness.engineInfo());
        char[] longDetail = new char[300];
        Arrays.fill(longDetail, 'd');
        ok.stateChanged(SessionState.PASSIVE, -5, 101, Collections.<ReasonCode>emptyList(), Double.NaN, -1, 0,
                new String(longDetail));
        assertEquals(1, out.size());
        assertFalse(out.get(0).padScore().isPresent());
        assertEquals(0, out.get(0).elapsedMs());
        assertEquals(DiagnosticRecord.MAX_DETAIL_LENGTH, out.get(0).detail().length());
        assertTrue(ok.isEnabled());
    }

    @Test
    void metricsCountersTimersAndRates() {
        LivenessMetrics m = new LivenessMetrics();
        m.sessionStarted(1);
        m.passivePassed(900);
        m.sessionEnded(result(null, 1000));
        m.sessionStarted(1);
        m.activeTriggered();
        m.challengeCompleted(1500);
        m.challengeCompleted(2500);
        m.sessionEnded(result(FailureReason.ACTIVE, 7000));
        m.sessionStarted(2);
        m.sessionEnded(result(FailureReason.CANCELLED, 100));
        m.frameProcessed(10);
        m.frameProcessed(30);
        LivenessMetrics.Snapshot s = m.snapshot();
        assertEquals(2, s.counter(LivenessMetrics.FRAMES_ANALYSED));
        assertEquals(20, s.averageFrameMs(), 1e-9);
        assertEquals(30, s.maxFrameMs());
        assertEquals(3, s.counter(LivenessMetrics.SESSIONS));
        assertEquals(1, s.counter(LivenessMetrics.RETRIES));
        assertEquals(1, s.counter(LivenessMetrics.PASSES));
        assertEquals(1, s.counter(LivenessMetrics.FAILURES_PREFIX + "ACTIVE"));
        assertEquals(2, s.counter(LivenessMetrics.CHALLENGES_PASSED));
        assertEquals(0, s.counter("no.such.counter"));
        assertEquals(2700, s.averageTotalMs(), 1e-9);
        assertEquals(900, s.averagePassiveMs(), 1e-9);
        assertEquals(2000, s.averageChallengeMs(), 1e-9);
        assertEquals(7000, s.maxTotalMs());
        assertEquals(1 / 3.0, s.retryRate(), 1e-9);
        assertEquals(1 / 3.0, s.failureRate(), 1e-9);
        assertTrue(s.toString().contains("liveness.sessions"));
        Pattern safe = Pattern.compile("liveness\\.[a-z_]+(\\.[A-Z_]+)?");
        for (String name : s.counters().keySet()) {
            assertTrue(safe.matcher(name).matches(), "T-CORE-MET-02: no identifiers in metric names: " + name);
        }
        LivenessMetrics.Snapshot empty = new LivenessMetrics().snapshot();
        assertEquals(0, empty.failureRate(), 0);
        assertEquals(0, empty.averageTotalMs(), 0);
    }

    private static LivenessResult result(FailureReason reason, long totalMs) {
        LivenessResult.Builder b = LivenessResult.builder("s", Workflow.REGISTRATION, SessionHarness.engineInfo(),
                SessionHarness.device(false, true)).method(LivenessMethod.PASSIVE)
                .period(FakeClock.EPOCH, FakeClock.EPOCH).timings(0, 0, totalMs);
        return reason == null ? b.pass().build() : b.fail(reason).build();
    }

    /** T-CORE-TX-01: only counted failures use an attempt. */
    @Test
    void retryCounting() {
        LivenessTransaction tx = new LivenessTransaction(TestPolicies.passiveFirst(Workflow.REGISTRATION));
        assertEquals(1, tx.nextAttempt());
        tx.record(result(FailureReason.DEVICE, 1));
        tx.record(result(FailureReason.QUALITY, 1));
        tx.record(result(FailureReason.CANCELLED, 1));
        tx.record(result(FailureReason.ENGINE, 1));
        assertEquals(1, tx.nextAttempt(), "uncounted failures keep the attempt");
        assertEquals(LivenessTransaction.Status.IN_PROGRESS, tx.record(result(FailureReason.TIMEOUT, 1)));
        assertEquals(2, tx.nextAttempt());
        assertEquals(LivenessTransaction.Status.PASSED, tx.record(result(null, 1)));
        assertFalse(tx.canRetry());
        assertEquals(6, tx.sessions());
        assertThrows(IllegalStateException.class, tx::nextAttempt);
        assertThrows(IllegalStateException.class, () -> tx.record(result(null, 1)));
        assertEquals(FailureReason.TIMEOUT, tx.lastFailure().get());
    }

    /** T-CORE-TX-02: {@code <workflow>.on_max_failure}. */
    @Test
    void behaviourAfterTheLastAttempt() {
        LivenessPolicy resident = TestPolicies.passiveFirst(Workflow.REGISTRATION).toBuilder()
                .onMaxFailure(OnMaxFailure.SUPERVISOR_OVERRIDE).maxRetries(2).build();
        LivenessTransaction attack = new LivenessTransaction(resident);
        attack.record(result(FailureReason.ACTIVE, 1));
        assertEquals(LivenessTransaction.Status.EXHAUSTED, attack.record(result(FailureReason.INTEGRITY, 1)));
        assertFalse(attack.isOverrideAllowed(), "never after a suspected attack");
        assertFalse(attack.isLockRequired());
        assertEquals(OnMaxFailure.SUPERVISOR_OVERRIDE, attack.onMaxFailure());

        LivenessPolicy auth = TestPolicies.passiveFirst(Workflow.LOGIN_AUTH).toBuilder()
                .onMaxFailure(OnMaxFailure.BLOCK_AND_LOCK).maxRetries(1).build();
        LivenessTransaction login = new LivenessTransaction(auth);
        LivenessResult authFail = LivenessResult.builder("s", Workflow.LOGIN_AUTH, SessionHarness.engineInfo(),
                SessionHarness.device(false, true)).method(LivenessMethod.PASSIVE)
                .period(FakeClock.EPOCH, FakeClock.EPOCH).fail(FailureReason.PAD).build();
        login.record(authFail);
        assertTrue(login.isLockRequired());
        assertFalse(login.isOverrideAllowed());
        assertThrows(IllegalArgumentException.class,
                () -> new LivenessTransaction(auth).record(result(FailureReason.PAD, 1)));
    }

    @Test
    void engineParameters() {
        EngineParameters d = EngineParameters.defaults();
        assertEquals(20, d.get(EngineParameter.TURN_YAW_DEG), 0);
        assertEquals(5, d.getInt(EngineParameter.ACQUIRE_CONSECUTIVE_FRAMES));
        assertTrue(d.isOn(EngineParameter.PLANARITY_ENABLED));
        Map<String, Object> m = new HashMap<>();
        m.put("active.turn_yaw_deg", 25);
        m.put("planarity.min_residual", "0.03");
        EngineParameters p = EngineParameters.fromMap(m);
        assertEquals(25, p.get(EngineParameter.TURN_YAW_DEG), 0);
        assertEquals(0.03, p.get(EngineParameter.PLANARITY_MIN_RESIDUAL), 1e-12);
        assertEquals(p, EngineParameters.fromMap(m));
        assertEquals(p.hashCode(), EngineParameters.fromMap(m).hashCode());
        assertEquals(EngineParameter.values().length, p.asMap().size());
        assertTrue(p.toString().contains("active.turn_yaw_deg"));
        assertThrows(IllegalArgumentException.class,
                () -> EngineParameters.fromMap(Collections.singletonMap("no.such", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> EngineParameters.fromMap(Collections.singletonMap("active.turn_yaw_deg", "wide")));
        assertThrows(IllegalArgumentException.class,
                () -> EngineParameters.fromMap(Collections.singletonMap("active.turn_yaw_deg", Boolean.TRUE)));
        assertThrows(IllegalArgumentException.class, () -> EngineParameters.builder().set(EngineParameter.TURN_YAW_DEG, 90));
        assertThrows(IllegalArgumentException.class,
                () -> EngineParameters.builder().set(EngineParameter.ACQUIRE_CONSECUTIVE_FRAMES, 2.5));
        assertThrows(IllegalArgumentException.class, () -> EngineParameters.builder()
                .set(EngineParameter.CHALLENGE_DELAY_MIN_MS, 2000).set(EngineParameter.CHALLENGE_DELAY_MAX_MS, 1000).build());
        for (EngineParameter e : EngineParameter.values()) {
            assertTrue(e.defaultValue() >= e.min() && e.defaultValue() <= e.max(), e.key());
            assertEquals(e, EngineParameter.fromKey(e.key()));
        }
    }
}
