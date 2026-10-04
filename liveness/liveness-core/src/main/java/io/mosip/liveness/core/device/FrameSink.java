/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.device;

/**
 * Receiver of a face stream.
 *
 * <p>Implementations must return quickly: a source calls them from its reader thread, and a slow sink delays
 * the next frame. The analysis path therefore only stores the newest frame in a single slot and lets a worker
 * thread process it; older unprocessed frames are dropped, never queued.
 */
public interface FrameSink {

    /**
     * Receives one frame.
     *
     * @param frame the frame; ownership passes to the sink
     */
    void onFrame(FaceFrame frame);

    /**
     * Receives the failure that ended the stream. No callback follows.
     *
     * @param error the device error
     */
    void onError(DeviceError error);

    /**
     * Receives the normal end of the stream. No callback follows.
     *
     * @param reason why the stream ended
     */
    void onEnd(EndReason reason);
}
