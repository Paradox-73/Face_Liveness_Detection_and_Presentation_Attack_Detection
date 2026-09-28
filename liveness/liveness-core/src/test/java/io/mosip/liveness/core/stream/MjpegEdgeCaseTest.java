package io.mosip.liveness.core.stream;

import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.EndReason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Header and body edge cases of the MJPEG reader. */
class MjpegEdgeCaseTest {

    private final Random random = new Random(17);

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

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] sized(byte[] body) {
        return concat(ascii("--B\r\nContent-Type: image/jpeg\r\nContent-Length: " + body.length + "\r\n\r\n"), body,
                ascii("\r\n"));
    }

    private static MjpegFrameReaderTest.Sink run(byte[] stream, int maxFrameBytes) {
        MjpegFrameReaderTest.Sink sink = new MjpegFrameReaderTest.Sink();
        MjpegFrameReader.builder(new ByteArrayInputStream(stream), sink).maxFrameBytes(maxFrameBytes).build().run();
        assertEquals(1, sink.terminals.get());
        return sink;
    }

    @Test
    void overlongHeaderLinesAndTooManyHeadersAreSkipped() {
        char[] longLine = new char[3000];
        Arrays.fill(longLine, 'h');
        StringBuilder many = new StringBuilder("--B\r\n");
        for (int i = 0; i < 40; i++) {
            many.append("X-H").append(i).append(": v\r\n");
        }
        many.append("Content-Length: 10\r\n\r\n0123456789\r\n");
        byte[] good = jpeg(100);
        MjpegFrameReaderTest.Sink sink = run(concat(ascii("--B\r\nX-Long: " + new String(longLine) + "\r\n\r\n"),
                ascii(many.toString()), sized(good)), 4096);
        assertEquals(1, sink.frames.size());
        assertArrayEquals(good, sink.frames.get(0).data());
    }

    @Test
    void boundaryParameterWithMoreParameters() {
        byte[] a = jpeg(80);
        byte[] stream = concat(ascii("Content-Type: multipart/x-mixed-replace;boundary=zz; charset=x\r\n\r\n"),
                ascii("--zz\r\nContent-Type: image/jpeg\r\n\r\n"), a, ascii("\r\n--zz--\r\n"));
        MjpegFrameReaderTest.Sink sink = run(stream, 4096);
        assertEquals(1, sink.frames.size());
        MjpegFrameReaderTest.Sink noParam = run(concat(ascii("Content-Type: multipart/mixed\r\n\r\n"), sized(a)), 4096);
        assertEquals(1, noParam.frames.size());
    }

    @Test
    void delimitedBodiesThatAreTruncatedTooLargeOrNotJpeg() {
        byte[] head = ascii("--B\r\nContent-Type: image/jpeg\r\n\r\n");
        MjpegFrameReaderTest.Sink truncated = run(concat(head, Arrays.copyOf(jpeg(500), 200)), 4096);
        assertEquals(0, truncated.frames.size());
        assertEquals(EndReason.END_OF_STREAM, truncated.end);

        byte[] big = jpeg(3000);
        MjpegFrameReaderTest.Sink tooLarge = run(concat(head, big, ascii("\r\n--B\r\n"), sized(jpeg(50))), 2048);
        assertEquals(1, tooLarge.frames.size());

        byte[] notJpeg = ascii("plain text body");
        MjpegFrameReaderTest.Sink text = run(concat(head, notJpeg, ascii("\r\n--B\r\n"), sized(jpeg(60))), 4096);
        assertEquals(1, text.frames.size());
    }

    @Test
    void largeDelimitedBodyGrowsTheBuffer() {
        byte[] big = jpeg(100_000);
        MjpegFrameReaderTest.Sink sink = run(concat(ascii("--B\r\nContent-Type: image/jpeg\r\n\r\n"), big,
                ascii("\r\n--B--\r\n")), MjpegFrameReader.DEFAULT_MAX_FRAME_BYTES);
        assertEquals(1, sink.frames.size());
        assertArrayEquals(big, sink.frames.get(0).data());
    }

    @Test
    void stopBeforeRunEndsAtOnce() {
        MjpegFrameReaderTest.Sink sink = new MjpegFrameReaderTest.Sink();
        MjpegFrameReader r = MjpegFrameReader.builder(new ByteArrayInputStream(sized(jpeg(50))), sink).build();
        r.stop();
        r.run();
        assertEquals(EndReason.CLOSED, sink.end);
        assertEquals(0, sink.frames.size());
    }

    @Test
    void readErrorAfterStopIsAClose() {
        MjpegFrameReaderTest.Sink sink = new MjpegFrameReaderTest.Sink();
        MjpegFrameReader[] reader = new MjpegFrameReader[1];
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                reader[0].stop();
                throw new IOException("Socket closed");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("already closed");
            }
        };
        reader[0] = MjpegFrameReader.builder(failing, sink).build();
        reader[0].run();
        assertEquals(EndReason.CLOSED, sink.end);
        assertTrue(sink.error == null);
    }

    @Test
    void invalidStreamCountsAreReported() {
        byte[] bad = jpeg(100);
        bad[1] = 0;
        MjpegFrameReaderTest.Sink sink = new MjpegFrameReaderTest.Sink();
        MjpegFrameReader r = MjpegFrameReader.builder(new ByteArrayInputStream(concat(sized(bad), sized(bad))), sink)
                .maxConsecutiveInvalid(2).build();
        r.run();
        assertEquals(DeviceErrorCode.INVALID_STREAM, sink.error.code());
        assertEquals(2, r.stats().invalidParts());
    }
}
