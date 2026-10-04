/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.api.PacketEvidenceKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.SupervisorOverride;

/** Results of a photo taken without a liveness check: liveness off, or a supervisor override. */
public class PhotoCaptureRunnerTest {

    @Test
    public void photoWithLivenessOffCarriesNoEvidence() {
        LivenessCaptureResult r = PhotoCaptureRunner.passResult(Workflow.EXCEPTION_PHOTO, null).build();
        assertTrue(r.isPass());
        assertFalse(r.isLivenessChecked());
        assertFalse(r.isSupervisorOverride());
        assertTrue(r.evidence().isEmpty());
    }

    @Test
    public void overridePhotoIsFlaggedInThePacketEvidence() {
        SupervisorOverride grant = new SupervisorOverride("supervisor1",
                Collections.singletonMap(PacketEvidenceKeys.SESSION, "S-3"));
        LivenessCaptureResult r = PhotoCaptureRunner.passResult(Workflow.REGISTRATION, grant).build();
        assertTrue(r.isSupervisorOverride());
        assertTrue("the evidence is attached like a liveness result's", r.isLivenessChecked());
        assertEquals(PacketEvidenceKeys.DECISION_OVERRIDE, r.evidence().get(PacketEvidenceKeys.DECISION));
        assertEquals("S-3", r.evidence().get(PacketEvidenceKeys.SESSION));
    }
}
