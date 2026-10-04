/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.audit;

/**
 * Receiver of diagnostic records, written only when {@code diagnostic.enable=Y}.
 *
 * <p>The desktop client logs {@link DiagnosticRecord#toLogLine()} at DEBUG to {@code logs/registration.log};
 * the Android client to Logcat with tag {@code Liveness}. Implementations must not throw.
 */
public interface DiagnosticSink {

    /**
     * Records one diagnostic entry.
     *
     * @param record the record
     */
    void log(DiagnosticRecord record);

    /**
     * Returns a sink that discards every record.
     *
     * @return a no-op sink
     */
    static DiagnosticSink noop() {
        return record -> {
        };
    }
}
