package io.mosip.registration.livenessmanager.metrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Appends metrics lines ({@link MetricsReport#line}) to a file in the app's private storage, on one background
 * thread, so the capture screen never waits for storage.
 *
 * <p>The file is {@code files/liveness/metrics.log} of the app (see {@code LivenessRuntime}). When it grows past
 * {@link #DEFAULT_MAX_BYTES} it is renamed to {@code metrics.log.1} (replacing the previous one) and a new file is
 * started, so at most about twice that size is kept. The lines hold only aggregate, anonymised values.
 * A write failure is reported to the error callback and never reaches the caller.
 *
 * <p>Thread-safe.
 */
public final class MetricsLog implements AutoCloseable {

    /** Size after which the file is rotated. */
    public static final long DEFAULT_MAX_BYTES = 256L * 1024L;

    private final File file;
    private final long maxBytes;
    private final Consumer<IOException> onError;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "liveness-metrics");
        t.setDaemon(true);
        return t;
    });

    /**
     * Creates the log.
     *
     * @param file     the log file; its folder is created on the first write
     * @param maxBytes size after which the file is rotated
     * @param onError  receives write failures (for a Logcat warning)
     */
    public MetricsLog(File file, long maxBytes, Consumer<IOException> onError) {
        this.file = Objects.requireNonNull(file, "file");
        this.maxBytes = Math.max(1L, maxBytes);
        this.onError = Objects.requireNonNull(onError, "onError");
    }

    /**
     * Returns the log file.
     *
     * @return the current file
     */
    public File file() {
        return file;
    }

    /**
     * Appends a line in the background.
     *
     * @param line the text, without a line break
     */
    public void append(String line) {
        if (line == null) {
            return;
        }
        try {
            writer.execute(() -> write(line));
        } catch (RejectedExecutionException e) {
            // Closed: the line is dropped.
        }
    }

    /**
     * Appends a line on the calling thread (tests and shutdown).
     *
     * @param line the text, without a line break
     */
    synchronized void write(String line) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir.getName());
            }
            if (file.length() > maxBytes) {
                rotate();
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(line);
                w.write('\n');
            }
        } catch (IOException e) {
            onError.accept(e);
        }
    }

    /** Stops the background writer; lines already queued are still written. */
    @Override
    public void close() {
        writer.shutdown();
    }

    private void rotate() throws IOException {
        File previous = new File(file.getPath() + ".1");
        if (previous.exists() && !previous.delete()) {
            throw new IOException("cannot delete " + previous.getName());
        }
        if (!file.renameTo(previous)) {
            throw new IOException("cannot rotate " + file.getName());
        }
    }
}
