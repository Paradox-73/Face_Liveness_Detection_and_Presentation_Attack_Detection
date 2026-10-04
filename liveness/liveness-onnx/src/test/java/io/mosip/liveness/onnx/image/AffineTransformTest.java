/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.image;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AffineTransformTest {

    @Test
    void similarityEstimateRecoversAKnownTransform() {
        AffineTransform known = AffineTransform.rotateScaleAbout(1.7, Math.toRadians(23), 10, 20, 56, 60);
        double[] src = {10, 20, 50, 22, 31, 45, 12, 70, 48, 68};
        double[] dst = new double[src.length];
        for (int i = 0; i < src.length; i += 2) {
            dst[i] = known.mapX(src[i], src[i + 1]);
            dst[i + 1] = known.mapY(src[i], src[i + 1]);
        }
        double[] a = AffineTransform.estimateSimilarity(src, dst).coefficients();
        double[] b = known.coefficients();
        for (int i = 0; i < 6; i++) {
            assertEquals(b[i], a[i], 1e-9, "coefficient " + i);
        }
        assertEquals(1.7, known.scale(), 1e-12);
    }

    @Test
    void similarityEstimateMatchesOpenCvOnTheMockMdsFace() {
        // YuNet landmarks of the MockMDS frame and the matrix OpenCV FaceRecognizerSF.alignCrop computes for them
        // (Umeyama, scikit-image style), from the Python reference.
        double[] src = {216.4242, 143.3062, 259.4496, 143.9021, 235.8842, 166.3286, 219.0453, 184.2816, 256.5347, 185.0282};
        double[] template = {38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041};
        double[] expected = {0.895736, 0.013115, -158.840367, -0.013115, 0.895736, -72.395524};
        double[] actual = AffineTransform.estimateSimilarity(src, template).coefficients();
        for (int i = 0; i < 6; i++) {
            assertEquals(expected[i], actual[i], 1e-4, "coefficient " + i);
        }
    }

    @Test
    void inverseUndoesTheTransform() {
        AffineTransform t = AffineTransform.of(1.2, -0.3, 5, 0.4, 0.9, -7);
        AffineTransform inv = t.invert();
        double x = t.mapX(3, 4);
        double y = t.mapY(3, 4);
        assertEquals(3, inv.mapX(x, y), 1e-12);
        assertEquals(4, inv.mapY(x, y), 1e-12);
        assertThrows(IllegalStateException.class, () -> AffineTransform.of(1, 2, 0, 2, 4, 0).invert());
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AffineTransform.of(Double.NaN, 0, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> AffineTransform.estimateSimilarity(new double[] {1, 1},
                new double[] {1, 1}));
        assertThrows(IllegalArgumentException.class, () -> AffineTransform.estimateSimilarity(new double[] {1, 1, 1, 1},
                new double[] {0, 0, 1, 1}));
    }
}
