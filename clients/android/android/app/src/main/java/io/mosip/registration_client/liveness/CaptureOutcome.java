/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration_client.liveness;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Liveness outcome of one face capture, as the Flutter capture block shows it. Holds a message key of the engine's
 * catalogue, not text, so the text follows the display language.
 */
public final class CaptureOutcome {

    /** How the capture ended. The name is the status string sent to Flutter. */
    public enum Status {
        /** No liveness capture ran for this field (liveness off, or not a face). */
        NOT_REQUIRED,
        /** Liveness passed; the face was stored. */
        PASSED,
        /** Liveness failed; the face was not stored and another capture may be tried. */
        FAILED,
        /** Liveness failed on every allowed attempt; face capture stays blocked for this registration. */
        BLOCKED,
        /** The capture screen was closed before any attempt ended. */
        CANCELLED
    }

    private static final CaptureOutcome NONE = new CaptureOutcome(Status.NOT_REQUIRED, null,
            Collections.emptyList(), 0, 0);

    private final Status status;
    private final String messageKey;
    private final List<String> args;
    private final int attemptsUsed;
    private final int maxAttempts;

    /**
     * Creates an outcome.
     *
     * @param status       how it ended
     * @param messageKey   engine message key, or {@code null}
     * @param args         message arguments
     * @param attemptsUsed liveness attempts used for this field so far
     * @param maxAttempts  liveness attempts allowed
     */
    public CaptureOutcome(Status status, String messageKey, List<String> args, int attemptsUsed, int maxAttempts) {
        this.status = Objects.requireNonNull(status, "status");
        this.messageKey = messageKey;
        this.args = args == null ? Collections.emptyList() : Collections.unmodifiableList(args);
        this.attemptsUsed = attemptsUsed;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Returns the outcome of a field without a liveness capture.
     *
     * @return {@link Status#NOT_REQUIRED}
     */
    public static CaptureOutcome none() {
        return NONE;
    }

    /**
     * Returns how the capture ended.
     *
     * @return the status
     */
    public Status status() {
        return status;
    }

    /**
     * Returns the engine message key.
     *
     * @return the key, or {@code null}
     */
    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns the message arguments.
     *
     * @return values for {@code {0}}, {@code {1}}
     */
    public List<String> args() {
        return args;
    }

    /**
     * Returns the attempts used.
     *
     * @return the count
     */
    public int attemptsUsed() {
        return attemptsUsed;
    }

    /**
     * Returns the attempts allowed.
     *
     * @return the count
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    @Override
    public String toString() {
        return "CaptureOutcome[" + status + ", " + messageKey + ", " + attemptsUsed + "/" + maxAttempts + "]";
    }
}
