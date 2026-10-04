/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;

public class EngineFaceVerifierTest {

    private final Map<String, String> config = new HashMap<>();

    private EngineFaceVerifier verifier(EngineFaceVerifier.MatcherSource source) {
        return new EngineFaceVerifier(source, key -> Optional.ofNullable(config.get(key)));
    }

    @Test
    public void emptyGalleryIsReportedWithoutLoadingModels() {
        boolean[] loaded = {false};
        FaceMatch match = verifier(() -> {
            loaded[0] = true;
            throw new EngineException(EngineErrorCode.NOT_READY, "not used");
        }).verify(new byte[] {1}, Collections.emptyList());

        assertEquals(FaceMatch.Status.NO_GALLERY, match.status());
        assertFalse(loaded[0]);
    }

    @Test
    public void unavailableModelsAreReported() {
        FaceMatch match = verifier(() -> {
            throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "bundle rejected");
        }).verify(new byte[] {1}, Collections.singletonList(new byte[] {2}));

        assertEquals(FaceMatch.Status.UNAVAILABLE, match.status());
        assertFalse(match.isMatch());
        assertEquals(0, match.score());
    }

    @Test
    public void thresholdComesFromConfigurationWithDefaultAndFloor() {
        EngineFaceVerifier v = verifier(() -> null);
        assertEquals(EngineFaceVerifier.DEFAULT_THRESHOLD, v.threshold());
        config.put(EngineFaceVerifier.MATCH_THRESHOLD_KEY, " 55 ");
        assertEquals(55, v.threshold());
        config.put(EngineFaceVerifier.MATCH_THRESHOLD_KEY, "5");
        assertEquals(EngineFaceVerifier.MIN_THRESHOLD, v.threshold());
        config.put(EngineFaceVerifier.MATCH_THRESHOLD_KEY, "140");
        assertEquals(100, v.threshold());
        config.put(EngineFaceVerifier.MATCH_THRESHOLD_KEY, "high");
        assertEquals(EngineFaceVerifier.DEFAULT_THRESHOLD, v.threshold());
    }

    @Test
    public void comparedResultAppliesTheThreshold() {
        assertTrue(FaceMatch.compared(40, 40).isMatch());
        assertEquals(FaceMatch.Status.NOT_MATCHED, FaceMatch.compared(39, 40).status());
    }

    @Test(expected = IllegalArgumentException.class)
    public void notComparedNeedsANonComparisonStatus() {
        FaceMatch.notCompared(FaceMatch.Status.MATCHED, 40);
    }
}
