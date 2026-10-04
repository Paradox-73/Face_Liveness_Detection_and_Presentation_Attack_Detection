/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.LivenessMethod;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.config.PolicyMode;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-CORE-Q-01..03 (quality gate), T-CORE-DEC-01..10 (decision table) and the passive score aggregate. */
class QualityAndDecisionTest {

    private static final LivenessPolicy REG = TestPolicies.passiveFirst(Workflow.REGISTRATION);
    private final QualityGate gate = new QualityGate(REG, EngineParameters.defaults());

    private static FaceObservation one(TestFaces f) {
        return FaceObservation.of(640, 480, Collections.singletonList(f.build()));
    }

    @Test
    void goodFacePassesTheGate() {
        assertNull(gate.evaluate(one(TestFaces.neutral())));
        assertNull(gate.evaluate(one(TestFaces.neutral().noPose().noLandmarks())), "missing measures are skipped");
        DetectedFace bare = DetectedFace.builder(FaceBox.of(200, 150, 200, 220, 0.9)).build();
        assertNull(gate.evaluate(FaceObservation.of(640, 480, Collections.singletonList(bare))));
    }

    @Test
    void faceCountProblems() {
        assertEquals(ReasonCode.NO_FACE, gate.evaluate(FaceObservation.none(640, 480)));
        assertEquals(ReasonCode.MULTI_FACE, gate.evaluate(FaceObservation.of(640, 480,
                Arrays.asList(TestFaces.neutral().build(), TestFaces.neutral().shiftX(200).build()))));
    }

    @Test
    void positionSizeAndPoseProblems() {
        assertEquals(ReasonCode.POOR_QUALITY, gate.evaluate(one(TestFaces.neutral().confidence(0.3))));
        assertEquals(ReasonCode.FACE_OUT_OF_AREA, gate.evaluate(one(TestFaces.neutral().shiftX(300))));
        assertEquals(ReasonCode.FACE_TOO_SMALL, gate.evaluate(one(TestFaces.neutral().ied(59))));
        assertNull(gate.evaluate(one(TestFaces.neutral().ied(60))));
        assertEquals(ReasonCode.OFF_POSE, gate.evaluate(one(TestFaces.neutral().yaw(16))));
        assertNull(gate.evaluate(one(TestFaces.neutral().yaw(15))));
        assertEquals(ReasonCode.OFF_POSE, gate.evaluate(one(TestFaces.neutral().pitch(-21))));
    }

    @Test
    void imageProblems() {
        assertEquals(ReasonCode.TOO_DARK, gate.evaluate(one(TestFaces.neutral().brightness(30))));
        assertEquals(ReasonCode.TOO_BRIGHT, gate.evaluate(one(TestFaces.neutral().brightness(240))));
        assertEquals(ReasonCode.BLURRY, gate.evaluate(one(TestFaces.neutral().sharpness(2))));
    }

    @ParameterizedTest(name = "{0} score {1} -> {2}")
    @CsvSource({
        "PASSIVE_THEN_ACTIVE, 79, CHALLENGE",
        "PASSIVE_THEN_ACTIVE, 80, CAPTURE_READY",
        "PASSIVE_THEN_ACTIVE, 81, CAPTURE_READY",
        "PASSIVE_THEN_ACTIVE, 19, FAIL",
        "PASSIVE_THEN_ACTIVE, 20, CHALLENGE",
        "PASSIVE_ONLY, 79, FAIL",
        "PASSIVE_ONLY, 80, CAPTURE_READY",
        "ALWAYS_ACTIVE, 99, CHALLENGE",
        "ALWAYS_ACTIVE, 19, FAIL",
        "OFF, 0, CAPTURE_READY"
    })
    void decisionTableAroundThresholds(PolicyMode mode, int score, DecisionPolicy.Outcome expected) {
        LivenessPolicy p = REG.toBuilder().mode(mode).build();
        assertEquals(expected, DecisionPolicy.decide(p, score, false, true).outcome);
    }

    @Test
    void decisionDetails() {
        DecisionPolicy pad = DecisionPolicy.decide(REG, 19, false, true);
        assertEquals(FailureReason.PAD, pad.failureReason);
        assertEquals(ReasonCode.PAD_SCORE, pad.reason);
        DecisionPolicy integrity = DecisionPolicy.decide(REG, 99, true, true);
        assertEquals(FailureReason.INTEGRITY, integrity.failureReason);
        DecisionPolicy escalate = DecisionPolicy.decide(REG, 50, false, true);
        assertEquals(LivenessMethod.PASSIVE_ACTIVE, escalate.method);
        assertEquals(ReasonCode.BELOW_THRESHOLD, escalate.reason);
        assertEquals(LivenessMethod.PASSIVE, DecisionPolicy.decide(REG, 90, false, true).method);
        assertEquals(LivenessMethod.NONE, DecisionPolicy.decide(REG.toBuilder().mode(PolicyMode.OFF).build(), 0, false,
                false).method);
        DecisionPolicy always = DecisionPolicy.decide(REG.toBuilder().mode(PolicyMode.ALWAYS_ACTIVE).build(), 90, false, true);
        assertEquals(LivenessMethod.ACTIVE, always.method);
        assertEquals(ReasonCode.POLICY, always.reason);
        assertTrue(always.toString().contains("CHALLENGE"));
    }

    @Test
    void escalationNeedsActiveEnabledAndUsableChallenges() {
        LivenessPolicy noActive = REG.toBuilder().activeEnabled(false).build();
        assertEquals(FailureReason.BELOW_THRESHOLD, DecisionPolicy.decide(noActive, 50, false, true).failureReason);
        assertEquals(FailureReason.BELOW_THRESHOLD, DecisionPolicy.decide(REG, 50, false, false).failureReason);
        LivenessPolicy always = REG.toBuilder().mode(PolicyMode.ALWAYS_ACTIVE).activeEnabled(false).build();
        assertEquals(DecisionPolicy.Outcome.CHALLENGE, DecisionPolicy.decide(always, 90, false, true).outcome);
        assertEquals(FailureReason.ENGINE, DecisionPolicy.decide(always, 90, false, false).failureReason);
    }

    /** T-CORE-DEC-08 / 09: authentication workflows default to threshold 95. */
    @Test
    void authThresholdDefaults() {
        LivenessPolicy auth = TestPolicies.passiveFirst(Workflow.EOD_AUTH);
        assertEquals(DecisionPolicy.Outcome.CHALLENGE, DecisionPolicy.decide(auth, 94, false, true).outcome);
        assertEquals(DecisionPolicy.Outcome.CAPTURE_READY, DecisionPolicy.decide(auth, 95, false, true).outcome);
    }

    @Test
    void medianAndTrimmedMean() {
        PassivePadAggregator median = new PassivePadAggregator(5, 0.5);
        assertEquals(-1, median.score());
        for (double p : new double[] {0.9, 0.1, 0.8, 0.85, 0.95}) {
            median.add(p);
        }
        assertEquals(85, median.score());
        assertTrue(median.isFull());
        median.add(0.2);
        assertEquals(5, median.count(), "window keeps the most recent values");
        assertEquals(6, median.totalAdded());
        assertEquals(80, median.score());
        median.clear();
        assertEquals(0, median.count());

        PassivePadAggregator even = new PassivePadAggregator(4, 0.5);
        for (double p : new double[] {0.2, 0.4, 0.6, 0.8}) {
            even.add(p);
        }
        assertEquals(50, even.score());

        PassivePadAggregator trimmed = new PassivePadAggregator(10, 0.2);
        for (double p : new double[] {0.0, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 1.0}) {
            trimmed.add(p);
        }
        assertEquals(50, trimmed.score());
        assertEquals(0.5, PassivePadAggregator.trimmedMean(new double[] {0.5}, 0.4), 1e-9);
    }
}
