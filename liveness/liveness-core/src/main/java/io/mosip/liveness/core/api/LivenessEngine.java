/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.EngineException;

/**
 * A liveness engine: the vendor plug-in point (SPI). The default engine combines ONNX face analysis, passive
 * PAD and face embedding models; a certified vendor SDK can replace it behind the same interface.
 *
 * <p>An engine is long-lived (models load once) and thread-safe for {@link #open}; each session it opens is
 * confined to one thread.
 */
public interface LivenessEngine extends AutoCloseable {

    /**
     * Describes the engine and the models it loaded.
     *
     * @return the engine description
     */
    EngineInfo info();

    /**
     * Tells whether the models are loaded and verified. Clients show a waiting state until it is.
     *
     * @return {@code true} if {@link #open} can succeed
     */
    default boolean isReady() {
        return true;
    }

    /**
     * Opens a session in state {@code IDLE}. The caller starts it, feeds it frames and reads its result.
     *
     * @param request  workflow, resolved policy, subject, device, clock and randomness
     * @param listener receiver of UI states, audit events, state changes and the result
     * @return the new session
     * @throws EngineException with {@code NOT_READY} while models are loading, or another code if the engine
     *                         cannot run (fail closed)
     */
    LivenessSession open(SessionRequest request, LivenessListener listener) throws EngineException;

    /** Releases the models and native resources. Idempotent; never throws. */
    @Override
    default void close() {
    }
}
