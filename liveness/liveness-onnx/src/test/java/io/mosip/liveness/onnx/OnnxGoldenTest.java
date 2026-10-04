/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx;

import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.vision.AnalyzerFeature;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceEmbedding;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.FacePoint;
import io.mosip.liveness.core.vision.FaceQuality;
import io.mosip.liveness.core.vision.HeadPose;
import io.mosip.liveness.core.vision.Landmark5;
import io.mosip.liveness.core.vision.PadScore;
import io.mosip.liveness.onnx.embed.SFaceEmbedder;
import io.mosip.liveness.onnx.image.ChannelOrder;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.image.TensorFormat;
import io.mosip.liveness.onnx.mesh.FaceMeasures;
import io.mosip.liveness.onnx.model.ModelIds;
import io.mosip.liveness.onnx.pad.MiniFasModel;
import io.mosip.liveness.onnx.pad.MiniFasPassivePad;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden tests on the MockMDS face images with the real models. Expected values come from an independent Python
 * pipeline (OpenCV 5.0 {@code FaceDetectorYN}-compatible decoding, OpenCV resize and warp, ONNX Runtime 1.30,
 * upstream MiniFASNet cropping); JPEG decoders differ slightly between OpenCV and ImageIO, hence the tolerances.
 */
class OnnxGoldenTest {

    private static final String STREAM = "mockmds-stream-0.jpeg";
    private static final String REGISTRATION = "mockmds-registration-face.png";
    private static final String EXCEPTION = "mockmds-exception-photo.jpg";

    /** Python reference: YuNet at full resolution on the MockMDS stream frame. */
    private static final double[] STREAM_BOX = {192.0757, 98.9576, 95.2690, 119.9734};
    private static final double STREAM_SCORE = 0.9184;
    private static final double[][] STREAM_LANDMARKS = {
        {216.4242, 143.3062}, {259.4496, 143.9021}, {235.8842, 166.3286}, {219.0453, 184.2816}, {256.5347, 185.0282},
    };

    @Test
    void yunetMatchesReferenceAtFullResolution() throws Exception {
        FaceObservation obs = TestSupport.nativeResolutionModels().faceAnalyzer().analyze(TestSupport.face(STREAM));
        assertEquals(1, obs.faceCount());
        FaceBox box = obs.primary().get().box();
        assertEquals(STREAM_BOX[0], box.x(), 1.0);
        assertEquals(STREAM_BOX[1], box.y(), 1.0);
        assertEquals(STREAM_BOX[2], box.width(), 1.0);
        assertEquals(STREAM_BOX[3], box.height(), 1.0);
        assertEquals(STREAM_SCORE, box.confidence(), 0.02);
        for (Landmark5 l : Landmark5.values()) {
            FacePoint p = box.landmark(l).get();
            assertEquals(STREAM_LANDMARKS[l.ordinal()][0], p.x(), 1.0, l.name());
            assertEquals(STREAM_LANDMARKS[l.ordinal()][1], p.y(), 1.0, l.name());
        }
        // Subject's right eye appears on the image left; the mouth is below the nose, the nose below the eyes.
        assertTrue(box.landmark(Landmark5.RIGHT_EYE).get().x() < box.landmark(Landmark5.LEFT_EYE).get().x());
        assertTrue(box.landmark(Landmark5.MOUTH_RIGHT).get().x() < box.landmark(Landmark5.MOUTH_LEFT).get().x());
        assertTrue(box.landmark(Landmark5.NOSE_TIP).get().y() > box.landmark(Landmark5.RIGHT_EYE).get().y());
        assertTrue(box.landmark(Landmark5.MOUTH_LEFT).get().y() > box.landmark(Landmark5.NOSE_TIP).get().y());
    }

    @Test
    void yunetDefaultInputSizeFindsTheSameFace() throws Exception {
        FaceBox box = TestSupport.defaultModels().faceAnalyzer().analyze(TestSupport.face(STREAM)).primary().get().box();
        // Python reference at a 320-pixel detector input: 191.29, 102.30, 95.76, 117.40.
        assertEquals(191.29, box.x(), 1.5);
        assertEquals(102.30, box.y(), 1.5);
        assertEquals(95.76, box.width(), 1.5);
        assertEquals(117.40, box.height(), 1.5);
    }

    @Test
    void meshLandmarksAndMeasuresAreSane() throws Exception {
        FaceObservation obs = TestSupport.nativeResolutionModels().faceAnalyzer().analyze(TestSupport.face(STREAM));
        DetectedFace face = obs.primary().get();
        assertTrue(face.mesh().isPresent());
        assertEquals(478, face.mesh().get().pointCount());
        // Iris centres (Python reference 218.60, 141.58 and 261.88, 142.76) sit next to the YuNet eye centres.
        assertEquals(218.60, face.mesh().get().x(468), 1.5);
        assertEquals(141.58, face.mesh().get().y(468), 1.5);
        assertEquals(261.88, face.mesh().get().x(473), 1.5);
        assertEquals(142.76, face.mesh().get().y(473), 1.5);
        assertEquals(0.2877, face.rightEyeAspectRatio().getAsDouble(), 0.02);
        assertEquals(0.2914, face.leftEyeAspectRatio().getAsDouble(), 0.02);
        assertEquals(0.8808, face.mouthWidthRatio().getAsDouble(), 0.03);
        assertTrue(Math.abs(face.gazeHorizontal().getAsDouble()) < 0.5);
        HeadPose pose = face.pose().get();
        System.out.println("[golden] mesh: " + pose + ", earR=" + face.rightEyeAspectRatio().getAsDouble() + ", earL="
                + face.leftEyeAspectRatio().getAsDouble() + ", mouth=" + face.mouthWidthRatio().getAsDouble() + ", gaze=("
                + face.gazeHorizontal().getAsDouble() + ", " + face.gazeVertical().getAsDouble() + "), " + face.quality().get());
        // Near-frontal face; the eye line drops 0.8 degrees towards the subject's left, so roll is slightly positive.
        assertTrue(pose.isWithin(8), pose.toString());
        assertTrue(pose.rollDeg() > 0 && pose.rollDeg() < 3, pose.toString());
        FaceQuality q = face.quality().get();
        assertEquals(43.29, q.interEyeDistancePx(), 1.0);
        assertTrue(q.brightness() > 40 && q.brightness() < 200, q.toString());
        assertTrue(q.sharpness() > 1, q.toString());
    }

    @Test
    void miniFasnetMatchesReferenceAndPinsPreprocessing() throws Exception {
        MiniFasPassivePad pad = TestSupport.nativeResolutionModels().passivePad();
        Rgb8Image image = Rgb8Image.of(TestSupport.face(STREAM));
        FaceBox box = FaceBox.of(STREAM_BOX[0], STREAM_BOX[1], STREAM_BOX[2], STREAM_BOX[3], STREAM_SCORE);
        MiniFasModel v2 = model(pad, ModelIds.MINIFASNET_V2);
        MiniFasModel v1se = model(pad, ModelIds.MINIFASNET_V1SE);
        assertEquals(2.7, v2.cropScale());
        assertEquals(4.0, v1se.cropScale());
        assertTrue(Arrays.equals(new int[] {111, 0, 367, 321}, MiniFasModel.cropRegion(490, 360, box, 2.7)));
        assertTrue(Arrays.equals(new int[] {96, 0, 382, 359}, MiniFasModel.cropRegion(490, 360, box, 4.0)));

        float[] p2 = v2.probabilities(image, box);
        float[] p1 = v1se.probabilities(image, box);
        assertArrayNear(new double[] {0.0013, 0.8889, 0.1098}, p2, 0.03);
        assertArrayNear(new double[] {0.0186, 0.2196, 0.7618}, p1, 0.04);
        double[] mean = pad.meanProbabilities(image, box);
        System.out.println("[golden] MiniFASNetV2 " + Arrays.toString(p2) + ", V1SE " + Arrays.toString(p1)
                + ", ensemble " + Arrays.toString(mean));
        assertEquals(MiniFasModel.LIVE_CLASS, argmax(mean));
        assertEquals(0.554, mean[MiniFasModel.LIVE_CLASS], 0.03);

        // The /255 variant (as a published model card suggests) calls the same live face an attack.
        TensorFormat divided = TensorFormat.of(ChannelOrder.BGR, 0f, 1f / 255f);
        float[] wrong = v2.probabilities(image, box, divided);
        System.out.println("[golden] MiniFASNetV2 with /255 input " + Arrays.toString(wrong));
        assertTrue(wrong[2] > 0.9 && wrong[MiniFasModel.LIVE_CLASS] < 0.05, Arrays.toString(wrong));
        // RGB order instead of BGR also changes the output.
        float[] rgb = v2.probabilities(image, box, TensorFormat.RGB_RAW);
        assertTrue(Math.abs(rgb[1] - p2[1]) > 1e-3, "channel order must matter");
    }

    @Test
    void passivePadScoreReportsEachModel() throws Exception {
        ImageBuffer image = TestSupport.face(STREAM);
        OnnxModelSet models = TestSupport.nativeResolutionModels();
        FaceBox box = models.faceAnalyzer().analyze(image).primary().get().box();
        PadScore score = models.passivePad().score(image, box);
        assertEquals(2, score.modelProbabilities().size());
        assertTrue(score.modelProbabilities().containsKey(ModelIds.MINIFASNET_V2));
        assertTrue(score.modelProbabilities().containsKey(ModelIds.MINIFASNET_V1SE));
        double mean = (score.modelProbabilities().get(ModelIds.MINIFASNET_V2)
                + score.modelProbabilities().get(ModelIds.MINIFASNET_V1SE)) / 2;
        assertEquals(mean, score.liveProbability(), 1e-9);
        assertEquals(0.55, score.liveProbability(), 0.06);
    }

    @Test
    void sfaceSeparatesSameAndDifferentPeople() throws Exception {
        OnnxModelSet models = TestSupport.nativeResolutionModels();
        SFaceEmbedder sface = models.faceEmbedder();
        FaceEmbedding stream = embed(models, STREAM);
        FaceEmbedding streamAgain = embed(models, STREAM);
        FaceEmbedding registration = embed(models, REGISTRATION);
        FaceEmbedding other = embed(models, EXCEPTION);
        assertEquals(SFaceEmbedder.DIMENSION, stream.dimension());
        assertEquals("sface@2021dec-fp16w", stream.modelId());
        assertEquals(1.0, stream.cosineSimilarity(streamAgain), 1e-6);
        double same = stream.cosineSimilarity(registration);
        double different = stream.cosineSimilarity(other);
        System.out.println("[golden] SFace cosine: same image " + stream.cosineSimilarity(streamAgain) + ", same person "
                + same + ", different person " + different);
        // Python reference: 0.585 (same person, different photo) and 0.045 (different person).
        assertEquals(0.585, same, 0.03);
        assertEquals(0.045, different, 0.05);
        assertTrue(same >= SFaceEmbedder.OPENCV_SAME_PERSON_COSINE);
        assertTrue(different < SFaceEmbedder.OPENCV_SAME_PERSON_COSINE);
        assertTrue(registration.cosineSimilarity(other) < SFaceEmbedder.OPENCV_SAME_PERSON_COSINE);
        assertEquals(same, SFaceEmbedder.cosine(stream.vector(), registration.vector()), 1e-6);
        assertFalse(sface.toString().contains("0."), "no vector values in toString");
    }

    @Test
    void otherFacesAreFoundAndRegistrationPhotoIsAnalysed() throws Exception {
        OnnxFaceAnalyzer analyzer = TestSupport.defaultModels().faceAnalyzer();
        FaceObservation reg = analyzer.analyze(TestSupport.face(REGISTRATION));
        assertEquals(1, reg.faceCount());
        assertEquals(55.72, reg.primary().get().box().x(), 1.5);
        FaceObservation exc = analyzer.analyze(TestSupport.face(EXCEPTION));
        assertEquals(1, exc.faceCount());
        assertTrue(exc.primary().get().leftEyeAspectRatio().isPresent());
    }

    @Test
    void twoPeopleInOneImageGiveTwoFaces() throws Exception {
        ImageBuffer a = TestSupport.face(STREAM);
        ImageBuffer b = TestSupport.face(EXCEPTION);
        ImageBuffer both = sideBySide(a, b);
        OnnxFaceAnalyzer analyzer = TestSupport.defaultModels().faceAnalyzer();
        FaceObservation obs = analyzer.analyze(both);
        assertEquals(2, obs.faceCount());
        assertTrue(obs.faces().get(0).mesh().isPresent());
        assertFalse(obs.faces().get(1).mesh().isPresent());
        // A hint on the stream person's face keeps that person first even though the other face is larger.
        FaceBox streamFace = obs.faces().get(0).box().centerX() < a.width() ? obs.faces().get(0).box()
                : obs.faces().get(1).box();
        FaceObservation tracked = analyzer.analyze(both, streamFace);
        assertTrue(tracked.primary().get().box().centerX() < a.width());
    }

    @Test
    void emptyImageHasNoFace() throws Exception {
        byte[] grey = new byte[320 * 240 * 3];
        Arrays.fill(grey, (byte) 128);
        FaceObservation obs = TestSupport.defaultModels().faceAnalyzer()
                .analyze(ImageBuffer.ofBytes(320, 240, PixelFormat.RGB888, grey));
        assertEquals(0, obs.faceCount());
        assertFalse(obs.primary().isPresent());
    }

    @Test
    void argbInputGivesTheSameDetectionAsBgr() throws Exception {
        ImageBuffer bgr = TestSupport.face(STREAM);
        byte[] d = bgr.bytes();
        int[] argb = new int[bgr.width() * bgr.height()];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = 0xff000000 | (d[3 * i + 2] & 0xff) << 16 | (d[3 * i + 1] & 0xff) << 8 | (d[3 * i] & 0xff);
        }
        OnnxFaceAnalyzer analyzer = TestSupport.defaultModels().faceAnalyzer();
        FaceBox fromBgr = analyzer.analyze(bgr).primary().get().box();
        FaceBox fromArgb = analyzer.analyze(ImageBuffer.ofArgbInts(bgr.width(), bgr.height(), argb)).primary().get().box();
        assertEquals(fromBgr.x(), fromArgb.x(), 1e-3);
        assertEquals(fromBgr.width(), fromArgb.width(), 1e-3);
    }

    @Test
    void detectorOnlyFallbackGivesFivePointPose() throws Exception {
        try (OnnxModelSet models = OnnxModelSet.builder(TestSupport.registry()).withMesh(false).withPassivePad(false)
                .withEmbedder(false).load()) {
            OnnxFaceAnalyzer analyzer = models.faceAnalyzer();
            assertFalse(analyzer.features().contains(AnalyzerFeature.EYE_OPENNESS));
            assertTrue(analyzer.features().contains(AnalyzerFeature.HEAD_POSE));
            DetectedFace face = analyzer.analyze(TestSupport.face(STREAM)).primary().get();
            assertFalse(face.mesh().isPresent());
            assertFalse(face.leftEyeAspectRatio().isPresent());
            assertTrue(face.pose().get().isWithin(15), face.pose().get().toString());
            // YuNet eye points at the 320-pixel input: about 46 px apart (43 px at full resolution).
            assertEquals(44.5, face.quality().get().interEyeDistancePx(), 3.5);
            assertEquals(1, models.models().size());
        }
    }

    @Test
    void detectorOnlyAnalyzerSharesTheDetector() throws Exception {
        OnnxModelSet models = TestSupport.defaultModels();
        ImageBuffer image = TestSupport.face(STREAM);
        DetectedFace full = models.faceAnalyzer().analyze(image).primary().get();
        DetectedFace light = models.detectorOnlyAnalyzer().analyze(image).primary().get();
        assertEquals(full.box(), light.box());
        assertFalse(light.mesh().isPresent());
        assertFalse(models.detectorOnlyAnalyzer().features().contains(AnalyzerFeature.MESH));
        assertTrue(light.pose().isPresent() && light.quality().isPresent());
    }

    @Test
    void meshMeasuresMatchFaceMeasuresHelpers() throws Exception {
        DetectedFace face = TestSupport.defaultModels().faceAnalyzer().analyze(TestSupport.face(STREAM)).primary().get();
        assertEquals(FaceMeasures.leftEyeAspectRatio(face.mesh().get()), face.leftEyeAspectRatio().getAsDouble(), 1e-12);
        assertEquals(FaceMeasures.rightEyeAspectRatio(face.mesh().get()), face.rightEyeAspectRatio().getAsDouble(),
                1e-12);
    }

    private static FaceEmbedding embed(OnnxModelSet models, String file) throws Exception {
        ImageBuffer image = TestSupport.face(file);
        FaceBox box = models.faceAnalyzer().analyze(image).primary().get().box();
        return models.faceEmbedder().embed(image, box);
    }

    private static MiniFasModel model(MiniFasPassivePad pad, String id) {
        List<MiniFasModel> models = pad.models();
        for (MiniFasModel m : models) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        throw new AssertionError("no model " + id);
    }

    private static int argmax(double[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] > v[best]) {
                best = i;
            }
        }
        return best;
    }

    private static void assertArrayNear(double[] expected, float[] actual, double tolerance) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], tolerance, "index " + i + " of " + Arrays.toString(actual));
        }
    }

    private static ImageBuffer sideBySide(ImageBuffer a, ImageBuffer b) {
        int w = a.width() + b.width();
        int h = Math.max(a.height(), b.height());
        byte[] out = new byte[w * h * 3];
        copy(a, out, w, 0);
        copy(b, out, w, a.width());
        return ImageBuffer.ofBytes(w, h, PixelFormat.BGR888, out);
    }

    private static void copy(ImageBuffer src, byte[] out, int outWidth, int xOffset) {
        for (int y = 0; y < src.height(); y++) {
            System.arraycopy(src.bytes(), y * src.width() * 3, out, (y * outWidth + xOffset) * 3, src.width() * 3);
        }
    }
}
