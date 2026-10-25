/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import io.mosip.liveness.core.Decision;
import io.mosip.liveness.core.Workflow;
import io.mosip.registration.clientmanager.spi.RegistrationService;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;

/**
 * The officer face of a packet authentication: a face that cannot be stored is reported to the caller, which then
 * refuses the authentication, instead of the packet being written without it.
 */
@RunWith(RobolectricTestRunner.class)
public class OperatorFaceEvidenceTest {

    private final List<String> calls = new ArrayList<>();
    /** No registration is open: every call fails, as ARC's service does then. */
    private final RegistrationService registration = (RegistrationService) Proxy.newProxyInstance(
            RegistrationService.class.getClassLoader(), new Class<?>[] {RegistrationService.class},
            (proxy, method, args) -> {
                calls.add(method.getName());
                throw new IllegalStateException("no open registration");
            });
    private final OperatorFaceEvidence evidence = new OperatorFaceEvidence(registration, null);

    @Test
    public void faceThatCannotBeStoredIsReported() {
        assertFalse(evidence.keep(passWithResponse("{\"biometrics\":[]}")));
    }

    @Test
    public void captureWithoutSignedResponseHasNothingToStore() {
        assertTrue("a vendor SBI app gives no response to keep", evidence.keep(passWithResponse(null)));
        assertTrue(calls.isEmpty());
    }

    private static LivenessCaptureResult passWithResponse(String response) {
        return LivenessCaptureResult.builder(Workflow.PACKET_AUTH, Decision.PASS)
                .capture("txn-1", new byte[] {1, 2, 3}, response, response != null).build();
    }
}
