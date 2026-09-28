package io.mosip.liveness.core.stream;

import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.EndReason;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FrameSink;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded hand-off of stream frames from a source's reader thread to the thread that owns a liveness session.
 *
 * <p>The reader thread uses this object as its {@link FrameSink}; the worker thread calls
 * {@link #deliverNext(FrameSink, long)} in a loop and passes each frame (and finally the end or error of the
 * stream) to the session. When the worker is slower than the stream, the <b>oldest</b> waiting frame is dropped,
 * so the session always analyses recent frames and memory stays bounded. With capacity 1
 * ({@link #latestOnly()}) this is the single-slot hand-off of design section 4.9.
 *
 * <p>Typical worker loop:
 * <pre>{@code
 * FrameHandOff handOff = FrameHandOff.latestOnly();
 * source.start(handOff);
 * while (!session.state().isTerminal() && !handOff.isEnded()) {
 *     handOff.deliverNext(session, 100);   // waits at most 100 ms
 *     session.tick();
 * }
 * }</pre>
 * Dropped frames are not wiped, because the preview may still show them. Thread-safe.
 */
public final class FrameHandOff implements FrameSink {

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Deque<FaceFrame> frames = new ArrayDeque<>();
    private DeviceError error;
    private EndReason endReason;
    private boolean terminalReceived;
    private boolean terminalDelivered;
    private long received;
    private long dropped;

    private FrameHandOff(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Creates a hand-off that keeps only the newest frame.
     *
     * @return the hand-off
     */
    public static FrameHandOff latestOnly() {
        return new FrameHandOff(1);
    }

    /**
     * Creates a hand-off that keeps up to {@code capacity} frames and drops the oldest when full.
     *
     * @param capacity frames kept; at least 1
     * @return the hand-off
     * @throws IllegalArgumentException if the capacity is below 1
     */
    public static FrameHandOff bounded(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        return new FrameHandOff(capacity);
    }

    /**
     * Queues a frame from the reader thread, dropping the oldest waiting frame when full. Frames that arrive
     * after the end or error of the stream are ignored.
     *
     * @param frame the frame
     */
    @Override
    public void onFrame(FaceFrame frame) {
        Objects.requireNonNull(frame, "frame");
        lock.lock();
        try {
            if (terminalReceived) {
                return;
            }
            received++;
            if (frames.size() >= capacity) {
                frames.pollFirst();
                dropped++;
            }
            frames.addLast(frame);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records the error that ended the stream; it is delivered after the waiting frames.
     *
     * @param error the device error
     */
    @Override
    public void onError(DeviceError error) {
        terminal(Objects.requireNonNull(error, "error"), null);
    }

    /**
     * Records the normal end of the stream; it is delivered after the waiting frames.
     *
     * @param reason why the stream ended
     */
    @Override
    public void onEnd(EndReason reason) {
        terminal(null, Objects.requireNonNull(reason, "reason"));
    }

    private void terminal(DeviceError e, EndReason r) {
        lock.lock();
        try {
            if (!terminalReceived) {
                terminalReceived = true;
                error = e;
                endReason = r;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for the next frame, or for the end of the stream once no frame is waiting, and passes it to the
     * target on the calling thread.
     *
     * @param target    receiver, normally the liveness session
     * @param timeoutMs longest wait in milliseconds; 0 does not wait
     * @return {@code true} if a frame or the end of the stream was delivered; {@code false} on timeout or when
     *         the end was already delivered
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public boolean deliverNext(FrameSink target, long timeoutMs) throws InterruptedException {
        Objects.requireNonNull(target, "target");
        FaceFrame frame;
        DeviceError e;
        EndReason r;
        lock.lock();
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
            while (frames.isEmpty() && !terminalReceived && remaining > 0) {
                remaining = changed.awaitNanos(remaining);
            }
            frame = frames.pollFirst();
            if (frame == null && (!terminalReceived || terminalDelivered)) {
                return false;
            }
            if (frame == null) {
                terminalDelivered = true;
            }
            e = error;
            r = endReason;
        } finally {
            lock.unlock();
        }
        if (frame != null) {
            target.onFrame(frame);
        } else if (e != null) {
            target.onError(e);
        } else {
            target.onEnd(r);
        }
        return true;
    }

    /**
     * Tells whether the end or error of the stream has been delivered to a target.
     *
     * @return {@code true} once nothing more will be delivered
     */
    public boolean isEnded() {
        lock.lock();
        try {
            return terminalDelivered;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of frames waiting.
     *
     * @return waiting frames
     */
    public int pending() {
        lock.lock();
        try {
            return frames.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of frames received from the reader thread.
     *
     * @return frame count
     */
    public long receivedFrames() {
        lock.lock();
        try {
            return received;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of frames dropped because the worker was busy.
     *
     * @return frame count
     */
    public long droppedFrames() {
        lock.lock();
        try {
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    /** Discards the waiting frames (for example when the session reaches {@code CAPTURE_READY}). */
    public void clear() {
        lock.lock();
        try {
            frames.clear();
        } finally {
            lock.unlock();
        }
    }
}
