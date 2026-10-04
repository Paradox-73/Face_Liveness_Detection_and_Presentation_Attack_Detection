/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.engine;

import java.util.concurrent.atomic.AtomicReference;

import io.mosip.liveness.core.api.EngineContext;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessEngineProvider;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;

/**
 * A vendor engine as a third-party jar would ship it: a public provider with a no-argument constructor, listed in
 * the test resources' {@code META-INF/services/io.mosip.liveness.core.api.LivenessEngineProvider}.
 */
public final class FakeVendorEngineProvider implements LivenessEngineProvider {

    /** Engine ID the provider serves. */
    public static final String ENGINE_ID = "acme-vendor-engine";

    /** Context of the last {@link #create} call. */
    static final AtomicReference<EngineContext> LAST_CONTEXT = new AtomicReference<>();

    /** Needed by {@link java.util.ServiceLoader}. */
    public FakeVendorEngineProvider() {
        // Nothing to load here.
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public LivenessEngine create(EngineContext context) {
        LAST_CONTEXT.set(context);
        return new VendorEngine();
    }

    /** The engine the provider creates. */
    static final class VendorEngine implements LivenessEngine {
        @Override
        public EngineInfo info() {
            return EngineInfo.builder(ENGINE_ID, "2.0").build();
        }

        @Override
        public LivenessSession open(SessionRequest request, LivenessListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
