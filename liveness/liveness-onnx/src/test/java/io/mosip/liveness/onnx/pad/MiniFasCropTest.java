package io.mosip.liveness.onnx.pad;

import io.mosip.liveness.core.vision.FaceBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the upstream MiniFASNet crop ({@code generate_patches.py::_get_new_box}); expected values computed with the
 * upstream Python function.
 */
class MiniFasCropTest {

    @Test
    void centredCropWhenItFits() {
        // Box 100x100 at (270, 190) in 640x480, scale 2.7: 270x270 centred on (320, 240).
        assertArrayEquals(new int[] {185, 105, 455, 375}, MiniFasModel.cropRegion(640, 480, FaceBox.of(270, 190, 100, 100, 1), 2.7));
    }

    @Test
    void scaleIsClampedToTheImage() {
        // Box height 120 in a 360-row image: scale clamped to 359 / 119 (box truncated to 119 rows) = 3.017.
        int[] r = MiniFasModel.cropRegion(490, 360, FaceBox.of(192.08, 98.96, 95.27, 119.97, 1), 4.0);
        assertArrayEquals(new int[] {96, 0, 382, 359}, r);
    }

    @Test
    void cropIsShiftedInsideNotCut() {
        // Face near the left edge: the 270-pixel crop moves right instead of shrinking.
        int[] r = MiniFasModel.cropRegion(640, 480, FaceBox.of(10, 190, 100, 100, 1), 2.7);
        assertEquals(0, r[0]);
        assertEquals(270, r[2]);
        // Face near the bottom-right corner.
        int[] c = MiniFasModel.cropRegion(640, 480, FaceBox.of(600, 450, 100, 100, 1), 2.7);
        assertEquals(639, c[2]);
        assertEquals(479, c[3]);
        assertEquals(369, c[0]);
        assertEquals(209, c[1]);
    }

    @Test
    void softmaxIsNormalisedAndStable() {
        float[] p = MiniFasModel.softmax(new float[] {1000f, 1001f, 999f});
        assertEquals(1.0, p[0] + p[1] + p[2], 1e-6);
        assertTrue(p[1] > p[0] && p[0] > p[2]);
    }
}
