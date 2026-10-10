/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.onnx.detect.YuNetFaceDetector;
import io.mosip.liveness.onnx.image.ChannelOrder;
import io.mosip.liveness.onnx.image.ImageOps;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.mesh.FaceMeshLandmarker;
import io.mosip.liveness.onnx.model.ModelIds;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;
import io.mosip.liveness.onnx.runtime.OrtModel;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Per-frame latency at 640x480 on the machine running the build (T-PERF). Prints p50, p95 and maximum per
 * stage and asserts nothing about time, because shared build machines vary widely (other builds running at the
 * same time multiplied p95 by ten in one run). The target (T-PERF-01 in docs/traceability.md) is p95 of at most 20 ms
 * per frame including JPEG decoding on a desktop. {@code -Dliveness.benchmark.frames=0} skips the benchmark.
 *
 * <p>Frames: the MockMDS stream image resized to 640x480 and JPEG-encoded once; each iteration decodes it with
 * ImageIO (as the desktop client does), runs the face analyser (YuNet at 320 px, Face Mesh, quality) and the
 * MiniFASNet ensemble. SFace, which runs only a few times per session, is timed separately.
 */
class PerformanceBenchmarkTest {

    private static final int WARM_UP = 60;

    @Test
    void perFrameLatencyAt640x480() throws Exception {
        int frames = Integer.getInteger("liveness.benchmark.frames", 100);
        assumeTrue(frames > 0, "benchmark disabled");
        byte[] jpeg = frame640x480Jpeg();
        for (int threads : new int[] {2, 1}) {
            try (OnnxModelSet models = OnnxModelSet.builder(TestSupport.registry())
                    .runtimeOptions(OnnxRuntimeOptions.builder().intraOpThreads(threads).build()).load()) {
                run(models, jpeg, frames, threads);
            }
        }
    }

    private static void run(OnnxModelSet models, byte[] jpeg, int frames, int threads) throws EngineException {
        long[] decode = new long[frames];
        long[] analyse = new long[frames];
        long[] pad = new long[frames];
        long[] total = new long[frames];
        long[] embed = new long[frames];
        for (int i = -WARM_UP; i < frames; i++) {
            long t0 = System.nanoTime();
            ImageBuffer image = TestSupport.decode(jpeg);
            long t1 = System.nanoTime();
            FaceObservation obs = models.faceAnalyzer().analyze(image);
            long t2 = System.nanoTime();
            FaceBox box = obs.primary().get().box();
            models.passivePad().score(image, box);
            long t3 = System.nanoTime();
            models.faceEmbedder().embed(image, box);
            long t4 = System.nanoTime();
            if (i >= 0) {
                decode[i] = t1 - t0;
                analyse[i] = t2 - t1;
                pad[i] = t3 - t2;
                total[i] = t3 - t0;
                embed[i] = t4 - t3;
            }
            assertEquals(1, obs.faceCount());
        }
        String cpu = Runtime.getRuntime().availableProcessors() + " logical CPUs";
        System.out.println(String.format(Locale.ROOT, "[perf] 640x480, %d frames, intra-op threads %d, %s, Java %s",
                frames, threads, cpu, System.getProperty("java.version")));
        print("JPEG decode (ImageIO)", decode);
        print("analyse (YuNet 320 + Face Mesh + quality)", analyse);
        print("passive PAD (MiniFASNet V2 + V1SE)", pad);
        print("per frame: decode + analyse + PAD", total);
        print("SFace embedding (align + infer)", embed);
    }

    @Test
    void stageBreakdownAt640x480() throws Exception {
        ModelRegistry registry = TestSupport.registry();
        OnnxRuntimeOptions options = OnnxRuntimeOptions.builder().intraOpThreads(1).build();
        Rgb8Image image = Rgb8Image.of(TestSupport.decode(frame640x480Jpeg()));
        int frames = Integer.getInteger("liveness.benchmark.frames", 100);
        assumeTrue(frames > 0, "benchmark disabled");
        try (OrtModel yunet = OrtModel.create(ModelIds.YUNET, registry.load(ModelIds.YUNET), options);
             OrtModel mesh = OrtModel.create(ModelIds.FACE_MESH, registry.load(ModelIds.FACE_MESH), options)) {
            YuNetFaceDetector at320 = new YuNetFaceDetector(yunet, YuNetFaceDetector.Options.defaults());
            YuNetFaceDetector full = new YuNetFaceDetector(yunet, YuNetFaceDetector.Options.of(0.7, 0.3, 0, 10));
            FaceMeshLandmarker landmarker = new FaceMeshLandmarker(mesh);
            FaceBox box = at320.detect(image).get(0);
            long[] d320 = new long[frames];
            long[] dFull = new long[frames];
            long[] m = new long[frames];
            for (int i = -WARM_UP; i < frames; i++) {
                long t0 = System.nanoTime();
                at320.detect(image);
                long t1 = System.nanoTime();
                full.detect(image);
                long t2 = System.nanoTime();
                landmarker.detect(image, box);
                long t3 = System.nanoTime();
                if (i >= 0) {
                    d320[i] = t1 - t0;
                    dFull[i] = t2 - t1;
                    m[i] = t3 - t2;
                }
            }
            System.out.println("[perf] stage breakdown, 1 intra-op thread, 640x480");
            print("YuNet, input 320x256", d320);
            print("YuNet, input 640x480", dFull);
            print("Face Mesh (warp 256x256 + infer)", m);
        }
    }

    private static byte[] frame640x480Jpeg() throws Exception {
        Rgb8Image resized = ImageOps.resize(Rgb8Image.of(TestSupport.face("mockmds-stream-0.jpeg")), 640, 480);
        BufferedImage img = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);
        byte[] target = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(ImageOps.withOrder(resized, ChannelOrder.BGR).toImageBuffer().bytes(), 0, target, 0,
                target.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static void print(String what, long[] nanos) {
        System.out.println(String.format(Locale.ROOT, "[perf]   %-45s p50 %6.2f ms   p95 %6.2f ms   max %6.2f ms", what,
                percentile(nanos, 50), percentile(nanos, 95), percentile(nanos, 100)));
    }

    private static double percentile(long[] nanos, int p) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1e6;
    }
}
