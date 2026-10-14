/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import java.util.EnumMap;

/**
 * Wall-clock time spent in each processing stage since the last {@link #reset()}.
 *
 * <p>The model decorators add to it; the runner resets it before each frame and reads it after. Confined to the
 * runner thread.
 */
final class StageTimer {

    /** A processing stage. */
    enum Stage {
        /** JPEG decode. */
        DECODE,
        /** Face detection, landmarks, Face Mesh, pose and quality. */
        ANALYZE,
        /** Passive PAD ensemble. */
        PAD,
        /** Face embedding (reference face, identity checks, binding). */
        EMBED
    }

    private final EnumMap<Stage, Long> nanos = new EnumMap<>(Stage.class);
    private final EnumMap<Stage, Integer> calls = new EnumMap<>(Stage.class);

    /**
     * Adds a measured duration.
     *
     * @param stage     the stage
     * @param elapsedNs wall-clock nanoseconds
     */
    void add(Stage stage, long elapsedNs) {
        nanos.merge(stage, elapsedNs, Long::sum);
        calls.merge(stage, 1, Integer::sum);
    }

    /**
     * Returns the time spent in a stage.
     *
     * @param stage the stage
     * @return nanoseconds since the last reset
     */
    long nanos(Stage stage) {
        return nanos.getOrDefault(stage, 0L);
    }

    /**
     * Returns how often a stage ran.
     *
     * @param stage the stage
     * @return calls since the last reset
     */
    int calls(Stage stage) {
        return calls.getOrDefault(stage, 0);
    }

    /** Clears all stages. */
    void reset() {
        nanos.clear();
        calls.clear();
    }
}
