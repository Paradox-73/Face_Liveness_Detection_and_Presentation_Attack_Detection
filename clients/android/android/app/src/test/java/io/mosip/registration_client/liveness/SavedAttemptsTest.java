/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;

/** Counted failures and blocks of the capture gate survive an app restart. */
@RunWith(RobolectricTestRunner.class)
public class SavedAttemptsTest {

    private static final String FIELD = "individualBiometrics";
    private static final String RID = "10001100010000120261010";

    private static SavedAttempts saved() {
        return new SavedAttempts(RuntimeEnvironment.getApplication(), "test_capture_attempts");
    }

    private static FaceCaptureLivenessGate gate() {
        FakeLivenessService liveness = new FakeLivenessService();
        liveness.enforced.add(Workflow.REGISTRATION);
        FaceCaptureLivenessGate gate = new FaceCaptureLivenessGate(liveness);
        gate.persistTo(saved());
        return gate;
    }

    @Test
    public void blockAndSuspectedAttackSurviveARestart() {
        gate().record(RID, FIELD, LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.FAIL_PAD)
                .failureReason(FailureReason.PAD).attempts(3, 3).retryAllowed(false).build());

        FaceCaptureLivenessGate restarted = gate();
        assertNotNull("still blocked after the restart", restarted.admit(RID, FIELD));
        assertEquals(3, restarted.usedAttempts(RID, FIELD));
        assertTrue(restarted.isSuspectedAttack(RID, FIELD));

        restarted.record(RID, FIELD, LivenessCaptureResult.builder(Workflow.REGISTRATION, Decision.PASS)
                .attempts(1, 3).build());
        assertEquals("a pass clears the saved count", 0, gate().usedAttempts(RID, FIELD));
    }

    /** An edited or corrupt saved entry blocks the field instead of resetting its count. */
    @Test
    public void corruptEntryFailsClosed() {
        SavedAttempts s = saved();
        s.put(RID + '|' + FIELD, "not,a,count", System.currentTimeMillis());
        assertNotNull("blocked", gate().admit(RID, FIELD));

        RuntimeEnvironment.getApplication().getSharedPreferences("test_capture_attempts", 0).edit()
                .putString(RID + '|' + FIELD, "garbage").commit();
        assertNotNull("an unreadable expiry is kept, not dropped", gate().admit(RID, FIELD));
    }

    @Test
    public void expiredEntriesAreDropped() {
        SavedAttempts s = saved();
        long now = 1_000_000L;
        s.put("old", "1,false,0", now - SavedAttempts.KEEP_MS);
        s.put("new", "2,false,0", now);
        assertEquals(java.util.Collections.singletonMap("new", "2,false,0"), s.load(now));
        assertTrue(s.load(now + SavedAttempts.KEEP_MS).isEmpty());
    }
}
