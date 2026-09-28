package io.mosip.liveness.core.device;

/**
 * Common face capture device interface (device adapter pattern).
 *
 * <p>One implementation exists per way of reaching a camera: an SBI device service over HTTP (desktop),
 * an SBI app over Android intents, the Android built-in camera acting as an L0 device, and a replay source.
 * The liveness session sees only this interface, so it behaves the same with every device.
 *
 * <p>Two calls are separate on purpose: {@link #openStream} gives unsigned preview frames for liveness, and
 * {@link #capture} gives the one signed image that goes into the packet. The session binds the two
 * (stream-to-capture binding, design section 4.10).
 */
public interface FaceCaptureDevice {

    /**
     * Describes the device (identity, trust level, specification version, stream and PAD capability).
     *
     * @return the descriptor
     */
    DeviceDescriptor describe();

    /**
     * Reads the current device status (SBI discovery {@code deviceStatus}).
     *
     * @return the status; {@link DeviceStatus#NOT_FOUND} if the device does not answer
     */
    DeviceStatus status();

    /**
     * Tells whether the device is ready for a stream or a capture.
     *
     * @return {@code true} when {@link #status()} is {@link DeviceStatus#READY}
     */
    default boolean isAvailable() {
        return status() == DeviceStatus.READY;
    }

    /**
     * Opens a preview stream. Frames flow only after {@link FaceFrameSource#start(FrameSink)}.
     *
     * @param request stream parameters
     * @return the frame source; the caller must close it
     * @throws DeviceException with {@link DeviceErrorCode#NO_STREAM} if the device cannot stream, or another
     *                         code if the stream cannot be opened
     */
    FaceFrameSource openStream(StreamRequest request) throws DeviceException;

    /**
     * Takes one signed face capture. Blocks until the device answers or the request times out. The returned
     * capture's signature has already been verified against the DEVICE trust store.
     *
     * @param request capture parameters, including the PAD request for {@code padCapability} devices
     * @return the signed capture
     * @throws DeviceException if the capture fails, times out, or its signature does not verify
     */
    SignedFaceCapture capture(CaptureRequest request) throws DeviceException;
}
