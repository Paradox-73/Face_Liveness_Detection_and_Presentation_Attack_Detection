package io.mosip.registration.livenessmanager.device;

import java.util.Objects;

import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DeviceStatus;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;

/**
 * A vendor SBI face device app reached through Android intents ({@code io.sbi.device} discovery, info and
 * {@code rCapture}), the path ARC uses today.
 *
 * <p>Android SBI devices give the client no preview frames (the device app shows its own), so
 * {@link #openStream} fails with {@code NO_STREAM} and the engine applies
 * {@code mosip.registration.liveness.no_stream_policy}: for a device that advertises {@code padCapability}
 * (MOSIP issue #1117) the device's own PAD result is used; otherwise liveness cannot run. The capture itself is
 * delegated to ARC's existing intent chain through {@link Bridge}.
 */
public final class SbiIntentDevice implements FaceCaptureDevice {

    /** Runs ARC's existing SBI intent chain for one capture. */
    @FunctionalInterface
    public interface Bridge {

        /**
         * Starts the device app's {@code rCapture} and waits for its answer.
         *
         * @param request capture parameters (transaction ID, timeout, optional PAD request)
         * @return the SBI 0.9.5 response JSON
         * @throws DeviceException if the device app fails, is cancelled or times out
         */
        String capture(CaptureRequest request) throws DeviceException;
    }

    private final DeviceDescriptor descriptor;
    private final Bridge bridge;
    private final DeviceStatus status;

    /**
     * Creates the adapter from what ARC's discovery and device info found.
     *
     * @param descriptor device identity; transport {@code SBI_INTENT}, not stream-capable
     * @param status     status from the last device info call
     * @param bridge     ARC's intent chain
     */
    public SbiIntentDevice(DeviceDescriptor descriptor, DeviceStatus status, Bridge bridge) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.status = Objects.requireNonNull(status, "status");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public DeviceDescriptor describe() {
        return descriptor;
    }

    @Override
    public DeviceStatus status() {
        return status;
    }

    @Override
    public FaceFrameSource openStream(StreamRequest request) throws DeviceException {
        throw new DeviceException(DeviceErrorCode.NO_STREAM, "Android SBI devices do not stream to the client");
    }

    @Override
    public SignedFaceCapture capture(CaptureRequest request) throws DeviceException {
        String json = bridge.capture(request);
        if (json == null || json.isEmpty()) {
            throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "device app returned no capture");
        }
        return SbiCaptureCodec.parse(json, descriptor.certification());
    }
}
