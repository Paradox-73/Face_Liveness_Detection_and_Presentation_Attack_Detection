/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.auth;

import java.util.Objects;
import java.util.Optional;

import io.mosip.liveness.core.Workflow;
import io.mosip.registration.livenessmanager.LivenessCaptureResult;

/**
 * Result of one face authentication: the status, the engine message key to show (when the engine has a message
 * for the case) and the attempts. Carries no score or template; a successful authentication keeps the signed
 * capture, so the caller can put the operator's face with its liveness evidence into the packet.
 */
public final class FaceAuthOutcome {

    private final Workflow workflow;
    private final FaceAuthStatus status;
    private final String messageKey;
    private final int attemptsUsed;
    private final int maxAttempts;
    private final LivenessCaptureResult capture;

    /**
     * Creates an outcome.
     *
     * @param workflow     the workflow
     * @param status       how it ended
     * @param messageKey   engine message key ({@code io.mosip.liveness.core.ui.MessageKeys}), or {@code null} when
     *                     the screen chooses the text from the status
     * @param attemptsUsed liveness attempts used, 0 when no capture ran
     * @param maxAttempts  liveness attempts allowed, 0 when no capture ran
     */
    public FaceAuthOutcome(Workflow workflow, FaceAuthStatus status, String messageKey, int attemptsUsed,
                           int maxAttempts) {
        this(workflow, status, messageKey, attemptsUsed, maxAttempts, null);
    }

    private FaceAuthOutcome(Workflow workflow, FaceAuthStatus status, String messageKey, int attemptsUsed,
                            int maxAttempts, LivenessCaptureResult capture) {
        this.capture = capture;
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.status = Objects.requireNonNull(status, "status");
        this.messageKey = messageKey;
        this.attemptsUsed = Math.max(0, attemptsUsed);
        this.maxAttempts = Math.max(0, maxAttempts);
    }

    /**
     * Creates an outcome of a check that ended before any capture.
     *
     * @param workflow   the workflow
     * @param status     how it ended
     * @param messageKey engine message key or {@code null}
     * @return the outcome
     */
    public static FaceAuthOutcome early(Workflow workflow, FaceAuthStatus status, String messageKey) {
        return new FaceAuthOutcome(workflow, status, messageKey, 0, 0);
    }

    /**
     * Returns a copy that keeps the signed capture of a successful authentication.
     *
     * @param result the capture screen's passing result
     * @return the copy
     */
    public FaceAuthOutcome withCapture(LivenessCaptureResult result) {
        return new FaceAuthOutcome(workflow, status, messageKey, attemptsUsed, maxAttempts, result);
    }

    /**
     * Returns the signed capture of a successful authentication.
     *
     * @return the capture (ISO face, SBI response, liveness evidence); empty for any other outcome
     */
    public Optional<LivenessCaptureResult> capture() {
        return isSuccess() ? Optional.ofNullable(capture) : Optional.empty();
    }

    /**
     * Returns the workflow.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return workflow;
    }

    /**
     * Returns how it ended.
     *
     * @return the status
     */
    public FaceAuthStatus status() {
        return status;
    }

    /**
     * Tells whether the user is authenticated.
     *
     * @return {@code true} for {@link FaceAuthStatus#SUCCESS}
     */
    public boolean isSuccess() {
        return status == FaceAuthStatus.SUCCESS;
    }

    /**
     * Returns the engine message key to show.
     *
     * @return the key, or {@code null}
     */
    public String messageKey() {
        return messageKey;
    }

    /**
     * Returns the liveness attempts used.
     *
     * @return the count
     */
    public int attemptsUsed() {
        return attemptsUsed;
    }

    /**
     * Returns the liveness attempts allowed.
     *
     * @return the count
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    @Override
    public String toString() {
        return "FaceAuthOutcome[" + workflow.id() + ", " + status + ", attempts " + attemptsUsed + "/" + maxAttempts
                + "]";
    }
}
