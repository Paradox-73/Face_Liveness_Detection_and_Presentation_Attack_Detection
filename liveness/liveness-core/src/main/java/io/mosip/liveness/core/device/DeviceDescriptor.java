package io.mosip.liveness.core.device;

import java.util.Objects;

/**
 * Identity and capabilities of a face capture device, taken from SBI discovery and the signed device
 * information (or filled in by an in-process adapter such as the Android built-in camera).
 *
 * <p>The serial number binds a pass token to the device that streamed; the certification level and PAD
 * capability are recorded in audits and packet evidence.
 */
public final class DeviceDescriptor {

    private final String deviceId;
    private final String serialNumber;
    private final String make;
    private final String model;
    private final String providerId;
    private final CertificationLevel certification;
    private final String specVersion;
    private final DevicePurpose purpose;
    private final DeviceTransport transport;
    private final boolean streamCapable;
    private final boolean padCapable;

    private DeviceDescriptor(Builder b) {
        this.deviceId = b.deviceId;
        this.serialNumber = b.serialNumber;
        this.make = b.make;
        this.model = b.model;
        this.providerId = b.providerId;
        this.certification = b.certification;
        this.specVersion = b.specVersion;
        this.purpose = b.purpose;
        this.transport = b.transport;
        this.streamCapable = b.streamCapable;
        this.padCapable = b.padCapable;
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
     * Returns the SBI device identifier (for example {@code "3"} for the MockMDS face device).
     *
     * @return the device identifier
     */
    public String deviceId() {
        return deviceId;
    }

    /**
     * Returns the serial number from the device's digital ID.
     *
     * @return the serial number
     */
    public String serialNumber() {
        return serialNumber;
    }

    /**
     * Returns the manufacturer from the digital ID.
     *
     * @return the make, or an empty string if unknown
     */
    public String make() {
        return make;
    }

    /**
     * Returns the model from the digital ID.
     *
     * @return the model, or an empty string if unknown
     */
    public String model() {
        return model;
    }

    /**
     * Returns the device provider identifier from the digital ID.
     *
     * @return the provider identifier, or an empty string if unknown
     */
    public String providerId() {
        return providerId;
    }

    /**
     * Returns the SBI trust level.
     *
     * @return L0 or L1
     */
    public CertificationLevel certification() {
        return certification;
    }

    /**
     * Returns the SBI specification version the device implements.
     *
     * @return for example {@code "0.9.5"}
     */
    public String specVersion() {
        return specVersion;
    }

    /**
     * Returns the device purpose.
     *
     * @return registration or authentication
     */
    public DevicePurpose purpose() {
        return purpose;
    }

    /**
     * Returns how the client reaches the device.
     *
     * @return the transport
     */
    public DeviceTransport transport() {
        return transport;
    }

    /**
     * Tells whether the device can deliver a preview stream. Liveness needs a stream; for devices without one
     * the {@code no_stream_policy} applies.
     *
     * @return {@code true} if {@link FaceCaptureDevice#openStream} is supported
     */
    public boolean isStreamCapable() {
        return streamCapable;
    }

    /**
     * Tells whether the device advertised {@code padCapability} (MOSIP issue {@code #1117}); such devices get a
     * {@link PadRequest} in the capture request and must echo its challenge.
     *
     * @return {@code true} if the device has its own PAD
     */
    public boolean isPadCapable() {
        return padCapable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeviceDescriptor)) {
            return false;
        }
        DeviceDescriptor d = (DeviceDescriptor) o;
        return streamCapable == d.streamCapable && padCapable == d.padCapable && deviceId.equals(d.deviceId)
                && serialNumber.equals(d.serialNumber) && make.equals(d.make) && model.equals(d.model)
                && providerId.equals(d.providerId) && certification == d.certification
                && specVersion.equals(d.specVersion) && purpose == d.purpose && transport == d.transport;
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, serialNumber, make, model, providerId, certification, specVersion, purpose,
                transport, streamCapable, padCapable);
    }

    @Override
    public String toString() {
        return "DeviceDescriptor[" + make + " " + model + ", serial=" + serialNumber + ", " + certification + ", spec="
                + specVersion + ", " + purpose + ", " + transport + ", stream=" + streamCapable + ", pad=" + padCapable
                + "]";
    }

    /** Builder for {@link DeviceDescriptor}. */
    public static final class Builder {

        private String deviceId = "";
        private String serialNumber;
        private String make = "";
        private String model = "";
        private String providerId = "";
        private CertificationLevel certification;
        private String specVersion;
        private DevicePurpose purpose = DevicePurpose.REGISTRATION;
        private DeviceTransport transport;
        private boolean streamCapable = true;
        private boolean padCapable;

        private Builder() {
        }

        /**
         * Sets the SBI device identifier; default empty.
         *
         * @param deviceId device identifier
         * @return this builder
         */
        public Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        /**
         * Sets the serial number; required.
         *
         * @param serialNumber serial number from the digital ID
         * @return this builder
         */
        public Builder serialNumber(String serialNumber) {
            this.serialNumber = serialNumber;
            return this;
        }

        /**
         * Sets the manufacturer; default empty.
         *
         * @param make manufacturer
         * @return this builder
         */
        public Builder make(String make) {
            this.make = make;
            return this;
        }

        /**
         * Sets the model; default empty.
         *
         * @param model model name
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the device provider identifier; default empty.
         *
         * @param providerId provider identifier
         * @return this builder
         */
        public Builder providerId(String providerId) {
            this.providerId = providerId;
            return this;
        }

        /**
         * Sets the trust level; required.
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
         * Sets the purpose; default {@link DevicePurpose#REGISTRATION}.
         *
         * @param purpose device purpose
         * @return this builder
         */
        public Builder purpose(DevicePurpose purpose) {
            this.purpose = purpose;
            return this;
        }

        /**
         * Sets the transport; required.
         *
         * @param transport how the client reaches the device
         * @return this builder
         */
        public Builder transport(DeviceTransport transport) {
            this.transport = transport;
            return this;
        }

        /**
         * Sets whether the device can stream; default {@code true}.
         *
         * @param streamCapable stream support
         * @return this builder
         */
        public Builder streamCapable(boolean streamCapable) {
            this.streamCapable = streamCapable;
            return this;
        }

        /**
         * Sets whether the device advertised {@code padCapability}; default {@code false}.
         *
         * @param padCapable PAD capability
         * @return this builder
         */
        public Builder padCapable(boolean padCapable) {
            this.padCapable = padCapable;
            return this;
        }

        /**
         * Validates the fields and creates the descriptor.
         *
         * @return the descriptor
         * @throws NullPointerException     if a required field or a string field is {@code null}
         * @throws IllegalArgumentException if the serial number or specification version is empty
         */
        public DeviceDescriptor build() {
            Objects.requireNonNull(deviceId, "deviceId");
            Objects.requireNonNull(serialNumber, "serialNumber");
            Objects.requireNonNull(make, "make");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(certification, "certification");
            Objects.requireNonNull(specVersion, "specVersion");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(transport, "transport");
            if (serialNumber.isEmpty() || specVersion.isEmpty()) {
                throw new IllegalArgumentException("serialNumber and specVersion must not be empty");
            }
            return new DeviceDescriptor(this);
        }
    }
}
