/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.image.PixelFormat;

/** Checks the per-frame timing: only the session's processing is counted, percentiles, and pass-through. */
public class FrameTimerTest {

    private long now;
    private final List<Object> received = new ArrayList<>();

    /** A session stand-in whose processing of frame i takes i milliseconds on the fake clock. */
    private final FrameSink session = new FrameSink() {
        @Override
        public void onFrame(FaceFrame frame) {
            now += frame.sequence() * 1_000_000L;
            received.add(frame);
        }

        @Override
        public void onError(DeviceError error) {
            received.add(error);
        }

        @Override
        public void onEnd(EndReason reason) {
            received.add(reason);
        }
    };

    @Test
    public void percentilesOfProcessingTime() {
        FrameTimer timer = new FrameTimer(session, () -> now);
        for (int i = 1; i <= 20; i++) {
            timer.onFrame(frame(i));
            now += 50_000_000L; // waiting for the next frame is not counted
        }
        assertEquals(20, timer.frames());
        assertEquals(10.0, timer.percentileMs(50), 1e-9);
        assertEquals(19.0, timer.percentileMs(95), 1e-9);
        assertEquals(20.0, timer.percentileMs(100), 1e-9);
        assertEquals("frames=20 p50=10.0ms p95=19.0ms max=20.0ms", timer.summary());
    }

    @Test
    public void emptyTimerReportsZero() {
        FrameTimer timer = new FrameTimer(session, () -> now);
        assertEquals(0, timer.frames());
        assertEquals(0.0, timer.percentileMs(95), 1e-9);
    }

    @Test
    public void errorsAndEndReachTheSession() {
        FrameTimer timer = new FrameTimer(session, () -> now);
        DeviceError error = DeviceError.of(DeviceErrorCode.STREAM_OPEN_FAILED, "test");
        timer.onError(error);
        timer.onEnd(EndReason.END_OF_STREAM);
        assertSame(error, received.get(0));
        assertSame(EndReason.END_OF_STREAM, received.get(1));
        assertEquals(0, timer.frames());
    }

    private static FaceFrame frame(long sequence) {
        return FaceFrame.builder().data(new byte[] {1}).format(PixelFormat.JPEG).size(0, 0).monotonicNanos(sequence)
                .sequence(sequence).sourceId("test").build();
    }
}
