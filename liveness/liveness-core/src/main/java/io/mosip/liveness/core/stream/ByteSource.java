/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.stream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Buffered byte reader over an {@link InputStream} with line reading and push-back, used by
 * {@link MjpegFrameReader}. Every method returns at end of stream instead of retrying, so a closed stream can
 * never make a caller spin. Not thread-safe.
 */
final class ByteSource {

    /** Returned by {@link #readLine(int)} when a line is longer than allowed; the rest follows on later calls. */
    static final String LINE_TOO_LONG = new String("<line too long>");

    private final InputStream in;
    private byte[] buf = new byte[16 * 1024];
    private int pos;
    private int limit;
    private boolean eof;

    ByteSource(InputStream in) {
        this.in = in;
    }

    /**
     * Reads one byte.
     *
     * @return the byte 0-255, or -1 at end of stream
     * @throws IOException if the stream fails
     */
    int read() throws IOException {
        if (pos >= limit && !fill()) {
            return -1;
        }
        return buf[pos++] & 0xFF;
    }

    /**
     * Reads up to {@code len} bytes, fewer only at end of stream.
     *
     * @return the number of bytes read
     * @throws IOException if the stream fails
     */
    int readFully(byte[] dst, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            if (pos >= limit && !fill()) {
                break;
            }
            int n = Math.min(len - done, limit - pos);
            System.arraycopy(buf, pos, dst, off + done, n);
            pos += n;
            done += n;
        }
        return done;
    }

    /**
     * Reads one line ended by LF (a preceding CR is removed), decoded as ISO-8859-1.
     *
     * @param maxLength longest line accepted
     * @return the line; {@link #LINE_TOO_LONG} after {@code maxLength} bytes without a line end; {@code null} at
     *         end of stream with nothing read
     * @throws IOException if the stream fails
     */
    String readLine(int maxLength) throws IOException {
        byte[] line = new byte[Math.min(maxLength, 256)];
        int n = 0;
        while (true) {
            int c = read();
            if (c < 0) {
                return n == 0 ? null : text(line, n);
            }
            if (c == '\n') {
                return text(line, n);
            }
            if (n == maxLength) {
                return LINE_TOO_LONG;
            }
            if (n == line.length) {
                byte[] bigger = new byte[Math.min(maxLength, line.length * 2)];
                System.arraycopy(line, 0, bigger, 0, n);
                line = bigger;
            }
            line[n++] = (byte) c;
        }
    }

    /**
     * Puts bytes back in front of the unread data, so the next reads return them first.
     */
    void unread(byte[] data, int off, int len) {
        if (len <= 0) {
            return;
        }
        int remaining = limit - pos;
        byte[] merged = new byte[Math.max(buf.length, len + remaining)];
        System.arraycopy(data, off, merged, 0, len);
        System.arraycopy(buf, pos, merged, len, remaining);
        buf = merged;
        pos = 0;
        limit = len + remaining;
    }

    /**
     * Tells whether the end of the stream was reached.
     *
     * @return {@code true} once a read returned end of stream
     */
    boolean isEof() {
        return eof && pos >= limit;
    }

    private boolean fill() throws IOException {
        if (eof) {
            return false;
        }
        int n = in.read(buf, 0, buf.length);
        if (n <= 0) {
            // InputStream.read returns 0 only for a zero-length request; treat anything else as the end.
            eof = true;
            return false;
        }
        pos = 0;
        limit = n;
        return true;
    }

    private static String text(byte[] line, int n) {
        int end = n;
        if (end > 0 && line[end - 1] == '\r') {
            end--;
        }
        return new String(line, 0, end, StandardCharsets.ISO_8859_1);
    }
}
