/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.pad;

import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.PadScore;
import io.mosip.liveness.onnx.OnnxModelSet;
import io.mosip.liveness.onnx.TestSupport;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.image.TensorFormat;
import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped passive PAD with the real MiniFASNet models on the MockMDS stock photo: a normally lit frame scores the
 * same with and without the two additions, and a darkened copy (45% brightness, like the synthetic dim packs) is
 * brightened before scoring.
 */
class PassivePadExposureTest {

    private static final String STREAM = "mockmds-stream-0.jpeg";

    @Test
    void normallyLitFrameIsScoredAsUpstream() throws Exception {
        OnnxModelSet models = TestSupport.nativeResolutionModels();
        ImageBuffer image = TestSupport.face(STREAM);
        FaceBox box = models.faceAnalyzer().analyze(image).primary().get().box();
        Rgb8Image rgb = Rgb8Image.of(image);
        assertEquals(1.0, SceneExposure.gain(rgb), 0);
        MiniFasPassivePad shipped = models.passivePad();
        MiniFasPassivePad upstream = new MiniFasPassivePad(shipped.models(), false, false);
        assertTrue(shipped.exposureCorrection() && shipped.screenCheck());
        assertEquals(upstream.score(rgb, box).liveProbability(), shipped.score(rgb, box).liveProbability(), 1e-9);
    }

    @Test
    void darkFrameIsBrightenedBeforeScoring() throws Exception {
        OnnxModelSet models = TestSupport.nativeResolutionModels();
        ImageBuffer image = TestSupport.face(STREAM);
        FaceBox box = models.faceAnalyzer().analyze(image).primary().get().box();
        Rgb8Image dark = darken(Rgb8Image.of(image), 0.45);
        double gain = SceneExposure.gain(dark);
        assertTrue(gain > 1.5, "gain " + gain);
        MiniFasPassivePad upstream = new MiniFasPassivePad(models.passivePad().models(), false, false);
        MiniFasPassivePad corrected = new MiniFasPassivePad(models.passivePad().models(), true, false);
        PadScore before = upstream.score(dark, box);
        PadScore after = corrected.score(dark, box);
        System.out.println("[pad] stock photo at 45%: live " + before.liveProbability() + " without, "
                + after.liveProbability() + " with exposure gain " + gain);
        float[] direct = models.passivePad().models().get(0).probabilities(dark, box,
                TensorFormat.BGR_RAW, gain);
        assertEquals(direct[MiniFasModel.LIVE_CLASS],
                after.modelProbabilities().get(models.passivePad().models().get(0).id()), 1e-6);
        assertTrue(Math.abs(after.liveProbability() - before.liveProbability()) > 1e-4,
                "the gain must change the input of the models");
    }

    @Test
    void closeEndsTheHelperThreadsAndLaterScoresAreRefused() throws Exception {
        OnnxModelSet models = TestSupport.nativeResolutionModels();
        ImageBuffer image = TestSupport.face(STREAM);
        FaceBox box = models.faceAnalyzer().analyze(image).primary().get().box();
        Rgb8Image rgb = Rgb8Image.of(image);
        MiniFasPassivePad pad = new MiniFasPassivePad(models.passivePad().models());
        assertEquals(models.passivePad().score(rgb, box).liveProbability(), pad.score(rgb, box).liveProbability(),
                1e-9);
        pad.close();
        assertTrue(pad.helpers().awaitTermination(5, TimeUnit.SECONDS), "helper threads still running");
        assertThrows(RejectedExecutionException.class, () -> pad.score(rgb, box));
    }

    private static Rgb8Image darken(Rgb8Image src, double factor) {
        byte[] d = src.data().clone();
        for (int i = 0; i < d.length; i++) {
            d[i] = (byte) Math.round((d[i] & 0xFF) * factor);
        }
        return Rgb8Image.wrap(src.width(), src.height(), d, src.order());
    }
}
