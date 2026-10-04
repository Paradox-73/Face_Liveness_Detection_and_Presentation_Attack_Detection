/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.ui.PromptIcon;

/**
 * Checks that a replayed pack answers challenge prompts like a person: the movement pack starts only after the
 * reaction time, any other prompt goes back to the neutral pack at once, and a prompt without a pack is ignored.
 * Every challenge type has a pack name, and pack names are matched without case. Frames are one-byte markers:
 * 'N' neutral, 'B' blink, 'S' smile, 'L' turn left, 'R' turn right, 'U' look up.
 */
public class ReplayPromptFollowerTest {

    private static final List<byte[]> NEUTRAL = Collections.singletonList(new byte[] {'N'});
    private static final List<byte[]> BLINK = Collections.singletonList(new byte[] {'B'});
    private static final List<byte[]> SMILE = Collections.singletonList(new byte[] {'S'});
    private static final List<byte[]> TURN_LEFT = Collections.singletonList(new byte[] {'L'});
    private static final List<byte[]> TURN_RIGHT = Collections.singletonList(new byte[] {'R'});
    private static final List<byte[]> LOOK_UP = Collections.singletonList(new byte[] {'U'});

    private final BlockingQueue<Byte> played = new LinkedBlockingQueue<>();
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private ReplayFrameSource source;
    private ReplayPromptFollower follower;

    @Before
    public void setUp() {
        source = new ReplayFrameSource(NEUTRAL, 60, true, Clock.system(), "test", null);
        Map<PromptIcon, List<byte[]>> movements = new EnumMap<>(PromptIcon.class);
        movements.put(PromptIcon.BLINK, BLINK);
        movements.put(PromptIcon.SMILE, SMILE);
        movements.put(PromptIcon.TURN_LEFT, TURN_LEFT);
        movements.put(PromptIcon.TURN_RIGHT, TURN_RIGHT);
        movements.put(PromptIcon.LOOK_UP, LOOK_UP);
        follower = new ReplayPromptFollower(source, NEUTRAL, movements, (task, ms) -> {
            delayed.add(task);
            delays.add(ms);
        }, 500L);
        source.start(new FrameSink() {
            @Override
            public void onFrame(FaceFrame frame) {
                played.add(frame.data()[0]);
            }

            @Override
            public void onError(DeviceError error) {
            }

            @Override
            public void onEnd(EndReason reason) {
            }
        });
    }

    @After
    public void tearDown() {
        source.close();
    }

    @Test
    public void movementStartsAfterTheReactionTime() throws InterruptedException {
        awaitFrame('N');
        follower.onPrompt(PromptIcon.BLINK);
        assertEquals(1, delayed.size());
        assertEquals(Long.valueOf(500L), delays.get(0));
        awaitFrame('N');
        delayed.get(0).run();
        awaitFrame('B');
    }

    @Test
    public void neutralPromptSwitchesBackAtOnce() throws InterruptedException {
        follower.onPrompt(PromptIcon.SMILE);
        delayed.get(0).run();
        awaitFrame('S');
        follower.onPrompt(PromptIcon.HOLD_STILL);
        assertEquals(1, delayed.size());
        awaitFrame('N');
    }

    @Test
    public void neutralPackContinuesWhereItWasLeft() throws InterruptedException {
        ReplayFrameSource counting = new ReplayFrameSource(
                java.util.Arrays.asList(new byte[] {'0'}, new byte[] {'1'}, new byte[] {'2'}, new byte[] {'3'},
                        new byte[] {'4'}, new byte[] {'5'}), 60, true, Clock.system(), "test", null);
        BlockingQueue<Byte> seen = new LinkedBlockingQueue<>();
        counting.start(new FrameSink() {
            @Override
            public void onFrame(FaceFrame frame) {
                seen.add(frame.data()[0]);
            }

            @Override
            public void onError(DeviceError error) {
            }

            @Override
            public void onEnd(EndReason reason) {
            }
        });
        try {
            byte first;
            do {
                first = seen.poll(2, TimeUnit.SECONDS);
            } while (first != '2');
            counting.switchTo(BLINK);
            // The stream may run ahead of this thread, so the last neutral frame played is the one just before
            // the first blink frame in the delivery order, not necessarily frame 2.
            byte last = first;
            byte b;
            while ((b = seen.poll(2, TimeUnit.SECONDS)) != 'B') {
                last = b;
            }
            counting.switchBack();
            do {
                b = seen.poll(2, TimeUnit.SECONDS);
            } while (b == 'B');
            char expected = (char) ('0' + (last - '0' + 1) % 6);
            assertEquals("resumed after frame " + (char) last + ", not from the start", expected, (char) b);
        } finally {
            counting.close();
        }
    }

    @Test
    public void staleSwitchIsDropped() throws InterruptedException {
        follower.onPrompt(PromptIcon.BLINK);
        follower.onPrompt(PromptIcon.PROGRESS);
        delayed.get(0).run();
        played.clear();
        for (int i = 0; i < 5; i++) {
            assertEquals(Byte.valueOf((byte) 'N'), played.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void repeatedPromptAndPromptWithoutPackChangeNothing() {
        follower.onPrompt(PromptIcon.BLINK);
        follower.onPrompt(PromptIcon.BLINK);
        assertEquals(1, delayed.size());
        follower.onPrompt(PromptIcon.PROGRESS);
        follower.onPrompt(PromptIcon.LOOK_DOWN);
        assertEquals(1, delayed.size());
        assertTrue(follower.answers(PromptIcon.BLINK));
        assertFalse(follower.answers(PromptIcon.LOOK_DOWN));
    }

    @Test
    public void headTurnsAndLookUpSwitchToTheirPacks() throws InterruptedException {
        follower.onPrompt(PromptIcon.TURN_LEFT);
        delayed.get(0).run();
        awaitFrame('L');
        follower.onPrompt(PromptIcon.TURN_RIGHT);
        delayed.get(1).run();
        awaitFrame('R');
        follower.onPrompt(PromptIcon.LOOK_UP);
        assertEquals(Long.valueOf(500L), delays.get(2));
        delayed.get(2).run();
        awaitFrame('U');
        assertTrue(follower.answers(PromptIcon.TURN_LEFT));
        assertTrue(follower.answers(PromptIcon.TURN_RIGHT));
        assertTrue(follower.answers(PromptIcon.LOOK_UP));
    }

    @Test
    public void lookStraightAndHoldStillGoesBackToTheMainPackAtOnce() throws InterruptedException {
        follower.onPrompt(PromptIcon.TURN_RIGHT);
        delayed.get(0).run();
        awaitFrame('R');
        // After the last challenge the engine shows "Look directly at the camera and hold still" with the face
        // outline icon until a neutral, frontal frame arrives.
        follower.onPrompt(PromptIcon.FACE_OVAL);
        assertEquals("no delay for the neutral pack", 1, delayed.size());
        awaitFrame('N');
        played.clear();
        for (int i = 0; i < 5; i++) {
            assertEquals(Byte.valueOf((byte) 'N'), played.poll(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void everyChallengeTypeHasAPackAction() {
        for (ChallengeType type : ChallengeType.values()) {
            PromptIcon prompt = PromptIcon.forChallenge(type);
            String action = ReplayPromptFollower.PACK_ACTIONS.get(prompt);
            assertTrue("no pack for " + type, action != null && !action.isEmpty());
            assertEquals(prompt, ReplayPromptFollower.promptOf(action));
        }
        assertEquals(ChallengeType.values().length, ReplayPromptFollower.PACK_ACTIONS.size());
    }

    @Test
    public void promptNamesMatchWithoutCase() {
        assertEquals(PromptIcon.TURN_LEFT, ReplayPromptFollower.promptOf("turn-left"));
        assertEquals(PromptIcon.TURN_LEFT, ReplayPromptFollower.promptOf("TURN_LEFT"));
        assertEquals(PromptIcon.TURN_LEFT, ReplayPromptFollower.promptOf("Turn left"));
        assertEquals(PromptIcon.LOOK_UP, ReplayPromptFollower.promptOf("Look-Up"));
        assertEquals(PromptIcon.BLINK, ReplayPromptFollower.promptOf(" BLINK "));
        assertNull(ReplayPromptFollower.promptOf("main"));
        assertNull(ReplayPromptFollower.promptOf("hold still"));
        assertNull(ReplayPromptFollower.promptOf(null));
    }

    @Test
    public void findsTheMainPackAndEveryActionPackWithoutCase() {
        ReplayPromptFollower.Packs packs = ReplayPromptFollower.findPacks("s3n", java.util.Arrays.asList(
                "S3N-Main", "s3n-blink", "s3n-SMILE", "s3n-turn_left", "S3n-Turn Right", "s3n-look-up",
                "s3n-LOOK-DOWN", "s3n-gaze-left", "s3n-Gaze_Right", "s3-blink", "s3n-unknown", "s3nx-smile"));
        assertEquals("S3N-Main", packs.main());
        Map<PromptIcon, String> m = packs.movements();
        assertEquals(8, m.size());
        assertEquals("s3n-blink", m.get(PromptIcon.BLINK));
        assertEquals("s3n-SMILE", m.get(PromptIcon.SMILE));
        assertEquals("s3n-turn_left", m.get(PromptIcon.TURN_LEFT));
        assertEquals("S3n-Turn Right", m.get(PromptIcon.TURN_RIGHT));
        assertEquals("s3n-look-up", m.get(PromptIcon.LOOK_UP));
        assertEquals("s3n-LOOK-DOWN", m.get(PromptIcon.LOOK_DOWN));
        assertEquals("s3n-gaze-left", m.get(PromptIcon.GAZE_LEFT));
        assertEquals("s3n-Gaze_Right", m.get(PromptIcon.GAZE_RIGHT));
    }

    @Test
    public void findPacksKeepsTheFirstMatchAndReportsAMissingMainPack() {
        ReplayPromptFollower.Packs packs = ReplayPromptFollower.findPacks("S2", java.util.Arrays.asList(
                "s2-turn-left", "S2-TURN-LEFT", "s2-turn-right"));
        assertNull(packs.main());
        assertEquals("s2-turn-left", packs.movements().get(PromptIcon.TURN_LEFT));
        assertEquals(java.util.Arrays.asList(PromptIcon.TURN_LEFT, PromptIcon.TURN_RIGHT), packs.prompts());
        assertTrue(ReplayPromptFollower.findPacks("s1", null).movements().isEmpty());
    }

    @Test
    public void promptsThatAreNotActionsNeverGetAMovementPack() {
        Map<PromptIcon, List<byte[]>> movements = new EnumMap<>(PromptIcon.class);
        movements.put(PromptIcon.HOLD_STILL, BLINK);
        movements.put(PromptIcon.FACE_OVAL, SMILE);
        ReplayPromptFollower f = new ReplayPromptFollower(source, NEUTRAL, movements, (task, ms) -> delayed.add(task),
                0L);
        assertFalse(f.answers(PromptIcon.HOLD_STILL));
        assertFalse(f.answers(PromptIcon.FACE_OVAL));
        f.onPrompt(PromptIcon.FACE_OVAL);
        assertTrue(delayed.isEmpty());
    }

    private void awaitFrame(char marker) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            Byte b = played.poll(100, TimeUnit.MILLISECONDS);
            if (b != null && b == (byte) marker) {
                return;
            }
        }
        throw new AssertionError("no frame " + marker + " within 3 s");
    }
}
