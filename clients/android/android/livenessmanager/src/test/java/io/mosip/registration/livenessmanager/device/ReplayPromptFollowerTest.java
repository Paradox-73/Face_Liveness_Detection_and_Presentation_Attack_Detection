package io.mosip.registration.livenessmanager.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.ui.PromptIcon;

/**
 * Checks that a replayed pack answers challenge prompts like a person: the movement pack starts only after the
 * reaction time, any other prompt goes back to the neutral pack at once, and a prompt without a pack is ignored.
 * Frames are one-byte markers: 'N' neutral, 'B' blink, 'S' smile.
 */
public class ReplayPromptFollowerTest {

    private static final List<byte[]> NEUTRAL = Collections.singletonList(new byte[] {'N'});
    private static final List<byte[]> BLINK = Collections.singletonList(new byte[] {'B'});
    private static final List<byte[]> SMILE = Collections.singletonList(new byte[] {'S'});

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
            byte b;
            do {
                b = seen.poll(2, TimeUnit.SECONDS);
            } while (b != 'B');
            counting.switchBack();
            do {
                b = seen.poll(2, TimeUnit.SECONDS);
            } while (b == 'B');
            assertTrue("resumed after frame 2, not from 0: " + (char) b, b >= '3' && b <= '5');
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
        follower.onPrompt(PromptIcon.TURN_LEFT);
        assertEquals(1, delayed.size());
        assertTrue(follower.answers(PromptIcon.BLINK));
        assertFalse(follower.answers(PromptIcon.TURN_LEFT));
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
