/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.stream;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.image.PixelFormat;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-CORE-MJ-01..10: the MJPEG reader on synthetic, truncated and malformed streams. */
class MjpegFrameReaderTest {

    private static final String BOUNDARY = "--BoundaryString";
    private final Random random = new Random(5);

    /** Records callbacks and checks that exactly one terminal call arrives. */
    static final class Sink implements FrameSink {
        final List<FaceFrame> frames = new CopyOnWriteArrayList<>();
        final AtomicInteger terminals = new AtomicInteger();
        volatile DeviceError error;
        volatile EndReason end;

        @Override
        public void onFrame(FaceFrame frame) {
            frames.add(frame);
        }

        @Override
        public void onError(DeviceError e) {
            terminals.incrementAndGet();
            error = e;
        }

        @Override
        public void onEnd(EndReason reason) {
            terminals.incrementAndGet();
            end = reason;
        }
    }

    /** Manual clock that the test or a stream can move. */
    static final class ManualClock implements Clock {
        volatile long nanos;

        @Override
        public long monotonicNanos() {
            return nanos;
        }

        @Override
        public Instant now() {
            return Instant.ofEpochSecond(0, nanos);
        }
    }

    private byte[] jpeg(int size) {
        byte[] b = new byte[size];
        for (int i = 2; i < size - 2; i++) {
            b[i] = (byte) random.nextInt(0xFF);
        }
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[size - 2] = (byte) 0xFF;
        b[size - 1] = (byte) 0xD9;
        return b;
    }

    /** One part exactly as MockMDS writes it ({@code SBIServiceResponse.createHeader}). */
    private static byte[] part(byte[] body, int declaredLength) {
        String head = BOUNDARY + "\r\nAccess-Control-Allow-Origin:*\r\nContent-Type:image/jpeg\r\nContent-Length:"
                + declaredLength + "\r\n\r\n";
        return concat(head.getBytes(StandardCharsets.US_ASCII), body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] part(byte[] body) {
        return part(body, body.length);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Sink read(byte[] stream) {
        Sink sink = new Sink();
        MjpegFrameReader.builder(new ByteArrayInputStream(stream), sink).sourceId("dev-1").build().run();
        assertEquals(1, sink.terminals.get(), "exactly one terminal call");
        return sink;
    }

    @Test
    void mockMdsStreamDeliversEveryFrameThenEnds() {
        byte[][] jpegs = {jpeg(500), jpeg(700), jpeg(64)};
        Sink sink = read(concat(part(jpegs[0]), part(jpegs[1]), part(jpegs[2])));
        assertEquals(EndReason.END_OF_STREAM, sink.end);
        assertEquals(3, sink.frames.size());
        for (int i = 0; i < 3; i++) {
            FaceFrame f = sink.frames.get(i);
            assertArrayEquals(jpegs[i], f.data());
            assertEquals(i, f.sequence());
            assertEquals(PixelFormat.JPEG, f.format());
            assertEquals("dev-1", f.sourceId());
        }
    }

    /** T-CORE-MJ-01: at end of stream the reader returns instead of spinning like the stock Streamer loop. */
    @Test
    void endOfStreamEndsTheReaderWithoutSpinning() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                reads.incrementAndGet();
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                reads.incrementAndGet();
                return -1;
            }
        };
        Sink sink = new Sink();
        Thread t = new Thread(MjpegFrameReader.builder(new SequenceInputStream(new ByteArrayInputStream(part(jpeg(100))),
                endless), sink).build());
        t.start();
        t.join(1_000);
        assertFalse(t.isAlive(), "reader thread ended within 1 s");
        assertEquals(EndReason.END_OF_STREAM, sink.end);
        assertEquals(1, sink.frames.size());
        assertTrue(reads.get() <= 3, "no busy loop at end of stream: " + reads.get() + " reads");
    }

    @Test
    void truncatedJpegAtEndOfStreamIsInvalid() {
        byte[] j = jpeg(1000);
        byte[] stream = concat(part(jpeg(80)), Arrays.copyOf(part(j), 300));
        Sink sink = new Sink();
        MjpegFrameReader reader = MjpegFrameReader.builder(new ByteArrayInputStream(stream), sink).build();
        reader.run();
        assertEquals(1, sink.frames.size());
        assertEquals(EndReason.END_OF_STREAM, sink.end);
        assertEquals(1, reader.stats().invalidParts());
        assertEquals(1, reader.stats().framesDelivered());
    }

    @Test
    void overDeclaredLengthLosesOnlyThatPart() {
        byte[] a = jpeg(300);
        byte[] b = jpeg(400);
        byte[] c = jpeg(500);
        Sink sink = read(concat(part(a, a.length + 40), part(b), part(c)));
        assertEquals(2, sink.frames.size());
        assertArrayEquals(b, sink.frames.get(0).data());
        assertArrayEquals(c, sink.frames.get(1).data());
    }

    @Test
    void underDeclaredLengthIsSkipped() {
        byte[] a = jpeg(300);
        byte[] b = jpeg(400);
        Sink sink = read(concat(part(a, a.length - 40), part(b)));
        assertEquals(1, sink.frames.size());
        assertArrayEquals(b, sink.frames.get(0).data());
    }

    @Test
    void nonJpegPartsAreDropped() {
        byte[] text = ascii("hello, this is not an image");
        byte[] notJpeg = jpeg(200);
        notJpeg[0] = 0x12;
        String head = BOUNDARY + "\r\nContent-Type:text/plain\r\nContent-Length:" + text.length + "\r\n\r\n";
        byte[] good = jpeg(150);
        Sink sink = read(concat(ascii(head), text, ascii("\r\n"), part(notJpeg), part(good)));
        assertEquals(1, sink.frames.size());
        assertArrayEquals(good, sink.frames.get(0).data());
    }

    @Test
    void extraHeadersAnyCaseAndSpacing() {
        byte[] j = jpeg(256);
        String head = BOUNDARY + "\r\nX-Mock-Label: replay\r\ncontent-type: IMAGE/JPEG\r\nCONTENT-LENGTH:   " + j.length
                + "  \r\nX-Timestamp: 12\r\n\r\n";
        Sink sink = read(concat(ascii(head), j, ascii("\r\n")));
        assertEquals(1, sink.frames.size());
    }

    @Test
    void httpEnvelopeAndPartsWithoutLength() {
        byte[] a = jpeg(120);
        byte[] b = jpeg(90);
        byte[] stream = concat(ascii("HTTP/1.1 200 OK\r\nConnection: close\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=\"Frame\"\r\n\r\n"),
                ascii("--Frame\r\nContent-Type: image/jpeg\r\n\r\n"), a,
                ascii("\r\n--Frame\r\nContent-Type: image/jpeg\r\n\r\n"), b,
                ascii("\r\n--Frame--\r\n{\"error\":{\"errorCode\":\"0\"}}"));
        Sink sink = read(stream);
        assertEquals(2, sink.frames.size());
        assertArrayEquals(a, sink.frames.get(0).data());
        assertArrayEquals(b, sink.frames.get(1).data());
        assertEquals(EndReason.END_OF_STREAM, sink.end);
    }

    @Test
    void partsWithoutBoundaryOrLengthAreSplitOnTheJpegEndMarker() {
        byte[] a = jpeg(120);
        Sink sink = read(concat(ascii("Content-Type: image/jpeg\r\n\r\n"), a));
        assertEquals(1, sink.frames.size());
        assertArrayEquals(a, sink.frames.get(0).data());
    }

    @Test
    void boundaryFromTheBuilder() {
        byte[] a = jpeg(120);
        Sink sink = new Sink();
        MjpegFrameReader.builder(new ByteArrayInputStream(concat(ascii("--xyz\r\nContent-Type: image/jpeg\r\n\r\n"), a,
                ascii("\r\n--xyz\r\n"))), sink).boundary("xyz").build().run();
        assertEquals(1, sink.frames.size());
        assertEquals(EndReason.END_OF_STREAM, sink.end);
    }

    @Test
    void mockMdsResultJsonAfterTheStreamIsIgnored() {
        Sink sink = read(concat(part(jpeg(100)), ascii("{\"error\":{\"errorCode\":\"0\",\"errorInfo\":\"Success\"}}")));
        assertEquals(1, sink.frames.size());
        assertEquals(EndReason.END_OF_STREAM, sink.end);
    }

    /** T-CORE-MJ-06: a read timeout (for example the 2 s socket timeout) is a stall. */
    @Test
    void readTimeoutIsAStall() {
        Sink sink = new Sink();
        InputStream stalls = new SequenceInputStream(new ByteArrayInputStream(part(jpeg(100))), new InputStream() {
            @Override
            public int read() throws IOException {
                throw new InterruptedIOException("Read timed out");
            }
        });
        MjpegFrameReader.builder(stalls, sink).build().run();
        assertEquals(DeviceErrorCode.STALLED, sink.error.code());
        assertEquals(1, sink.frames.size());
        assertEquals(1, sink.terminals.get());
    }

    /** T-CORE-MJ-10: a reset connection. */
    @Test
    void resetIsADisconnect() {
        Sink sink = new Sink();
        MjpegFrameReader.builder(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Connection reset");
            }
        }, sink).build().run();
        assertEquals(DeviceErrorCode.DISCONNECTED, sink.error.code());
    }

    /** T-CORE-MJ-07: garbage but no valid frame within 3 s. */
    @Test
    void noFirstFrameWithinTheDeadline() {
        ManualClock clock = new ManualClock();
        InputStream garbage = new InputStream() {
            @Override
            public int read() {
                clock.nanos += 10_000_000L;
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                int n = Math.min(len, 64);
                for (int i = 0; i < n; i++) {
                    b[off + i] = (byte) (i % 63 == 62 ? '\n' : 'x');
                }
                clock.nanos += 100_000_000L;
                return n;
            }
        };
        Sink sink = new Sink();
        MjpegFrameReader.builder(garbage, sink).clock(clock).timeouts(StreamRequest.defaults()).build().run();
        assertEquals(DeviceErrorCode.FIRST_FRAME_TIMEOUT, sink.error.code());
        assertTrue(clock.nanos >= 3_000_000_000L);
    }

    /** Returns one chunk per read call and moves the clock before each chunk, like a paced live stream. */
    static final class ChunkedStream extends InputStream {
        private final List<byte[]> chunks;
        private final ManualClock clock;
        private final long stepNanos;
        private final boolean repeat;
        private int index;
        private byte[] current;
        private int pos;

        ChunkedStream(List<byte[]> chunks, ManualClock clock, long stepMs, boolean repeat) {
            this.chunks = chunks;
            this.clock = clock;
            this.stepNanos = stepMs * 1_000_000L;
            this.repeat = repeat;
        }

        private boolean next() {
            if (index == chunks.size()) {
                if (!repeat) {
                    return false;
                }
                index = 0;
            }
            current = chunks.get(index++);
            pos = 0;
            clock.nanos += stepNanos;
            return true;
        }

        @Override
        public int read() {
            if ((current == null || pos == current.length) && !next()) {
                return -1;
            }
            return current[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if ((current == null || pos == current.length) && !next()) {
                return -1;
            }
            int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    @Test
    void streamTimeoutEndsNormally() {
        ManualClock clock = new ManualClock();
        InputStream endless = new ChunkedStream(Collections.singletonList(part(jpeg(100))), clock, 100, true);
        Sink sink = new Sink();
        MjpegFrameReader.builder(endless, sink).clock(clock).streamTimeoutMs(1_000).build().run();
        assertEquals(EndReason.STREAM_TIMEOUT, sink.end);
        assertTrue(sink.frames.size() >= 9 && sink.frames.size() <= 12, "frames " + sink.frames.size());
    }

    @Test
    void stopFromAnotherThreadClosesTheStream() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 64 * 1024);
        Sink sink = new Sink();
        MjpegFrameReader reader = MjpegFrameReader.builder(in, sink).build();
        Thread t = new Thread(reader);
        t.start();
        out.write(part(jpeg(100)));
        out.flush();
        for (int i = 0; i < 100 && sink.frames.isEmpty(); i++) {
            Thread.sleep(10);
        }
        reader.stop();
        // A socket read wakes up when the socket is closed; a pipe read needs the writer side closed too.
        out.close();
        t.join(2_000);
        assertFalse(t.isAlive());
        assertEquals(EndReason.CLOSED, sink.end);
        assertEquals(1, sink.frames.size());
        assertEquals(1, sink.terminals.get());
        reader.stop();
    }

    @Test
    void tooManyInvalidPartsEndTheStream() {
        byte[] bad = jpeg(100);
        bad[bad.length - 1] = 0;
        bad[bad.length - 2] = 0;
        Sink sink = new Sink();
        MjpegFrameReader.builder(new ByteArrayInputStream(concat(part(bad), part(bad), part(bad), part(jpeg(50)))), sink)
                .maxConsecutiveInvalid(3).build().run();
        assertEquals(DeviceErrorCode.INVALID_STREAM, sink.error.code());
        assertTrue(sink.frames.isEmpty());
    }

    @Test
    void oversizedAndUnparsableLengthsAreInvalid() {
        byte[] big = jpeg(3000);
        String badLength = BOUNDARY + "\r\nContent-Type:image/jpeg\r\nContent-Length:abc\r\n\r\n";
        byte[] good = jpeg(100);
        Sink sink = new Sink();
        MjpegFrameReader reader = MjpegFrameReader.builder(new ByteArrayInputStream(concat(part(big), ascii(badLength),
                ascii("\r\n"), part(good))), sink).maxFrameBytes(2048).build();
        reader.run();
        assertEquals(1, sink.frames.size());
        assertEquals(2, reader.stats().invalidParts());
    }

    @Test
    void failingSinkEndsTheStream() {
        Sink errors = new Sink();
        FrameSink throwing = new FrameSink() {
            @Override
            public void onFrame(FaceFrame frame) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onError(DeviceError e) {
                errors.onError(e);
            }

            @Override
            public void onEnd(EndReason reason) {
                errors.onEnd(reason);
            }
        };
        MjpegFrameReader.builder(new ByteArrayInputStream(concat(part(jpeg(100)), part(jpeg(100)))), throwing).build().run();
        assertEquals(DeviceErrorCode.UNKNOWN, errors.error.code());
        assertEquals(1, errors.terminals.get());
    }

    @Test
    void builderAndLifecycleRules() {
        MjpegFrameReader r = MjpegFrameReader.builder(new ByteArrayInputStream(new byte[0]), new Sink()).build();
        r.run();
        assertThrows(IllegalStateException.class, r::run);
        MjpegFrameReader.Builder b = MjpegFrameReader.builder(new ByteArrayInputStream(new byte[0]), new Sink());
        assertThrows(IllegalArgumentException.class, () -> b.maxFrameBytes(10));
        assertThrows(IllegalArgumentException.class, () -> b.firstFrameTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> b.maxConsecutiveInvalid(0));
        assertThrows(IllegalArgumentException.class, () -> b.sourceId("").build());
        assertThrows(NullPointerException.class, () -> MjpegFrameReader.builder(null, new Sink()));
        b.boundary("  ");
    }

    @Test
    void completeJpegCheck() {
        byte[] j = jpeg(10);
        assertTrue(MjpegFrameReader.isCompleteJpeg(j, j.length));
        byte[] padded = concat(j, new byte[] {'\r', '\n', 0});
        assertTrue(MjpegFrameReader.isCompleteJpeg(padded, padded.length));
        assertFalse(MjpegFrameReader.isCompleteJpeg(j, 3));
        assertFalse(MjpegFrameReader.isCompleteJpeg(ascii("GIF89a....."), 11));
    }

    @Test
    void statsReportRateOfDeliveredFrames() {
        ManualClock clock = new ManualClock();
        List<byte[]> parts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            parts.add(part(jpeg(60)));
        }
        InputStream paced = new ChunkedStream(parts, clock, 50, false);
        Sink sink = new Sink();
        MjpegFrameReader reader = MjpegFrameReader.builder(paced, sink).clock(clock).build();
        reader.run();
        assertEquals(10, reader.stats().framesDelivered());
        assertEquals(20, reader.stats().fps(), 0.5);
        assertEquals(0, reader.stats().framesDropped());
        assertNull(sink.error);
    }
}
