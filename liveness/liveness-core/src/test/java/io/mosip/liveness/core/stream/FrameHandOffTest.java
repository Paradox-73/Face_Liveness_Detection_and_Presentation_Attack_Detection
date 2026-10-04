/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.stream;

import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.PixelFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frame hand-off between the reader thread and the session thread, and the frame-rate meter. */
class FrameHandOffTest {

    private static FaceFrame frame(long seq) {
        return FaceFrame.builder().data(new byte[] {(byte) 0xFF, (byte) 0xD8}).format(PixelFormat.JPEG)
                .monotonicNanos(seq * 50_000_000L).sequence(seq).sourceId("t").build();
    }

    @Test
    void latestOnlyKeepsTheNewestFrame() throws InterruptedException {
        FrameHandOff h = FrameHandOff.latestOnly();
        FaceFrame last = frame(3);
        h.onFrame(frame(1));
        h.onFrame(frame(2));
        h.onFrame(last);
        assertEquals(1, h.pending());
        assertEquals(2, h.droppedFrames());
        assertEquals(3, h.receivedFrames());
        MjpegFrameReaderTest.Sink sink = new MjpegFrameReaderTest.Sink();
        assertTrue(h.deliverNext(sink, 0));
        assertSame(last, sink.frames.get(0));
        assertFalse(h.deliverNext(sink, 10), "nothing waiting: returns after the timeout");
    }

    @Test
    void boundedDropsTheOldestAndDeliversTheEndLast() throws InterruptedException {
        FrameHandOff h = FrameHandOff.bounded(2);
        h.onFrame(frame(1));
        h.onFrame(frame(2));
        h.onFrame(frame(3));
        h.onEnd(EndReason.END_OF_STREAM);
        h.onFrame(frame(4));
        h.onError(DeviceError.of(DeviceErrorCode.STALLED, "late"));
        MjpegFrameReaderTest.Sink sink = new MjpegFrameReaderTest.Sink();
        while (h.deliverNext(sink, 0)) {
            // drain
        }
        assertEquals(2, sink.frames.size());
        assertEquals(2, sink.frames.get(0).sequence());
        assertEquals(3, sink.frames.get(1).sequence());
        assertEquals(EndReason.END_OF_STREAM, sink.end);
        assertNull(sink.error);
        assertEquals(1, sink.terminals.get());
        assertTrue(h.isEnded());
        assertFalse(h.deliverNext(sink, 0));
    }

    @Test
    void errorIsDeliveredAndClearDropsWaitingFrames() throws InterruptedException {
        FrameHandOff h = FrameHandOff.bounded(3);
        h.onFrame(frame(1));
        h.clear();
        assertEquals(0, h.pending());
        h.onError(DeviceError.of(DeviceErrorCode.DISCONNECTED, "gone"));
        MjpegFrameReaderTest.Sink sink = new MjpegFrameReaderTest.Sink();
        assertTrue(h.deliverNext(sink, 100));
        assertEquals(DeviceErrorCode.DISCONNECTED, sink.error.code());
        assertThrows(IllegalArgumentException.class, () -> FrameHandOff.bounded(0));
    }

    @Test
    void readerAndWorkerThreads() throws Exception {
        FrameHandOff h = FrameHandOff.latestOnly();
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 2_000; i++) {
                h.onFrame(frame(i));
            }
            h.onEnd(EndReason.END_OF_STREAM);
        });
        MjpegFrameReaderTest.Sink sink = new MjpegFrameReaderTest.Sink();
        reader.start();
        while (!h.isEnded()) {
            h.deliverNext(sink, 50);
        }
        reader.join();
        assertEquals(2_000, h.receivedFrames());
        assertEquals(2_000, sink.frames.size() + h.droppedFrames());
        for (int i = 1; i < sink.frames.size(); i++) {
            assertTrue(sink.frames.get(i).sequence() > sink.frames.get(i - 1).sequence(), "order is kept");
        }
        assertEquals(EndReason.END_OF_STREAM, sink.end);
    }

    @Test
    void rateMeter() {
        FrameRateMeter m = new FrameRateMeter(4);
        assertEquals(0, m.fps(), 0);
        assertEquals(0, m.sourceFps(), 0);
        assertEquals(0, m.jitterMs(), 0);
        assertEquals(0, m.meanIntervalMs(), 0);
        long[] times = {0, 100, 200, 300, 400, 500};
        for (int i = 0; i < times.length; i++) {
            m.record(times[i] * 1_000_000L, i * 3L);
        }
        assertEquals(4, m.count());
        assertEquals(300_000_000L, m.spanNanos());
        assertEquals(10, m.fps(), 1e-9);
        assertEquals(30, m.sourceFps(), 1e-9);
        assertEquals(0, m.jitterMs(), 1e-9);
        assertEquals(100, m.meanIntervalMs(), 1e-9);
        m.record(700_000_000L, 20);
        assertTrue(m.jitterMs() > 0);
        m.clear();
        assertEquals(0, m.count());
        assertEquals(0, new FrameRateMeter().count());
        assertThrows(IllegalArgumentException.class, () -> new FrameRateMeter(1));
    }
}
