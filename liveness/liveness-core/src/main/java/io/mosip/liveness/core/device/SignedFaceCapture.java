package io.mosip.liveness.core.device;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One signed face capture returned by a device: an ISO/IEC 19794-5 face record plus the signed SBI data block.
 *
 * <p>The adapter has already verified the signature against the DEVICE trust store before it returns this
 * object. Only the metadata is immutable: {@link #isoRecord()} returns the shared array, not a copy.
 * {@link #toString()} never prints the record or the signature.
 */
public final class SignedFaceCapture {

    private final byte[] isoRecord;
    private final String signedData;
    private final Instant capturedAt;
    private final String transactionId;
    private final String deviceSerial;
    private final CertificationLevel certification;
    private final String specVersion;
    private final Double qualityScore;
    private final String echoedChallenge;
    private final DevicePadResult devicePad;
    private final String sessionFrameSha256;

    private SignedFaceCapture(Builder b) {
        this.isoRecord = b.isoRecord;
        this.signedData = b.signedData;
        this.capturedAt = b.capturedAt;
        this.transactionId = b.transactionId;
        this.deviceSerial = b.deviceSerial;
        this.certification = b.certification;
        this.specVersion = b.specVersion;
        this.qualityScore = b.qualityScore;
        this.echoedChallenge = b.echoedChallenge;
        this.devicePad = b.devicePad;
        this.sessionFrameSha256 = b.sessionFrameSha256;
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
     * Returns the ISO/IEC 19794-5 face record (decoded from {@code bioValue}); shared, read only.
     *
     * @return record bytes
     */
    public byte[] isoRecord() {
        return isoRecord;
    }

    /**
     * Returns the signed SBI data block as a compact JWS, kept for the packet.
     *
     * @return the JWS string
     */
    public String signedData() {
        return signedData;
    }

    /**
     * Returns the capture time reported by the device ({@code timestamp}).
     *
     * @return the capture instant
     */
    public Instant capturedAt() {
        return capturedAt;
    }

    /**
     * Returns the transaction identifier echoed by the device.
     *
     * @return the transaction identifier
     */
    public String transactionId() {
        return transactionId;
    }

    /**
     * Returns the serial number of the device that signed the capture.
     *
     * @return the serial number
     */
    public String deviceSerial() {
        return deviceSerial;
    }

    /**
     * Returns the trust level of the signing device.
     *
     * @return L0 or L1
     */
    public CertificationLevel certification() {
        return certification;
    }

    /**
     * Returns the SBI specification version of the response.
     *
     * @return for example {@code "0.9.5"}
     */
    public String specVersion() {
        return specVersion;
    }

    /**
     * Returns the device quality score ({@code qualityScore}).
     *
     * @return score 0-100, if reported
     */
    public OptionalDouble qualityScore() {
        return qualityScore == null ? OptionalDouble.empty() : OptionalDouble.of(qualityScore);
    }

    /**
     * Returns the PAD challenge the device echoed; present only for devices with {@code padCapability}.
     *
     * @return the echoed nonce, if any
     */
    public Optional<String> echoedChallenge() {
        return Optional.ofNullable(echoedChallenge);
    }

    /**
     * Returns the device's own PAD result, if the capture carried a PAD block.
     *
     * @return the device PAD result, if any
     */
    public Optional<DevicePadResult> devicePad() {
        return Optional.ofNullable(devicePad);
    }

    /**
     * Returns the SHA-256 of the session frame an in-process device signed (Android built-in L0 device).
     *
     * @return lower-case hex digest, if any
     */
    public Optional<String> sessionFrameSha256() {
        return Optional.ofNullable(sessionFrameSha256);
    }

    @Override
    public String toString() {
        return "SignedFaceCapture[tx=" + transactionId + ", serial=" + deviceSerial + ", " + certification + ", at="
                + capturedAt + ", recordBytes=" + isoRecord.length + ", echo=" + (echoedChallenge != null)
                + ", devicePad=" + (devicePad == null ? "none" : devicePad.decision()) + "]";
    }

    /** Builder for {@link SignedFaceCapture}. */
    public static final class Builder {

        private byte[] isoRecord;
        private String signedData;
        private Instant capturedAt;
        private String transactionId;
        private String deviceSerial;
        private CertificationLevel certification;
        private String specVersion;
        private Double qualityScore;
        private String echoedChallenge;
        private DevicePadResult devicePad;
        private String sessionFrameSha256;

        private Builder() {
        }

        /**
         * Sets the ISO face record; required. The array is taken over, not copied.
         *
         * @param isoRecord record bytes
         * @return this builder
         */
        public Builder isoRecord(byte[] isoRecord) {
            this.isoRecord = isoRecord;
            return this;
        }

        /**
         * Sets the signed data block; required.
         *
         * @param signedData compact JWS
         * @return this builder
         */
        public Builder signedData(String signedData) {
            this.signedData = signedData;
            return this;
        }

        /**
         * Sets the device capture time; required.
         *
         * @param capturedAt capture instant
         * @return this builder
         */
        public Builder capturedAt(Instant capturedAt) {
            this.capturedAt = capturedAt;
            return this;
        }

        /**
         * Sets the echoed transaction identifier; required.
         *
         * @param transactionId transaction identifier
         * @return this builder
         */
        public Builder transactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        /**
         * Sets the signing device's serial number; required.
         *
         * @param deviceSerial serial number
         * @return this builder
         */
        public Builder deviceSerial(String deviceSerial) {
            this.deviceSerial = deviceSerial;
            return this;
        }

        /**
         * Sets the signing device's trust level; required.
         *
         * @param certification L0 or L1
         * @return this builder
         */
        public Builder certification(CertificationLevel certification) {
            this.certification = certification;
            return this;
        }

        /**
         * Sets the SBI specification version; required.
         *
         * @param specVersion for example {@code "0.9.5"}
         * @return this builder
         */
        public Builder specVersion(String specVersion) {
            this.specVersion = specVersion;
            return this;
        }

        /**
         * Sets the device quality score.
         *
         * @param qualityScore score 0-100, or {@code null}
         * @return this builder
         */
        public Builder qualityScore(Double qualityScore) {
            this.qualityScore = qualityScore;
            return this;
        }

        /**
         * Sets the echoed PAD challenge.
         *
         * @param echoedChallenge nonce echoed by the device, or {@code null}
         * @return this builder
         */
        public Builder echoedChallenge(String echoedChallenge) {
            this.echoedChallenge = echoedChallenge;
            return this;
        }

        /**
         * Sets the device PAD result.
         *
         * @param devicePad result, or {@code null}
         * @return this builder
         */
        public Builder devicePad(DevicePadResult devicePad) {
            this.devicePad = devicePad;
            return this;
        }

        /**
         * Sets the SHA-256 of the signed session frame (in-process devices only).
         *
         * @param sessionFrameSha256 lower-case hex digest, or {@code null}
         * @return this builder
         */
        public Builder sessionFrameSha256(String sessionFrameSha256) {
            this.sessionFrameSha256 = sessionFrameSha256;
            return this;
        }

        /**
         * Validates the fields and creates the capture.
         *
         * @return the capture
         * @throws NullPointerException     if a required field is missing
         * @throws IllegalArgumentException if the record is empty or the quality score is outside 0-100
         */
        public SignedFaceCapture build() {
            Objects.requireNonNull(isoRecord, "isoRecord");
            Objects.requireNonNull(signedData, "signedData");
            Objects.requireNonNull(capturedAt, "capturedAt");
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(deviceSerial, "deviceSerial");
            Objects.requireNonNull(certification, "certification");
            Objects.requireNonNull(specVersion, "specVersion");
            if (isoRecord.length == 0) {
                throw new IllegalArgumentException("isoRecord must not be empty");
            }
            if (qualityScore != null && !(qualityScore >= 0 && qualityScore <= 100)) {
                throw new IllegalArgumentException("qualityScore must be 0-100");
            }
            return new SignedFaceCapture(this);
        }
    }
}
