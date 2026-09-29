package io.mosip.registration.livenessmanager.device;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.StreamStats;
import io.mosip.liveness.core.image.PixelFormat;

/**
 * Plays a folder of JPEG or PNG frames as a camera stream, for automated tests and emulator demos in place of a
 * person in front of the camera. Frames are sent in file-name order at a fixed rate on one background thread;
 * with {@code loop} the sequence repeats until the source is closed, otherwise the stream ends with
 * {@link EndReason#END_OF_STREAM}.
 *
 * <p>Only debug builds offer it on the capture screen (developer setting); release builds always use the camera.
 */
public final class ReplayFrameSource implements FaceFrameSource {

    private final List<byte[]> frames;
    private final PixelFormat format;
    private final int fps;
    private final boolean loop;
    private final Clock clock;
    private final String sourceId;
    private final Consumer<byte[]> preview;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong delivered = new AtomicLong();
    private volatile Thread thread;

    /**
     * Creates a source over frames already in memory.
     *
     * @param frames   encoded frames; not copied, not modified
     * @param fps      frames per second, 1-60
     * @param loop     whether to repeat the sequence
     * @param clock    session clock used to time-stamp frames
     * @param sourceId device serial written in each frame
     * @param preview  receives each frame's bytes for display, or {@code null}
     */
    public ReplayFrameSource(List<byte[]> frames, int fps, boolean loop, Clock clock, String sourceId,
                             Consumer<byte[]> preview) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("replay needs at least one frame");
        }
        if (fps < 1 || fps > 60) {
            throw new IllegalArgumentException("fps must be 1-60: " + fps);
        }
        this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
        this.format = isPng(frames.get(0)) ? PixelFormat.PNG : PixelFormat.JPEG;
        this.fps = fps;
        this.loop = loop;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.preview = preview;
    }

    /**
     * Reads the frames of an asset folder ({@code .jpg}, {@code .jpeg}, {@code .png}), sorted by name.
     *
     * @param assets asset manager
     * @param folder folder inside the assets
     * @return the frames
     * @throws DeviceException {@code STREAM_OPEN_FAILED} if the folder holds no frame
     */
    public static List<byte[]> readAssets(AssetManager assets, String folder) throws DeviceException {
        try {
            String[] names = assets.list(folder);
            List<byte[]> out = new ArrayList<>();
            if (names != null) {
                Arrays.sort(names);
                for (String name : names) {
                    if (isFrameFile(name)) {
                        try (InputStream in = assets.open(folder + "/" + name)) {
                            out.add(readAll(in));
                        }
                    }
                }
            }
            return requireFrames(out, folder);
        } catch (IOException e) {
            throw new DeviceException(DeviceErrorCode.STREAM_OPEN_FAILED, "replay folder cannot be read", e);
        }
    }

    /**
     * Reads the frames of a folder on the device ({@code .jpg}, {@code .jpeg}, {@code .png}), sorted by name.
     *
     * @param folder the folder
     * @return the frames
     * @throws DeviceException {@code STREAM_OPEN_FAILED} if the folder holds no frame
     */
    public static List<byte[]> readDirectory(File folder) throws DeviceException {
        File[] files = folder.listFiles((dir, name) -> isFrameFile(name));
        List<byte[]> out = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                try (InputStream in = new FileInputStream(f)) {
                    out.add(readAll(in));
                } catch (IOException e) {
                    throw new DeviceException(DeviceErrorCode.STREAM_OPEN_FAILED, "replay frame cannot be read", e);
                }
            }
        }
        return requireFrames(out, folder.getName());
    }

    @Override
    public void start(FrameSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("replay source already started or closed");
        }
        Thread t = new Thread(() -> play(sink), "liveness-replay");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public StreamStats stats() {
        return new StreamStats(delivered.get(), 0, 0, fps, 0.0);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Thread t = thread;
            if (t != null) {
                t.interrupt();
            }
        }
    }

    private void play(FrameSink sink) {
        long periodNanos = 1_000_000_000L / fps;
        long next = System.nanoTime();
        int index = 0;
        while (!closed.get()) {
            if (index == frames.size()) {
                if (!loop) {
                    sink.onEnd(EndReason.END_OF_STREAM);
                    return;
                }
                index = 0;
            }
            byte[] data = frames.get(index++);
            long seq = delivered.incrementAndGet();
            if (preview != null) {
                preview.accept(data);
            }
            // Each frame gets its own copy: the session owns and wipes the arrays it is given.
            sink.onFrame(FaceFrame.builder().data(Arrays.copyOf(data, data.length)).format(format).size(0, 0)
                    .monotonicNanos(clock.monotonicNanos()).sequence(seq).sourceId(sourceId).build());
            next += periodNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static List<byte[]> requireFrames(List<byte[]> frames, String name) throws DeviceException {
        if (frames.isEmpty()) {
            throw new DeviceException(DeviceErrorCode.STREAM_OPEN_FAILED, "replay folder has no frames: " + name);
        }
        return frames;
    }

    private static boolean isFrameFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
    }

    private static boolean isPng(byte[] data) {
        return data.length > 4 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
