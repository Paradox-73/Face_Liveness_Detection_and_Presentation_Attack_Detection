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
import io.mosip.liveness.core.device.StreamStats;
import io.mosip.liveness.core.image.PixelFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads an SBI face stream ({@code multipart/x-mixed-replace} MJPEG) and hands each JPEG part to a
 * {@link FrameSink} as a {@link FaceFrame} (design sections 4.9 and 4.12, change D2).
 *
 * <p>It replaces the parse loop of the desktop client's {@code Streamer.retrieveNextImage}, which searches for
 * {@code Content-Length:} and then loops on {@code read() != 255}; at end of stream that loop never ends. This
 * reader stops on the first end of stream, stall, error or {@link #stop()}, and then makes exactly one terminal
 * call: {@link FrameSink#onEnd(EndReason)} or {@link FrameSink#onError(DeviceError)}.
 *
 * <p>Accepted input:
 * <ul>
 *   <li>parts separated by a boundary line ({@code --BoundaryString} on MockMDS); the boundary is learnt from the
 *       first such line, from a {@code Content-Type: multipart/...; boundary=} header, or given to the builder;</li>
 *   <li>part headers in any letter case, with or without a space after the colon, in any order, with extra
 *       headers ignored;</li>
 *   <li>a body of {@code Content-Length} bytes, or, without that header, the bytes up to the next boundary;</li>
 *   <li>HTTP response headers, blank lines and other text (for example the JSON MockMDS appends after the
 *       stream) between parts, which are skipped.</li>
 * </ul>
 * A part counts as invalid, and is not delivered, when its body is not a complete JPEG (starts with FF D8 and
 * ends with FF D9), when its type is not {@code image/jpeg}, when the declared length is wrong, or when it is
 * larger than the frame size limit. After a wrong length the reader looks for the next boundary inside the
 * bytes it read, so the following part is not lost. Too many invalid parts in a row end the stream with
 * {@link DeviceErrorCode#INVALID_STREAM}.
 *
 * <p>Timeouts: a read that times out (for example a socket with a 2 s read timeout, which throws
 * {@link InterruptedIOException}) ends the stream with {@link DeviceErrorCode#STALLED}; no valid frame within the
 * first-frame deadline ends it with {@link DeviceErrorCode#FIRST_FRAME_TIMEOUT}; the stream timeout ends it with
 * {@link EndReason#STREAM_TIMEOUT}. Deadlines are checked between parts and while skipping data, so they need a
 * stream that keeps delivering bytes or times out its reads.
 *
 * <p>{@link #run()} runs on the caller's reader thread; {@link #stop()} and {@link #stats()} may be called from
 * any thread. The input stream is closed when {@link #run()} returns.
 */
public final class MjpegFrameReader implements Runnable {

    /** Default largest accepted JPEG part, in bytes. */
    public static final int DEFAULT_MAX_FRAME_BYTES = 4 * 1024 * 1024;
    /** Default number of invalid parts in a row that ends the stream. */
    public static final int DEFAULT_MAX_CONSECUTIVE_INVALID = 25;

    private static final int MAX_HEADER_LINE = 1024;
    private static final int MAX_HEADERS = 32;
    private static final int DEADLINE_CHECK_BYTES = 64 * 1024;

    private final InputStream input;
    private final ByteSource source;
    private final FrameSink sink;
    private final Clock clock;
    private final String sourceId;
    private final int maxFrameBytes;
    private final long firstFrameTimeoutNanos;
    private final long streamTimeoutNanos;
    private final int maxConsecutiveInvalid;
    private final FrameRateMeter meter = new FrameRateMeter();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private volatile boolean stopped;
    private String boundaryLine;
    private long sequence;
    private long startNanos;
    private int consecutiveInvalid;

    private MjpegFrameReader(Builder b) {
        this.input = b.input;
        this.source = new ByteSource(b.input);
        this.sink = b.sink;
        this.clock = b.clock;
        this.sourceId = b.sourceId;
        this.maxFrameBytes = b.maxFrameBytes;
        this.firstFrameTimeoutNanos = b.firstFrameTimeoutMs * 1_000_000L;
        this.streamTimeoutNanos = b.streamTimeoutMs * 1_000_000L;
        this.maxConsecutiveInvalid = b.maxConsecutiveInvalid;
        this.boundaryLine = b.boundaryLine;
    }

    /**
     * Starts a builder.
     *
     * @param input the MJPEG response body (or the whole HTTP response)
     * @param sink  receiver of frames and of the terminal call
     * @return a builder with the {@link StreamRequest} default timeouts and the system clock
     */
    public static Builder builder(InputStream input, FrameSink sink) {
        return new Builder(input, sink);
    }

    /**
     * Reads the stream until it ends, fails, times out or is stopped. Makes exactly one terminal call to the
     * sink and closes the input stream.
     *
     * @throws IllegalStateException if called more than once
     */
    @Override
    public void run() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("MjpegFrameReader.run may be called once");
        }
        startNanos = clock.monotonicNanos();
        try {
            readParts();
        } catch (InterruptedIOException e) {
            failOrClose(DeviceErrorCode.STALLED, "no stream data within the read timeout", e);
        } catch (IOException e) {
            failOrClose(DeviceErrorCode.DISCONNECTED, "stream read failed: " + e.getClass().getSimpleName(), e);
        } finally {
            closeQuietly();
        }
    }

    /** Stops the reader from any thread; the reader ends with {@link EndReason#CLOSED}. Idempotent. */
    public void stop() {
        stopped = true;
        closeQuietly();
    }

    /**
     * Returns the statistics measured since the start; safe from any thread.
     *
     * @return delivered frames, invalid parts, frame rate and jitter (nothing is dropped by the reader)
     */
    public StreamStats stats() {
        return new StreamStats(delivered.get(), 0, invalid.get(), meter.fps(), meter.jitterMs());
    }

    private void readParts() throws IOException {
        while (!ended.get()) {
            Part part = readHeaders();
            if (ended.get()) {
                return;
            }
            if (part == null) {
                end(stopped ? EndReason.CLOSED : EndReason.END_OF_STREAM);
                return;
            }
            if (part.lengthDeclared) {
                readSizedBody(part);
            } else {
                readDelimitedBody(part);
            }
            if (source.isEof() && !ended.get()) {
                end(stopped ? EndReason.CLOSED : EndReason.END_OF_STREAM);
            }
        }
    }

    /**
     * Skips to the next part and reads its headers.
     *
     * @return the part; {@code null} at end of stream or once a terminal call was made
     */
    private Part readHeaders() throws IOException {
        Part part = null;
        while (!checkDeadlines()) {
            String line = source.readLine(MAX_HEADER_LINE);
            if (line == null) {
                return null;
            }
            if (line == ByteSource.LINE_TOO_LONG) {
                part = null;
            } else if (line.isEmpty()) {
                if (part != null && part.isEnvelope()) {
                    learnBoundary(part.contentType);
                    part = null;
                } else if (part != null && part.isPartHeader()) {
                    return part;
                }
            } else if (line.startsWith("--")) {
                if (isClosingBoundary(line)) {
                    return null;
                }
                if (boundaryLine == null) {
                    boundaryLine = line.trim();
                }
                part = new Part();
            } else {
                part = parseHeader(part, line);
            }
        }
        return null;
    }

    private static Part parseHeader(Part part, String line) {
        int colon = line.indexOf(':');
        if (colon <= 0 || !isToken(line, colon)) {
            return null;
        }
        Part p = part == null ? new Part() : part;
        if (++p.headerCount > MAX_HEADERS) {
            return null;
        }
        String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
        String value = line.substring(colon + 1).trim();
        if ("content-length".equals(name)) {
            p.lengthDeclared = true;
            p.contentLength = parseLength(value);
        } else if ("content-type".equals(name)) {
            p.contentType = value;
        }
        return p;
    }

    private static int parseLength(String value) {
        try {
            long n = Long.parseLong(value);
            return n < 0 || n > Integer.MAX_VALUE ? -1 : (int) n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isToken(String line, int end) {
        for (int i = 0; i < end; i++) {
            char c = line.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-'
                    || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private boolean isClosingBoundary(String line) {
        return boundaryLine != null && line.trim().equals(boundaryLine + "--");
    }

    private void learnBoundary(String contentType) {
        int at = contentType.toLowerCase(Locale.ROOT).indexOf("boundary=");
        if (at < 0 || boundaryLine != null) {
            return;
        }
        String value = contentType.substring(at + "boundary=".length()).trim();
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).trim();
        }
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.isEmpty()) {
            // MockMDS declares "boundary=--BoundaryString" and writes "--BoundaryString" lines; accept both forms.
            boundaryLine = value.startsWith("--") ? value : "--" + value;
        }
    }

    private void readSizedBody(Part part) throws IOException {
        int n = part.contentLength;
        if (n <= 0 || n > maxFrameBytes) {
            invalidPart();
            return;
        }
        byte[] body = new byte[n];
        int got = source.readFully(body, 0, n);
        if (got < n) {
            invalidPart();
            return;
        }
        if (part.isJpegType() && isCompleteJpeg(body, n)) {
            deliver(body);
            return;
        }
        int boundaryAt = indexOfBoundary(body);
        if (boundaryAt > 0) {
            source.unread(body, boundaryAt, n - boundaryAt);
        }
        invalidPart();
    }

    private void readDelimitedBody(Part part) throws IOException {
        byte[] delimiter = boundaryLine == null ? null : ("\n" + boundaryLine).getBytes(StandardCharsets.ISO_8859_1);
        GrowableBytes acc = new GrowableBytes();
        while (true) {
            int c = source.read();
            if (c < 0) {
                if (acc.size() > 0) {
                    invalidPart();
                }
                return;
            }
            acc.add(c);
            if (acc.size() > maxFrameBytes) {
                invalidPart();
                return;
            }
            if (acc.size() % DEADLINE_CHECK_BYTES == 0 && checkDeadlines()) {
                return;
            }
            if (delimiter != null && acc.endsWith(delimiter)) {
                int end = trimLineEnd(acc.array(), acc.size() - delimiter.length);
                byte[] tail = boundaryLine.getBytes(StandardCharsets.ISO_8859_1);
                source.unread(tail, 0, tail.length);
                acceptOrReject(part, acc.copy(end));
                return;
            }
            if (delimiter == null && acc.size() >= 4 && acc.endsWithEoi() && acc.startsWithSoi()) {
                acceptOrReject(part, acc.copy(acc.size()));
                return;
            }
        }
    }

    private void acceptOrReject(Part part, byte[] body) {
        if (part.isJpegType() && isCompleteJpeg(body, body.length)) {
            deliver(body);
        } else {
            invalidPart();
        }
    }

    private static int trimLineEnd(byte[] data, int end) {
        int e = end;
        while (e > 0 && (data[e - 1] == '\r' || data[e - 1] == '\n')) {
            e--;
        }
        return e;
    }

    /** A complete JPEG starts with SOI (FF D8) and ends with EOI (FF D9), ignoring up to 4 trailing CR, LF or NUL. */
    static boolean isCompleteJpeg(byte[] data, int length) {
        if (length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return false;
        }
        int end = length;
        int trimmed = 0;
        while (end > 2 && trimmed < 4 && (data[end - 1] == '\r' || data[end - 1] == '\n' || data[end - 1] == 0)) {
            end--;
            trimmed++;
        }
        return (data[end - 2] & 0xFF) == 0xFF && (data[end - 1] & 0xFF) == 0xD9;
    }

    /** Finds the start of a boundary line inside a body read with a wrong {@code Content-Length}. */
    private int indexOfBoundary(byte[] body) {
        String marker = boundaryLine == null ? "\n--" : "\n" + boundaryLine;
        byte[] m = marker.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + m.length <= body.length; i++) {
            for (int j = 0; j < m.length; j++) {
                if (body[i + j] != m[j]) {
                    continue outer;
                }
            }
            return i + 1;
        }
        return -1;
    }

    private void deliver(byte[] jpeg) {
        long now = clock.monotonicNanos();
        FaceFrame frame = FaceFrame.builder().data(jpeg).format(PixelFormat.JPEG).monotonicNanos(now)
                .sequence(sequence++).sourceId(sourceId).build();
        meter.record(now, frame.sequence());
        delivered.incrementAndGet();
        consecutiveInvalid = 0;
        try {
            sink.onFrame(frame);
        } catch (RuntimeException e) {
            fail(DeviceErrorCode.UNKNOWN, "frame sink failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private void invalidPart() {
        invalid.incrementAndGet();
        if (++consecutiveInvalid >= maxConsecutiveInvalid) {
            fail(DeviceErrorCode.INVALID_STREAM, consecutiveInvalid + " invalid stream parts in a row", null);
        }
    }

    /**
     * Checks the stop flag and the deadlines.
     *
     * @return {@code true} if the stream has ended (now or before)
     */
    private boolean checkDeadlines() {
        if (ended.get()) {
            return true;
        }
        if (stopped) {
            end(EndReason.CLOSED);
            return true;
        }
        long elapsed = clock.monotonicNanos() - startNanos;
        if (delivered.get() == 0 && elapsed > firstFrameTimeoutNanos) {
            fail(DeviceErrorCode.FIRST_FRAME_TIMEOUT, "no valid frame within the first-frame deadline", null);
            return true;
        }
        if (elapsed > streamTimeoutNanos) {
            end(EndReason.STREAM_TIMEOUT);
            return true;
        }
        return false;
    }

    private void failOrClose(DeviceErrorCode code, String message, IOException cause) {
        if (stopped) {
            end(EndReason.CLOSED);
        } else {
            fail(code, message, cause);
        }
    }

    private void end(EndReason reason) {
        if (ended.compareAndSet(false, true)) {
            sink.onEnd(reason);
        }
    }

    private void fail(DeviceErrorCode code, String message, Throwable cause) {
        if (ended.compareAndSet(false, true)) {
            sink.onError(DeviceError.of(code, message, cause));
        }
    }

    private void closeQuietly() {
        try {
            input.close();
        } catch (IOException | RuntimeException e) {
            // Nothing useful can be done; the stream is being abandoned.
        }
    }

    /** Headers of one multipart part (or of the HTTP response envelope). */
    private static final class Part {
        private int headerCount;
        private boolean lengthDeclared;
        private int contentLength = -1;
        private String contentType;

        private static String lower(String s) {
            return s.toLowerCase(Locale.ROOT);
        }

        boolean isEnvelope() {
            return contentType != null && lower(contentType).startsWith("multipart/");
        }

        boolean isPartHeader() {
            return lengthDeclared || contentType != null;
        }

        boolean isJpegType() {
            String t = contentType == null ? null : lower(contentType);
            return t == null || t.startsWith("image/jpeg") || t.startsWith("image/jpg");
        }
    }

    /** Minimal growable byte array. */
    private static final class GrowableBytes {
        private byte[] data = new byte[32 * 1024];
        private int size;

        void add(int b) {
            if (size == data.length) {
                byte[] bigger = new byte[data.length * 2];
                System.arraycopy(data, 0, bigger, 0, size);
                data = bigger;
            }
            data[size++] = (byte) b;
        }

        int size() {
            return size;
        }

        byte[] array() {
            return data;
        }

        byte[] copy(int length) {
            byte[] out = new byte[length];
            System.arraycopy(data, 0, out, 0, length);
            return out;
        }

        boolean endsWith(byte[] suffix) {
            if (size < suffix.length || data[size - 1] != suffix[suffix.length - 1]) {
                return false;
            }
            int start = size - suffix.length;
            for (int i = 0; i < suffix.length; i++) {
                if (data[start + i] != suffix[i]) {
                    return false;
                }
            }
            return true;
        }

        boolean startsWithSoi() {
            return (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
        }

        boolean endsWithEoi() {
            return (data[size - 2] & 0xFF) == 0xFF && (data[size - 1] & 0xFF) == 0xD9;
        }
    }

    /** Builder for {@link MjpegFrameReader}. */
    public static final class Builder {

        private final InputStream input;
        private final FrameSink sink;
        private Clock clock = Clock.system();
        private String sourceId = "sbi-stream";
        private String boundaryLine;
        private int maxFrameBytes = DEFAULT_MAX_FRAME_BYTES;
        private long firstFrameTimeoutMs = StreamRequest.DEFAULT_FIRST_FRAME_TIMEOUT_MS;
        private long streamTimeoutMs = StreamRequest.DEFAULT_STREAM_TIMEOUT_MS;
        private int maxConsecutiveInvalid = DEFAULT_MAX_CONSECUTIVE_INVALID;

        private Builder(InputStream input, FrameSink sink) {
            this.input = Objects.requireNonNull(input, "input");
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        /**
         * Sets the clock that timestamps frames and measures deadlines; use the session clock.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets the source identifier written into each frame, normally the device serial number.
         *
         * @param sourceId identifier; not empty
         * @return this builder
         */
        public Builder sourceId(String sourceId) {
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
            return this;
        }

        /**
         * Sets the boundary when it is known from the HTTP {@code Content-Type} header.
         *
         * @param boundary the {@code boundary=} parameter, with or without the leading {@code --}
         * @return this builder
         */
        public Builder boundary(String boundary) {
            Objects.requireNonNull(boundary, "boundary");
            String b = boundary.trim();
            this.boundaryLine = b.isEmpty() ? null : (b.startsWith("--") ? b : "--" + b);
            return this;
        }

        /**
         * Sets the largest accepted part; larger parts are invalid.
         *
         * @param maxFrameBytes bytes; at least 1024
         * @return this builder
         */
        public Builder maxFrameBytes(int maxFrameBytes) {
            if (maxFrameBytes < 1024) {
                throw new IllegalArgumentException("maxFrameBytes must be at least 1024");
            }
            this.maxFrameBytes = maxFrameBytes;
            return this;
        }

        /**
         * Sets the time allowed until the first valid frame; default 3,000 ms.
         *
         * @param firstFrameTimeoutMs milliseconds; positive
         * @return this builder
         */
        public Builder firstFrameTimeoutMs(long firstFrameTimeoutMs) {
            this.firstFrameTimeoutMs = positive(firstFrameTimeoutMs);
            return this;
        }

        /**
         * Sets the stream timeout; default 60,000 ms ({@code liveness.stream.timeout_ms}).
         *
         * @param streamTimeoutMs milliseconds; positive
         * @return this builder
         */
        public Builder streamTimeoutMs(long streamTimeoutMs) {
            this.streamTimeoutMs = positive(streamTimeoutMs);
            return this;
        }

        /**
         * Copies the first-frame and stream timeouts of a stream request.
         *
         * @param request the stream request
         * @return this builder
         */
        public Builder timeouts(StreamRequest request) {
            this.firstFrameTimeoutMs = request.firstFrameTimeoutMs();
            this.streamTimeoutMs = request.streamTimeoutMs();
            return this;
        }

        /**
         * Sets how many invalid parts in a row end the stream; default {@value #DEFAULT_MAX_CONSECUTIVE_INVALID}.
         *
         * @param maxConsecutiveInvalid parts; at least 1
         * @return this builder
         */
        public Builder maxConsecutiveInvalid(int maxConsecutiveInvalid) {
            if (maxConsecutiveInvalid < 1) {
                throw new IllegalArgumentException("maxConsecutiveInvalid must be at least 1");
            }
            this.maxConsecutiveInvalid = maxConsecutiveInvalid;
            return this;
        }

        /**
         * Creates the reader.
         *
         * @return the reader
         */
        public MjpegFrameReader build() {
            if (sourceId.isEmpty()) {
                throw new IllegalArgumentException("sourceId must not be empty");
            }
            return new MjpegFrameReader(this);
        }

        private static long positive(long ms) {
            if (ms <= 0) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            return ms;
        }
    }
}
