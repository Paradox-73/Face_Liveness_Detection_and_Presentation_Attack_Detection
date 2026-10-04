/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.EngineException;

/**
 * Factory for a {@link LivenessEngine}, found with {@link java.util.ServiceLoader} (and, on the desktop, also as
 * a Spring bean). A vendor engine plugs in by shipping a jar with an implementation of this interface listed in
 * {@code META-INF/services/io.mosip.liveness.core.api.LivenessEngineProvider}.
 *
 * <p>Implementations need a public no-argument constructor and must not load models in it; loading happens in
 * {@link #create(EngineContext)}.
 */
public interface LivenessEngineProvider {

    /**
     * Returns the identifier of the engine this provider creates, matched against
     * {@code mosip.registration.liveness.engine}.
     *
     * @return the engine identifier
     */
    String engineId();

    /**
     * Creates the engine. It may load models in the background and report {@link LivenessEngine#isReady()}
     * {@code false} until done.
     *
     * @param context platform services
     * @return the engine
     * @throws EngineException if the engine cannot be created (for example the model bundle fails verification)
     */
    LivenessEngine create(EngineContext context) throws EngineException;
}
