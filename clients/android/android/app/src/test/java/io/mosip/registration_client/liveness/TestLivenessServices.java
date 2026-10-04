/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import java.util.List;

import io.mosip.liveness.core.FailureReason;
import io.mosip.liveness.core.Workflow;
import io.mosip.liveness.core.ui.MessageKeys;
import io.mosip.registration.livenessmanager.LivenessCaptureRequest;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;
import io.mosip.registration.livenessmanager.LivenessService;

/**
 * Test fakes of this package for tests in other packages (the bridge classes in {@code api_services}).
 */
public final class TestLivenessServices {

    private TestLivenessServices() {
    }

    /**
     * Creates a fake liveness facade with no enforced workflow.
     *
     * @return the fake
     */
    public static Fake fake() {
        return new Fake(new FakeLivenessService());
    }

    /**
     * A resident capture that failed presentation attack detection with no retry left.
     *
     * @param attempts attempts used (and allowed)
     * @return the capture screen's result
     */
    public static LivenessCaptureResult padFailure(int attempts) {
        return LivenessCaptureResult.builder(Workflow.REGISTRATION, FailureReason.PAD.decision())
                .failureReason(FailureReason.PAD).messageKey(MessageKeys.FAIL_PAD)
                .attempts(attempts, attempts).retryAllowed(false).build();
    }

    /** Handle on a {@link FakeLivenessService}. */
    public static final class Fake {
        private final FakeLivenessService service;

        private Fake(FakeLivenessService service) {
            this.service = service;
        }

        /**
         * Returns the fake as the facade.
         *
         * @return the facade
         */
        public LivenessService service() {
            return service;
        }

        /**
         * Switches liveness on for a workflow.
         *
         * @param workflow the workflow
         */
        public void enforce(Workflow workflow) {
            service.enforced.add(workflow);
        }

        /**
         * Returns the capture screen requests made so far.
         *
         * @return the requests
         */
        public List<LivenessCaptureRequest> requests() {
            return service.requests;
        }
    }
}
