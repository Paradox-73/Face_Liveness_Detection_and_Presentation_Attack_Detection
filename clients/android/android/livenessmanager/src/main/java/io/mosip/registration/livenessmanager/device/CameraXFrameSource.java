package io.mosip.registration.livenessmanager.device;

import android.content.Context;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.device.DeviceError;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.FrameSink;
import io.mosip.liveness.core.device.StreamStats;
import io.mosip.liveness.core.image.PixelFormat;

/**
 * Front-camera frames from CameraX (design section 4.9, Android).
 *
 * <p>{@code ImageAnalysis} delivers {@code RGBA_8888} frames with {@code STRATEGY_KEEP_ONLY_LATEST} on one
 * background thread; each frame is copied into a tightly packed array and handed to the sink, which keeps only
 * the newest frame for the liveness worker. The frame's rotation comes from CameraX and is applied by the image
 * decoder. Analysis frames are what the sensor sees, not mirrored ({@code mirrored=false}); only the on-screen
 * preview ({@code PreviewView}) is mirrored like a selfie, so head-turn directions stay subject-relative.
 *
 * <p>Binds to the screen's lifecycle: the camera stops when the screen stops.
 */
public final class CameraXFrameSource implements FaceFrameSource {

    /** Requested analysis resolution (the engine analyses 640x480). */
    public static final Size ANALYSIS_SIZE = new Size(640, 480);

    private final Context context;
    private final LifecycleOwner owner;
    private final Preview.SurfaceProvider surfaceProvider;
    private final Clock clock;
    private final String sourceId;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "liveness-camera");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong delivered = new AtomicLong();
    private volatile long firstNanos;
    private volatile long lastNanos;
    private volatile ProcessCameraProvider provider;
    private volatile FrameSink sink;

    /**
     * Creates the source.
     *
     * @param context         any context
     * @param owner           lifecycle of the screen that shows the preview
     * @param surfaceProvider preview surface ({@code PreviewView.getSurfaceProvider()}), or {@code null}
     * @param clock           session clock used to time-stamp frames
     * @param sourceId        device serial written in each frame
     */
    public CameraXFrameSource(Context context, LifecycleOwner owner, Preview.SurfaceProvider surfaceProvider,
                              Clock clock, String sourceId) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.owner = Objects.requireNonNull(owner, "owner");
        this.surfaceProvider = surfaceProvider;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    }

    @Override
    public void start(FrameSink frameSink) {
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("camera source already started or closed");
        }
        this.sink = Objects.requireNonNull(frameSink, "sink");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> bind(future), ContextCompat.getMainExecutor(context));
    }

    @Override
    public StreamStats stats() {
        long n = delivered.get();
        double seconds = (lastNanos - firstNanos) / 1e9;
        double fps = n > 1 && seconds > 0 ? (n - 1) / seconds : 0.0;
        return new StreamStats(n, 0, 0, fps, 0.0);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ProcessCameraProvider p = provider;
        if (p != null) {
            ContextCompat.getMainExecutor(context).execute(p::unbindAll);
        }
        analysisExecutor.shutdown();
    }

    private void bind(ListenableFuture<ProcessCameraProvider> future) {
        if (closed.get()) {
            return;
        }
        try {
            ProcessCameraProvider p = future.get();
            provider = p;
            CameraSelector selector = p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
            ResolutionSelector resolution = new ResolutionSelector.Builder()
                    .setResolutionStrategy(new ResolutionStrategy(ANALYSIS_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build();
            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolution)
                    .build();
            analysis.setAnalyzer(analysisExecutor, this::analyze);
            p.unbindAll();
            if (surfaceProvider != null) {
                Preview preview = new Preview.Builder().setResolutionSelector(resolution).build();
                preview.setSurfaceProvider(surfaceProvider);
                p.bindToLifecycle(owner, selector, preview, analysis);
            } else {
                p.bindToLifecycle(owner, selector, analysis);
            }
        } catch (Exception e) {
            fail(DeviceErrorCode.CAMERA_IN_USE, "camera could not be opened", e);
        }
    }

    private void analyze(@NonNull ImageProxy image) {
        try {
            if (closed.get()) {
                return;
            }
            FrameSink s = sink;
            if (s == null) {
                return;
            }
            long now = clock.monotonicNanos();
            long n = delivered.incrementAndGet();
            if (n == 1) {
                firstNanos = now;
            }
            lastNanos = now;
            s.onFrame(FaceFrame.builder().data(copyRgba(image)).format(PixelFormat.RGBA8888)
                    .size(image.getWidth(), image.getHeight())
                    .rotationDegrees(image.getImageInfo().getRotationDegrees()).mirrored(false)
                    .monotonicNanos(now).sequence(n).sourceId(sourceId).build());
        } catch (RuntimeException e) {
            fail(DeviceErrorCode.INVALID_STREAM, "camera frame could not be read", e);
        } finally {
            image.close();
        }
    }

    /** Copies plane 0 of an RGBA_8888 image into a tightly packed array (rows may be padded). */
    static byte[] copyRgba(ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = plane.getRowStride();
        int rowBytes = width * 4;
        byte[] out = new byte[rowBytes * height];
        buffer.rewind();
        if (rowStride == rowBytes) {
            buffer.get(out, 0, out.length);
        } else {
            for (int y = 0; y < height; y++) {
                buffer.position(y * rowStride);
                buffer.get(out, y * rowBytes, rowBytes);
            }
        }
        return out;
    }

    private void fail(DeviceErrorCode code, String message, Throwable cause) {
        FrameSink s = sink;
        if (s != null && closed.compareAndSet(false, true)) {
            s.onError(DeviceError.of(code, message, cause));
            analysisExecutor.shutdown();
        }
    }
}
