/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.Bundle;
import android.os.Debug;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.onnx.OnnxModelSet;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions.ExecutionProvider;
import io.mosip.registration.livenessmanager.device.ReplayFrameSource;
import io.mosip.registration.livenessmanager.engine.AssetModelSource;
import io.mosip.registration.livenessmanager.image.AndroidImageDecoder;

/**
 * Per-frame latency of the liveness engine on the device that runs the test (benchmark for scripts/android-perf.sh).
 *
 * <p>One run measures one setting: an ONNX Runtime execution provider and an intra-op thread count. Each iteration
 * decodes the replay frame (JPEG, as the capture path does), runs the face analyser (YuNet, Face Mesh, quality) and
 * the passive PAD (MiniFASNet ensemble), and records the wall-clock time of the three steps together. The first
 * frame is timed on its own, right after the models are loaded, so it includes one-off work such as lazy
 * initialisation of the execution provider. The test asserts nothing about time (devices differ widely); it
 * asserts only that a face is found in every frame.
 *
 * <p>Arguments (adb {@code -e name value}): {@code provider} (CPU, XNNPACK, NNAPI; default CPU), {@code threads}
 * (default 2), {@code frames} (measured frames, default 100), {@code warmup} (frames before measuring, default 20).
 * With {@code frames=0} the test is skipped, so a plain connectedAndroidTest run stays fast. Results go out as
 * instrumentation status lines ({@code perf_*}) and one {@code LivenessPerf} log line.
 */
@RunWith(AndroidJUnit4.class)
public class LivenessPerformanceTest {

    private static final String TAG = "LivenessPerf";
    private static final String REPLAY_PACK = "liveness-replay/mockmds-still";

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final AndroidImageDecoder decoder = new AndroidImageDecoder();

    @Test
    public void perFrameLatency() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        ExecutionProvider requested = ExecutionProvider.parse(args.getString("provider", "CPU"));
        int threads = Integer.parseInt(args.getString("threads", "2"));
        int frames = Integer.parseInt(args.getString("frames", "100"));
        int warmup = Integer.parseInt(args.getString("warmup", "20"));
        assumeTrue("benchmark disabled (frames=0)", frames > 0);

        List<byte[]> packs = ReplayFrameSource.readAssets(context.getAssets(), REPLAY_PACK);
        FaceFrame frame = FaceFrame.builder().data(packs.get(0)).format(PixelFormat.JPEG).size(0, 0)
                .monotonicNanos(0).sequence(1).sourceId("perf").build();

        OnnxRuntimeOptions options = OnnxRuntimeOptions.builder().intraOpThreads(threads)
                .executionProvider(requested).build();
        ExecutionProvider effective = options.effectiveExecutionProvider();

        ModelRegistry registry = ModelRegistry.open(new AssetModelSource(context.getAssets()));
        long loadStart = System.nanoTime();
        long peakPssKb;
        long[] total = new long[frames];
        long firstFrame;
        int width;
        int height;
        try (OnnxModelSet models = OnnxModelSet.load(registry, options)) {
            long loadMs = (System.nanoTime() - loadStart) / 1_000_000L;
            peakPssKb = pssKb();

            long t0 = System.nanoTime();
            ImageBuffer first = decoder.decode(frame);
            width = first.width();
            height = first.height();
            runOnce(models, first);
            firstFrame = System.nanoTime() - t0;
            first.wipe();
            peakPssKb = Math.max(peakPssKb, pssKb());

            for (int i = -warmup; i < frames; i++) {
                long s = System.nanoTime();
                ImageBuffer image = decoder.decode(frame);
                try {
                    runOnce(models, image);
                } finally {
                    image.wipe();
                }
                long e = System.nanoTime();
                if (i >= 0) {
                    total[i] = e - s;
                }
                if (i % 10 == 0) {
                    peakPssKb = Math.max(peakPssKb, pssKb());
                }
            }
            peakPssKb = Math.max(peakPssKb, pssKb());

            long[] sorted = total.clone();
            Arrays.sort(sorted);
            double p50 = percentile(sorted, 50) / 1e6;
            double p95 = percentile(sorted, 95) / 1e6;
            double mean = Arrays.stream(total).average().orElse(0) / 1e6;
            double max = sorted[sorted.length - 1] / 1e6;

            Bundle out = new Bundle();
            out.putString("perf_requested_provider", requested.name());
            out.putString("perf_effective_provider", effective.name());
            out.putString("perf_threads", Integer.toString(threads));
            out.putString("perf_frames", Integer.toString(frames));
            out.putString("perf_frame_size", width + "x" + height);
            out.putString("perf_model_load_ms", Long.toString(loadMs));
            out.putString("perf_first_frame_ms", fmt(firstFrame / 1e6));
            out.putString("perf_p50_ms", fmt(p50));
            out.putString("perf_p95_ms", fmt(p95));
            out.putString("perf_mean_ms", fmt(mean));
            out.putString("perf_max_ms", fmt(max));
            out.putString("perf_peak_pss_kb", Long.toString(peakPssKb));
            InstrumentationRegistry.getInstrumentation().sendStatus(0, out);
            Log.i(TAG, String.format(Locale.ROOT,
                    "provider=%s effective=%s threads=%d frames=%d size=%dx%d load_ms=%d first_ms=%s p50_ms=%s "
                            + "p95_ms=%s mean_ms=%s max_ms=%s peak_pss_kb=%d",
                    requested, effective, threads, frames, width, height, loadMs, fmt(firstFrame / 1e6), fmt(p50),
                    fmt(p95), fmt(mean), fmt(max), peakPssKb));
            assertTrue("no frame was timed", total[0] > 0);
        }
    }

    private static void runOnce(OnnxModelSet models, ImageBuffer image) throws Exception {
        FaceObservation observation = models.faceAnalyzer().analyze(image);
        assertEquals("one face in the replay frame", 1, observation.faceCount());
        FaceBox box = observation.primary().get().box();
        models.passivePad().score(image, box);
    }

    private static long pssKb() {
        Debug.MemoryInfo info = new Debug.MemoryInfo();
        Debug.getMemoryInfo(info);
        return info.getTotalPss();
    }

    private static double percentile(long[] sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
