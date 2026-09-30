package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.ReasonCode;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.config.LivenessPolicy;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FacePoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-CORE-INT-01..08 (frame integrity), T-CORE-PLN-01..04 (planarity) and the image fingerprints. */
class IntegrityAndPlanarityTest {

    private static final long MS = 1_000_000L;
    private final Random random = new Random(11);
    private long seq;
    private long nanos = 5_000 * MS;

    private FrameIntegrityChecker checker() {
        return new FrameIntegrityChecker(TestPolicies.passiveFirst(Workflow.REGISTRATION), EngineParameters.defaults());
    }

    private FaceFrame gray(byte[] data, long stepMs, long seqStep) {
        nanos += stepMs * MS;
        seq += seqStep;
        return FaceFrame.builder().data(data).format(PixelFormat.GRAY8).size(64, 48).monotonicNanos(nanos).sequence(seq)
                .sourceId("t").build();
    }

    private byte[] noise() {
        byte[] b = new byte[64 * 48];
        random.nextBytes(b);
        return b;
    }

    private static ImageBuffer image(byte[] data) {
        return ImageBuffer.ofBytes(64, 48, PixelFormat.GRAY8, data);
    }

    private ReasonCode feed(FrameIntegrityChecker c, byte[] data) {
        FaceFrame f = gray(data, 50, 1);
        ReasonCode r = c.onArrival(f);
        return r != null ? r : c.onDecoded(f, image(data));
    }

    @Test
    void liveNoiseIsClean() {
        FrameIntegrityChecker c = checker();
        for (int i = 0; i < 200; i++) {
            assertNull(feed(c, noise()));
        }
        assertEquals(20, c.sourceFps(), 0.01);
    }

    @Test
    void sequenceOrTimestampGoingBackwardsIsFlagged() {
        FrameIntegrityChecker c = checker();
        assertNull(c.onArrival(gray(noise(), 50, 1)));
        assertEquals(ReasonCode.TIMESTAMP_REGRESSION, c.onArrival(gray(noise(), 50, 0)));
        FrameIntegrityChecker d = checker();
        assertNull(d.onArrival(gray(noise(), 50, 1)));
        assertEquals(ReasonCode.TIMESTAMP_REGRESSION, d.onArrival(gray(noise(), -10, 1)));
    }

    @Test
    void frameRateBelowMinimumIsFlaggedAfterTheWindow() {
        FrameIntegrityChecker slow = checker();
        ReasonCode r = null;
        int frames = 0;
        while (r == null && frames < 20) {
            r = slow.onArrival(gray(noise(), 400, 1));
            frames++;
        }
        assertEquals(ReasonCode.INVALID_FRAMES, r);
        assertEquals(6, frames, "flagged once 2 s of stream were seen");

        FrameIntegrityChecker threeFps = checker();
        for (int i = 0; i < 30; i++) {
            assertNull(threeFps.onArrival(gray(noise(), 333, 1)), "3 fps is the SBI minimum");
        }
        FrameIntegrityChecker dropped = checker();
        for (int i = 0; i < 30; i++) {
            assertNull(dropped.onArrival(gray(noise(), 400, 12)), "frames dropped before the session still count");
        }
    }

    @Test
    void corruptFramesInARowAreFlagged() {
        FrameIntegrityChecker c = checker();
        for (int i = 0; i < 9; i++) {
            assertNull(c.onCorrupt());
        }
        assertNull(feed(c, noise()), "a good frame resets the run");
        for (int i = 0; i < 9; i++) {
            assertNull(c.onCorrupt());
        }
        assertEquals(ReasonCode.INVALID_FRAMES, c.onCorrupt());
    }

    @Test
    void identicalRawFramesAreFrozen() {
        FrameIntegrityChecker c = checker();
        byte[] still = noise();
        for (int i = 0; i < 5; i++) {
            assertNull(feed(c, still.clone()), "frame " + i);
        }
        assertEquals(ReasonCode.FROZEN, feed(c, still.clone()));
    }

    @Test
    void identicalJpegBytesAreFrozenEvenIfDecodingDiffers() {
        FrameIntegrityChecker c = checker();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        ReasonCode r = null;
        for (int i = 0; i < 6; i++) {
            nanos += 33 * MS;
            FaceFrame f = FaceFrame.builder().data(jpeg.clone()).format(PixelFormat.JPEG).monotonicNanos(nanos)
                    .sequence(++seq).sourceId("t").build();
            assertNull(c.onArrival(f));
            r = c.onDecoded(f, image(noise()));
        }
        assertEquals(ReasonCode.FROZEN, r);
    }

    @Test
    void replayedLoopIsFlagged() {
        FrameIntegrityChecker c = checker();
        List<byte[]> clip = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            clip.add(noise());
        }
        ReasonCode r = null;
        int frame = 0;
        while (r == null && frame < 60) {
            r = feed(c, clip.get(frame % clip.size()).clone());
            frame++;
        }
        assertEquals(ReasonCode.LOOP, r);
        assertEquals(14, frame, "found once 6 frames repeat the run 8 frames earlier");
    }

    @Test
    void stillPersonWithSensorNoiseIsNeitherFrozenNorLooped() {
        FrameIntegrityChecker c = checker();
        for (int i = 0; i < 120; i++) {
            byte[] px = new byte[64 * 48];
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 64; x++) {
                    px[y * 64 + x] = (byte) Math.max(0, Math.min(255, x * 2 + y * 2 + random.nextInt(9) - 4));
                }
            }
            assertNull(feed(c, px), "frame " + i);
        }
    }

    @Test
    void lumaOfEveryPixelFormat() {
        assertEquals(76, FrameFingerprint.luma(ImageBuffer.ofBytes(1, 1, PixelFormat.RGB888, new byte[] {(byte) 255, 0, 0}), 0, 0));
        assertEquals(76, FrameFingerprint.luma(ImageBuffer.ofBytes(1, 1, PixelFormat.BGR888, new byte[] {0, 0, (byte) 255}), 0, 0));
        assertEquals(149, FrameFingerprint.luma(ImageBuffer.ofBytes(1, 1, PixelFormat.RGBA8888,
                new byte[] {0, (byte) 255, 0, (byte) 255}), 0, 0));
        assertEquals(28, FrameFingerprint.luma(ImageBuffer.ofArgbInts(1, 1, new int[] {0xFF0000FF}), 0, 0));
        assertEquals(200, FrameFingerprint.luma(ImageBuffer.ofBytes(1, 1, PixelFormat.GRAY8, new byte[] {(byte) 200}), 0, 0));
        byte[] nv21 = new byte[6];
        nv21[3] = (byte) 90;
        assertEquals(90, FrameFingerprint.luma(ImageBuffer.ofBytes(2, 2, PixelFormat.NV21, nv21), 1, 1));
    }

    @Test
    void fingerprints() {
        assertEquals("ba7816bf", FrameFingerprint.sha256Prefix("abc"));
        byte[] gradient = new byte[64 * 48];
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                gradient[y * 64 + x] = (byte) (255 - x * 4);
            }
        }
        assertEquals(-1L, FrameFingerprint.dHash(image(gradient)), "every cell brighter than its right neighbour");
        int[] t = FrameFingerprint.thumbnail(image(gradient), 4, 2, 2);
        assertEquals(8, t.length);
        assertEquals(0, FrameFingerprint.meanAbsDiff(t, t.clone()), 0);
    }

    // ---- planarity

    private static double residual(TestFaces neutral, TestFaces turned) {
        DetectedFace n = neutral.build();
        DetectedFace t = turned.build();
        return PlanarityChecker.residual(PlanarityChecker.rigidPoints(n), PlanarityChecker.rigidPoints(t), TestFaces.IED_PX);
    }

    @Test
    void realHeadTurnHasParallaxWithFiveLandmarks() {
        double real = residual(TestFaces.neutral(), TestFaces.neutral().yaw(20));
        double photo = residual(TestFaces.neutral().planar(), TestFaces.neutral().yaw(20).planar());
        double min = EngineParameters.defaults().get(EngineParameter.PLANARITY_MIN_RESIDUAL);
        assertTrue(real > 3 * min, "3-D residual " + real);
        assertTrue(photo < min / 2, "planar residual " + photo);
    }

    @Test
    void realHeadTurnHasParallaxWithTheMesh() {
        double real = residual(TestFaces.neutral().withMesh(), TestFaces.neutral().withMesh().yaw(-20));
        double photo = residual(TestFaces.neutral().withMesh().planar(), TestFaces.neutral().withMesh().planar().yaw(-20));
        assertTrue(real > 0.03, "3-D residual " + real);
        assertTrue(photo < 1e-6, "an exact homography fits a flat face: " + photo);
        assertEquals(PlanarityChecker.RIGID_MESH_POINTS.length,
                PlanarityChecker.rigidPoints(TestFaces.neutral().withMesh().build()).size());
    }

    @Test
    void residualIsUndefinedForBadInput() {
        List<FacePoint> five = PlanarityChecker.rigidPoints(TestFaces.neutral().build());
        assertTrue(Double.isNaN(PlanarityChecker.residual(five, five.subList(0, 4), 80)));
        assertTrue(Double.isNaN(PlanarityChecker.residual(five.subList(0, 4), five.subList(0, 4), 80)));
        assertTrue(Double.isNaN(PlanarityChecker.residual(five, five, 0)));
        assertTrue(Double.isNaN(PlanarityChecker.residual(null, five, 80)));
        List<FacePoint> line = Arrays.asList(FacePoint.of(0, 0), FacePoint.of(1, 1), FacePoint.of(2, 2),
                FacePoint.of(3, 3), FacePoint.of(4, 4));
        assertTrue(Double.isNaN(PlanarityChecker.residual(line, line, 80)), "collinear points are degenerate");
        assertTrue(PlanarityChecker.rigidPoints(DetectedFace.builder(FaceBox.of(0, 0, 9, 9, 1)).build()).isEmpty());
        assertNotNull(PlanarityChecker.fitAffine(five, five));
    }

    @Test
    void solverDetectsSingularSystems() {
        assertNull(LinearSolver.solve(new double[][] {{1, 2}, {2, 4}}, new double[] {1, 2}));
        double[] x = LinearSolver.solve(new double[][] {{0, 1}, {1, 0}}, new double[] {3, 4});
        assertEquals(4, x[0], 1e-12);
        assertEquals(3, x[1], 1e-12);
    }
}
