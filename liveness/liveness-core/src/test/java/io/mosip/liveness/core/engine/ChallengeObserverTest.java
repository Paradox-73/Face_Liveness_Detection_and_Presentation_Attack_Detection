/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.ChallengeOutcome;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-CORE-ACT-01..12: action detectors on synthetic landmark sequences at 20 fps. */
class ChallengeObserverTest {

    private static final long MS = 1_000_000L;
    private static final LivenessPolicy POLICY = TestPolicies.passiveFirst(Workflow.REGISTRATION);
    private static final List<ChallengeType> DETECTABLE = Arrays.asList(ChallengeType.BLINK, ChallengeType.SMILE,
            ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT, ChallengeType.LOOK_UP, ChallengeType.LOOK_DOWN);

    private long now;

    private ChallengeObserver observe(ChallengeType target) {
        return observe(target, DETECTABLE);
    }

    private ChallengeObserver observe(ChallengeType target, List<ChallengeType> detectable) {
        return observe(target, detectable, EngineParameters.defaults());
    }

    private ChallengeObserver observe(ChallengeType target, List<ChallengeType> detectable, EngineParameters params) {
        NeutralBaseline b = new NeutralBaseline();
        for (int i = 0; i < 6; i++) {
            b.add(FaceSignals.of(TestFaces.neutral().build()));
        }
        List<ChallengeType> contrary = new java.util.ArrayList<>();
        for (ChallengeType t : detectable) {
            if (t != target && t != ChallengeType.BLINK && !(target.isHeadMovement() && t.isGaze())) {
                contrary.add(t);
            }
        }
        now = 10_000 * MS;
        return new ChallengeObserver(target, b, contrary, now, POLICY, params);
    }

    private ChallengeObserver.Status feed(ChallengeObserver o, int frames, TestFaces face) {
        ChallengeObserver.Status s = o.status();
        for (int i = 0; i < frames && s == ChallengeObserver.Status.PENDING; i++) {
            now += 50 * MS;
            s = o.onFrame(now, FaceSignals.of(face.build()));
        }
        return s;
    }

    @ParameterizedTest
    @EnumSource(value = ChallengeType.class, names = {"TURN_LEFT", "TURN_RIGHT", "LOOK_UP", "LOOK_DOWN", "SMILE"})
    void truePositivePerAction(ChallengeType type) {
        ChallengeObserver o = observe(type);
        feed(o, 6, TestFaces.neutral());
        assertEquals(ChallengeObserver.Status.PASSED, feed(o, 10, TestFaces.acting(type)));
        assertEquals(ChallengeOutcome.PASSED, o.outcome());
        assertEquals(350, o.onsetMs());
        assertTrue(o.holdMs() >= 300, "hold " + o.holdMs());
        assertNull(o.reason());
    }

    @Test
    void blinkClosesAndReopens() {
        ChallengeObserver o = observe(ChallengeType.BLINK);
        feed(o, 6, TestFaces.neutral());
        feed(o, 3, TestFaces.acting(ChallengeType.BLINK));
        assertEquals(ChallengeObserver.Status.PASSED, feed(o, 2, TestFaces.neutral()));
        assertEquals(150, o.holdMs(), "closed time");
    }

    @Test
    void eyesKeptClosedIsNotABlink() {
        ChallengeObserver o = observe(ChallengeType.BLINK);
        feed(o, 6, TestFaces.neutral());
        feed(o, 14, TestFaces.acting(ChallengeType.BLINK));
        assertEquals(ChallengeObserver.Status.PENDING, feed(o, 3, TestFaces.neutral()));
        feed(o, 200, TestFaces.neutral());
        assertEquals(ChallengeOutcome.NOT_HELD, o.outcome(), "reached the trigger but was never a valid blink");
    }

    @Test
    void smileFromAClassifierScore() {
        ChallengeObserver o = observe(ChallengeType.SMILE);
        feed(o, 6, TestFaces.neutral());
        NeutralBaseline noMouth = new NeutralBaseline();
        ChallengeObserver scoreOnly = new ChallengeObserver(ChallengeType.SMILE, noMouth, Collections.<ChallengeType>emptyList(),
                now, POLICY, EngineParameters.defaults());
        ChallengeObserver.Status s = ChallengeObserver.Status.PENDING;
        for (int i = 1; i <= 16 && s == ChallengeObserver.Status.PENDING; i++) {
            DetectedFace f = DetectedFace.builder(FaceBox.of(0, 0, 100, 100, 1)).smileScore(i <= 6 ? 0.1 : 0.9).build();
            s = scoreOnly.onFrame(now + i * 50 * MS, FaceSignals.of(f));
        }
        assertEquals(ChallengeObserver.Status.PASSED, s);
    }

    @Test
    void gazeWithoutTurningTheHead() {
        List<ChallengeType> detectable = Arrays.asList(ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT,
                ChallengeType.GAZE_LEFT, ChallengeType.GAZE_RIGHT);
        ChallengeObserver o = observe(ChallengeType.GAZE_RIGHT, detectable);
        ChallengeObserver.Status s = ChallengeObserver.Status.PENDING;
        for (int i = 1; i <= 20 && s == ChallengeObserver.Status.PENDING; i++) {
            double gaze = i <= 6 ? 0 : -0.5;
            s = o.onFrame(now + i * 50 * MS, FaceSignals.of(face(0, gaze)));
        }
        assertEquals(ChallengeObserver.Status.PASSED, s);

        ChallengeObserver turned = observe(ChallengeType.GAZE_LEFT, detectable);
        s = ChallengeObserver.Status.PENDING;
        for (int i = 1; i <= 20 && s == ChallengeObserver.Status.PENDING; i++) {
            s = turned.onFrame(now + i * 50 * MS, FaceSignals.of(face(i <= 6 ? 0 : 25, i <= 6 ? 0 : 0.5)));
        }
        assertEquals(ChallengeOutcome.WRONG_ACTION, turned.outcome(), "turning the head is not a gaze");
    }

    private static DetectedFace face(double yaw, double gaze) {
        return DetectedFace.builder(FaceBox.of(0, 0, 100, 100, 1))
                .pose(io.mosip.liveness.core.vision.HeadPose.of(yaw, 0, 0)).gaze(gaze, 0).build();
    }

    @Test
    void wrongDirectionFails() {
        ChallengeObserver o = observe(ChallengeType.TURN_LEFT);
        feed(o, 6, TestFaces.neutral());
        assertEquals(ChallengeObserver.Status.FAILED, feed(o, 5, TestFaces.acting(ChallengeType.TURN_RIGHT)));
        assertEquals(ChallengeOutcome.WRONG_ACTION, o.outcome());
        assertEquals(ReasonCode.WRONG_ACTION, o.reason());
        assertEquals(FailureReason.ACTIVE, o.failureReason());
    }

    @Test
    void contraryActionDuringTheWindowFails() {
        ChallengeObserver o = observe(ChallengeType.TURN_LEFT);
        feed(o, 6, TestFaces.neutral());
        assertEquals(ChallengeObserver.Status.FAILED, feed(o, 4, TestFaces.acting(ChallengeType.SMILE)));
        assertEquals(ChallengeOutcome.WRONG_ACTION, o.outcome());
    }

    /**
     * A genuine head turn without a smile: the mouth width ratio (mouth width over inter-eye distance) rises with
     * the yaw because the eyes come closer in the image faster than the mouth corners, +27% at 45 degrees as in
     * the S2 turn packs of the scenario suite. Both directions must pass.
     */
    @ParameterizedTest
    @EnumSource(value = ChallengeType.class, names = {"TURN_LEFT", "TURN_RIGHT"})
    void headTurnWhoseMouthRatioGrowsWithYawIsNotASmile(ChallengeType turn) {
        ChallengeObserver o = observe(turn);
        assertEquals(ChallengeObserver.Status.PASSED, turnWithWideningMouth(o, turn));
    }

    /** Without the yaw limit on the smile measure the same turn fails as a smile: the defect this limit fixes. */
    @Test
    void withoutTheYawLimitTheTurnIsMistakenForASmile() {
        EngineParameters noLimit = EngineParameters.builder().set(EngineParameter.SMILE_MAX_YAW_DEG, 90).build();
        ChallengeObserver o = observe(ChallengeType.TURN_LEFT, DETECTABLE, noLimit);
        turnWithWideningMouth(o, ChallengeType.TURN_LEFT);
        assertEquals(ChallengeOutcome.WRONG_ACTION, o.outcome());
    }

    @Test
    void smileWithASlightHeadTurnStillCounts() {
        ChallengeObserver o = observe(ChallengeType.SMILE);
        feed(o, 6, TestFaces.neutral());
        assertEquals(ChallengeObserver.Status.PASSED, feed(o, 10, TestFaces.acting(ChallengeType.SMILE).yaw(8)));
    }

    @Test
    void frontalSmileBeforeATurnStillFailsIt() {
        ChallengeObserver o = observe(ChallengeType.TURN_RIGHT);
        feed(o, 6, TestFaces.neutral());
        feed(o, 3, TestFaces.acting(ChallengeType.SMILE).yaw(-10));
        assertEquals(ChallengeOutcome.WRONG_ACTION, o.outcome(), "the smile is measured up to 15 degrees of yaw");
    }

    private ChallengeObserver.Status turnWithWideningMouth(ChallengeObserver o, ChallengeType turn) {
        feed(o, 6, TestFaces.neutral());
        double sign = turn == ChallengeType.TURN_LEFT ? 1 : -1;
        ChallengeObserver.Status s = o.status();
        for (int step = 1; step <= 20 && s == ChallengeObserver.Status.PENDING; step++) {
            double yaw = Math.min(45, step * 5);
            s = feed(o, 1, TestFaces.neutral().yaw(sign * yaw).mouth(1 + 0.006 * yaw));
        }
        return s;
    }

    @Test
    void oneNoisyContraryFrameIsTolerated() {
        ChallengeObserver o = observe(ChallengeType.TURN_LEFT);
        feed(o, 6, TestFaces.neutral());
        feed(o, 1, TestFaces.acting(ChallengeType.LOOK_UP));
        assertEquals(ChallengeObserver.Status.PASSED, feed(o, 10, TestFaces.acting(ChallengeType.TURN_LEFT)));
    }

    @Test
    void spontaneousBlinkDuringAHeadTurnIsIgnored() {
        ChallengeObserver o = observe(ChallengeType.TURN_LEFT);
        feed(o, 6, TestFaces.neutral());
        feed(o, 3, TestFaces.neutral().ear(0.05));
        assertEquals(ChallengeObserver.Status.PASSED, feed(o, 10, TestFaces.acting(ChallengeType.TURN_LEFT)));
    }

    @Test
    void onsetFasterThanMinimumFails() {
        ChallengeObserver o = observe(ChallengeType.TURN_RIGHT);
        assertEquals(ChallengeObserver.Status.FAILED, feed(o, 5, TestFaces.acting(ChallengeType.TURN_RIGHT)));
        assertEquals(ChallengeOutcome.ONSET_TOO_FAST, o.outcome());
        assertEquals(ReasonCode.ONSET_TOO_FAST, o.reason());
        assertEquals(50, o.onsetMs());
    }

    @Test
    void noiseSpikeBeforeTheRealOnsetDoesNotCount() {
        ChallengeObserver o = observe(ChallengeType.TURN_LEFT);
        feed(o, 1, TestFaces.neutral().yaw(12));
        feed(o, 5, TestFaces.neutral());
        assertEquals(ChallengeObserver.Status.PASSED, feed(o, 10, TestFaces.acting(ChallengeType.TURN_LEFT)));
    }

    @Test
    void actionNotHeldFailsAtTimeout() {
        ChallengeObserver o = observe(ChallengeType.LOOK_UP);
        feed(o, 6, TestFaces.neutral());
        feed(o, 2, TestFaces.acting(ChallengeType.LOOK_UP).pitch(16));
        feed(o, 1, TestFaces.neutral());
        feed(o, 2, TestFaces.acting(ChallengeType.LOOK_UP).pitch(16));
        feed(o, 150, TestFaces.neutral());
        assertEquals(ChallengeOutcome.NOT_HELD, o.outcome());
        assertEquals(ReasonCode.NOT_HELD, o.reason());
        assertEquals(FailureReason.ACTIVE, o.failureReason());
    }

    @Test
    void holdShorterThanRequiredDoesNotPass() {
        ChallengeObserver o = observe(ChallengeType.LOOK_UP);
        feed(o, 6, TestFaces.neutral());
        assertEquals(ChallengeObserver.Status.PENDING, feed(o, 5, TestFaces.acting(ChallengeType.LOOK_UP)));
        assertEquals(ChallengeObserver.Status.PASSED, feed(o, 2, TestFaces.acting(ChallengeType.LOOK_UP)));
        assertEquals(300, o.holdMs());
    }

    @Test
    void noActionTimesOut() {
        ChallengeObserver o = observe(ChallengeType.SMILE);
        feed(o, 102, TestFaces.neutral());
        assertEquals(ChallengeOutcome.TIMEOUT, o.outcome());
        assertEquals(ReasonCode.CHALLENGE_TIMEOUT, o.reason());
        assertEquals(FailureReason.TIMEOUT, o.failureReason());
        assertTrue(o.durationMs(now) > 5_000);
    }

    /**
     * T-CORE-ACT-07: the sign convention is the subject's own. A positive yaw is a turn to the subject's left
     * whether or not the preview is mirrored, so TURN_LEFT passes with yaw +25 and fails with yaw -25.
     */
    @Test
    void subjectRelativeSignConvention() {
        ChallengeObserver left = observe(ChallengeType.TURN_LEFT);
        feed(left, 6, TestFaces.neutral());
        assertEquals(ChallengeObserver.Status.PASSED, feed(left, 10, TestFaces.neutral().yaw(25)));
        ChallengeObserver mirroredMistake = observe(ChallengeType.TURN_LEFT);
        feed(mirroredMistake, 6, TestFaces.neutral());
        assertEquals(ChallengeOutcome.WRONG_ACTION, statusAfter(mirroredMistake, TestFaces.neutral().yaw(-25)));
    }

    private ChallengeOutcome statusAfter(ChallengeObserver o, TestFaces face) {
        feed(o, 10, face);
        return o.outcome();
    }

    @Test
    void framesWithoutAFaceChangeNothingAndAbortRecordsOutcome() {
        ChallengeObserver o = observe(ChallengeType.TURN_LEFT);
        now += 100 * MS;
        assertEquals(ChallengeObserver.Status.PENDING, o.onFrame(now, null));
        DetectedFace noPose = TestFaces.neutral().noPose().build();
        assertEquals(ChallengeObserver.Status.PENDING, o.onFrame(now + MS, FaceSignals.of(noPose)));
        o.abort(now, ChallengeOutcome.PAD_FAILED);
        assertEquals(ChallengeOutcome.PAD_FAILED, o.outcome());
        assertNull(o.reason());
        o.abort(now, ChallengeOutcome.CANCELLED);
        assertEquals(ChallengeOutcome.PAD_FAILED, o.outcome(), "abort after the end changes nothing");
        assertEquals(ChallengeObserver.Status.FAILED, o.onFrame(now + 2 * MS, FaceSignals.of(noPose)));
    }

    @Test
    void levelsAreNaNWithoutMeasures() {
        ActionLevels levels = new ActionLevels(EngineParameters.defaults());
        FaceSignals bare = FaceSignals.of(DetectedFace.builder(FaceBox.of(0, 0, 10, 10, 1)).build());
        NeutralBaseline empty = new NeutralBaseline();
        for (ChallengeType t : EnumSet.allOf(ChallengeType.class)) {
            assertTrue(Double.isNaN(levels.level(t, bare, empty)), t.name());
        }
        assertTrue(Double.isNaN(bare.maxPoseAngle()));
        assertTrue(Double.isNaN(FaceSignals.interEyeDistance(DetectedFace.builder(FaceBox.of(0, 0, 10, 10, 1)).build())));
        assertEquals(80, FaceSignals.interEyeDistance(TestFaces.neutral().build()), 1e-9);
        DetectedFace landmarksOnly = DetectedFace.builder(TestFaces.neutral().build().box()).build();
        assertEquals(80, FaceSignals.interEyeDistance(landmarksOnly), 1e-6);
    }
}
