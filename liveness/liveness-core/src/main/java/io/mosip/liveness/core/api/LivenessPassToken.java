/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.api;

import io.mosip.liveness.core.Workflow;

import java.time.Instant;
import java.util.Objects;

/**
 * Proof that a session passed liveness, issued once in {@code CAPTURE_READY} (docs/design/engine.md section 7).
 *
 * <p>The token is single-use (the client's token store marks it used) and expires at
 * {@code issuedAt + binding.max_gap_ms + capture timeout}; in authentication flows the window starts when the
 * device reports Ready. For devices with {@code padCapability} the {@link #nonce()} is sent as
 * {@code bio[].pad.challenge} and must come back in the signed capture. {@link #toString()} masks the subject
 * reference and the nonce.
 */
public final class LivenessPassToken {

    private final String sessionId;
    private final Workflow workflow;
    private final String subjectRef;
    private final String deviceSerial;
    private final String nonce;
    private final Instant issuedAt;
    private final Instant expiresAt;

    private LivenessPassToken(Builder b) {
        this.sessionId = b.sessionId;
        this.workflow = b.workflow;
        this.subjectRef = b.subjectRef;
        this.deviceSerial = b.deviceSerial;
        this.nonce = b.nonce;
        this.issuedAt = b.issuedAt;
        this.expiresAt = b.expiresAt;
    }

    /**
     * Starts a builder.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the session that issued the token.
     *
     * @return the session identifier
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Returns the workflow the token is valid for.
     *
     * @return the workflow
     */
    public Workflow workflow() {
        return workflow;
    }

    /**
     * Returns the subject the token is bound to.
     *
     * @return registration ID or user ID
     */
    public String subjectRef() {
        return subjectRef;
    }

    /**
     * Returns the serial number of the device that streamed; the capture must come from the same device.
     *
     * @return the device serial number
     */
    public String deviceSerial() {
        return deviceSerial;
    }

    /**
     * Returns the random nonce; also the PAD challenge for {@code padCapability} devices.
     *
     * @return the nonce (URL-safe text, at least 16 random bytes)
     */
    public String nonce() {
        return nonce;
    }

    /**
     * Returns when the token was issued.
     *
     * @return the issue instant
     */
    public Instant issuedAt() {
        return issuedAt;
    }

    /**
     * Returns when the token stops being valid.
     *
     * @return the expiry instant
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Tells whether the token has expired.
     *
     * @param now the current time
     * @return {@code true} if {@code now} is after {@link #expiresAt()}
     */
    public boolean isExpiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LivenessPassToken)) {
            return false;
        }
        LivenessPassToken t = (LivenessPassToken) o;
        return sessionId.equals(t.sessionId) && workflow == t.workflow && subjectRef.equals(t.subjectRef)
                && deviceSerial.equals(t.deviceSerial) && nonce.equals(t.nonce) && issuedAt.equals(t.issuedAt)
                && expiresAt.equals(t.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, workflow, subjectRef, deviceSerial, nonce, issuedAt, expiresAt);
    }

    @Override
    public String toString() {
        return "LivenessPassToken[session=" + sessionId + ", " + workflow.id() + ", subject=***, device=" + deviceSerial
                + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
    }

    /** Builder for {@link LivenessPassToken}; every field is required. */
    public static final class Builder {

        private String sessionId;
        private Workflow workflow;
        private String subjectRef;
        private String deviceSerial;
        private String nonce;
        private Instant issuedAt;
        private Instant expiresAt;

        private Builder() {
        }

        /**
         * Sets the issuing session.
         *
         * @param sessionId session identifier
         * @return this builder
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Sets the workflow.
         *
         * @param workflow workflow
         * @return this builder
         */
        public Builder workflow(Workflow workflow) {
            this.workflow = workflow;
            return this;
        }

        /**
         * Sets the subject.
         *
         * @param subjectRef registration ID or user ID
         * @return this builder
         */
        public Builder subjectRef(String subjectRef) {
            this.subjectRef = subjectRef;
            return this;
        }

        /**
         * Sets the streaming device.
         *
         * @param deviceSerial device serial number
         * @return this builder
         */
        public Builder deviceSerial(String deviceSerial) {
            this.deviceSerial = deviceSerial;
            return this;
        }

        /**
         * Sets the nonce.
         *
         * @param nonce random nonce, at least 16 characters
         * @return this builder
         */
        public Builder nonce(String nonce) {
            this.nonce = nonce;
            return this;
        }

        /**
         * Sets the issue time.
         *
         * @param issuedAt issue instant
         * @return this builder
         */
        public Builder issuedAt(Instant issuedAt) {
            this.issuedAt = issuedAt;
            return this;
        }

        /**
         * Sets the expiry time.
         *
         * @param expiresAt expiry instant, after the issue instant
         * @return this builder
         */
        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /**
         * Validates the fields and creates the token.
         *
         * @return the token
         * @throws NullPointerException     if a field is missing
         * @throws IllegalArgumentException if the nonce is shorter than 16 characters or the expiry is not after
         *                                  the issue time
         */
        public LivenessPassToken build() {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(subjectRef, "subjectRef");
            Objects.requireNonNull(deviceSerial, "deviceSerial");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (nonce.length() < 16) {
                throw new IllegalArgumentException("nonce must have at least 16 characters");
            }
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("expiresAt must be after issuedAt");
            }
            return new LivenessPassToken(this);
        }
    }
}
