/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

/**
 * An ordered, finite list of encoded frames (a frame pack), read by index.
 */
interface FrameSequence {

    /**
     * Returns the pack identifier.
     *
     * @return for example {@code "s1-blink"}
     */
    String id();

    /**
     * Returns the number of frames.
     *
     * @return at least 1
     */
    int size();

    /**
     * Returns the encoded bytes of one frame. Callers must not modify the array.
     *
     * @param index 0-based frame index
     * @return JPEG or PNG bytes
     */
    byte[] frame(int index);
}
