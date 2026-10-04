/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch;

import io.mosip.liveness.core.vision.FaceEmbedding;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The template format written into the BIR BDB by {@code extractTemplate}. */
class FaceTemplateTest {

    private static final String MODEL = "sface@2021dec-fp16w";

    @Test
    void roundTripKeepsModelAndVector() {
        FaceEmbedding e = FaceEmbedding.of(MODEL, unitVector(128, 7));
        byte[] template = FaceTemplate.encode(e);
        assertEquals(539, template.length, "4 magic + 1 version + 1 length + 19 id + 2 dimension + 512 floats");
        assertTrue(FaceTemplate.isTemplate(template));
        FaceEmbedding back = FaceTemplate.decode(template);
        assertEquals(MODEL, back.modelId());
        assertArrayEquals(e.vector(), back.vector());
        assertEquals(1.0, back.cosineSimilarity(e), 1e-6);
    }

    @Test
    void layoutIsTheDocumentedOne() {
        byte[] t = FaceTemplate.encode(FaceEmbedding.of("m", new float[] {1f, 0f}));
        assertArrayEquals(new byte[] {'S', 'F', 'T', 'E', 1, 1, 'm', 0, 2, 0x3f, (byte) 0x80, 0, 0, 0, 0, 0, 0},
                t);
    }

    @Test
    void imagesAndRecordsAreNotTemplates() {
        assertFalse(FaceTemplate.isTemplate(null));
        assertFalse(FaceTemplate.isTemplate(new byte[] {'S', 'F'}));
        assertFalse(FaceTemplate.isTemplate(new byte[] {'F', 'A', 'C', 0, '0', '3', '0', 0}));
        assertFalse(FaceTemplate.isTemplate(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}));
        assertFalse(FaceTemplate.isTemplate(new byte[] {0, 0, 0, 0x0c, 'j', 'P', ' ', ' '}));
        assertFalse(FaceTemplate.isTemplate(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
    }

    @Test
    void corruptTemplatesAreRejected() {
        byte[] good = FaceTemplate.encode(FaceEmbedding.of(MODEL, unitVector(128, 1)));
        assertThrows(IllegalArgumentException.class, () -> FaceTemplate.decode(new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> FaceTemplate.decode(Arrays.copyOf(good, good.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> FaceTemplate.decode(Arrays.copyOf(good, 10)));
        assertThrows(IllegalArgumentException.class, () -> FaceTemplate.decode(Arrays.copyOf(good, good.length + 4)));
        byte[] version = good.clone();
        version[4] = 2;
        assertThrows(IllegalArgumentException.class, () -> FaceTemplate.decode(version));
        byte[] zeroDimension = FaceTemplate.encode(FaceEmbedding.of("m", new float[] {1f}));
        zeroDimension[7] = 0;
        zeroDimension[8] = 0;
        assertThrows(IllegalArgumentException.class, () -> FaceTemplate.decode(zeroDimension));
    }

    @Test
    void overlongModelIdentifierIsRefused() {
        char[] id = new char[256];
        Arrays.fill(id, 'x');
        assertThrows(IllegalArgumentException.class,
                () -> FaceTemplate.encode(FaceEmbedding.of(new String(id), new float[] {1f})));
    }

    static float[] unitVector(int n, int seed) {
        float[] v = new float[n];
        double norm = 0;
        for (int i = 0; i < n; i++) {
            v[i] = (float) Math.sin(seed * 31.0 + i);
            norm += v[i] * v[i];
        }
        for (int i = 0; i < n; i++) {
            v[i] /= (float) Math.sqrt(norm);
        }
        return v;
    }
}
