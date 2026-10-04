package io.mosip.registration.livenessmanager.device;

import android.graphics.Bitmap;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Date;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.CertificationLevel;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.DeviceStatus;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.registration.livenessmanager.image.AndroidImageDecoder;

/**
 * The phone's or tablet's own camera as an SBI L0 face device inside the client (docs/design/android-liveness.md,
 * section 5).
 *
 * <p>The stream is the liveness session's frame source (CameraX, or a replay in debug builds). The signed capture
 * is not a new picture: it is the frontal, neutral frame the session selected ({@link CaptureRequest#sourceFrame()}),
 * so the stored face is by construction one the liveness check saw. The frame is turned upright, encoded as
 * JPEG 2000, wrapped in an ISO/IEC 19794-5 record and signed as an SBI 0.9.5 capture (RS256, device certificate in
 * {@code x5c}). The signed header also carries the session ID, the frame's SHA-256 and the pass-token nonce. The
 * result goes through ARC's unchanged SBI checks (signature, DEVICE trust, time, transaction ID, quality).
 *
 * <p>The trust model of the key is described in {@link AndroidKeystoreSigner}.
 */
public final class BuiltInCameraL0Device implements FaceCaptureDevice {

    /** Device ID in the descriptor. */
    public static final String DEVICE_ID = "builtin-face-camera";
    /** Make and device provider name. */
    public static final String MAKE = "MOSIP";
    /** Model name. */
    public static final String MODEL = "Built-in camera";
    /** Device provider ID. */
    public static final String PROVIDER_ID = "MOSIP.LIVENESS";
    /** Quality reported in the capture (the liveness quality gate already checked sharpness, light and pose). */
    public static final int REPORTED_QUALITY = 90;

    /** Opens the frame stream of one session. */
    @FunctionalInterface
    public interface StreamFactory {

        /**
         * Opens a stream.
         *
         * @param request stream parameters
         * @return the source
         * @throws DeviceException if the camera cannot be opened
         */
        FaceFrameSource open(StreamRequest request) throws DeviceException;
    }

    private final DeviceDescriptor descriptor;
    private final StreamFactory streams;
    private final BooleanSupplier ready;
    private final AndroidImageDecoder decoder;
    private final DeviceSigner signer;
    private final Clock clock;
    private final String environment;
    private volatile String lastResponseJson;

    /**
     * Creates the device.
     *
     * @param serialNumber stable serial number of this installation
     * @param purpose      registration or authentication
     * @param streams      frame stream factory
     * @param ready        tells whether the camera can be used (permission granted, not blocked)
     * @param decoder      image decoder
     * @param signer       device key
     * @param clock        clock for capture times
     * @param environment  SBI environment name written in the capture ({@code Developer} in debug builds)
     */
    public BuiltInCameraL0Device(String serialNumber, DevicePurpose purpose, StreamFactory streams,
                                 BooleanSupplier ready, AndroidImageDecoder decoder, DeviceSigner signer, Clock clock,
                                 String environment) {
        this.descriptor = DeviceDescriptor.builder().deviceId(DEVICE_ID)
                .serialNumber(Objects.requireNonNull(serialNumber, "serialNumber")).make(MAKE).model(MODEL)
                .providerId(PROVIDER_ID).certification(CertificationLevel.L0)
                .specVersion(SbiCaptureCodec.SPEC_VERSION).purpose(Objects.requireNonNull(purpose, "purpose"))
                .transport(DeviceTransport.BUILTIN_CAMERA).streamCapable(true).padCapable(false).build();
        this.streams = Objects.requireNonNull(streams, "streams");
        this.ready = Objects.requireNonNull(ready, "ready");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public DeviceDescriptor describe() {
        return descriptor;
    }

    @Override
    public DeviceStatus status() {
        return ready.getAsBoolean() ? DeviceStatus.READY : DeviceStatus.NOT_READY;
    }

    @Override
    public FaceFrameSource openStream(StreamRequest request) throws DeviceException {
        if (!ready.getAsBoolean()) {
            throw new DeviceException(DeviceErrorCode.CAMERA_PERMISSION_DENIED, "camera cannot be used");
        }
        return streams.open(request);
    }

    /**
     * Signs the session frame named in the request. The pass-token nonce and session ID are taken from
     * {@link #capture(CaptureRequest, String, String)}; this overload leaves them out.
     */
    @Override
    public SignedFaceCapture capture(CaptureRequest request) throws DeviceException {
        return capture(request, null, null);
    }

    /**
     * Signs the session frame named in the request and records the SBI response JSON
     * ({@link #lastResponseJson()}).
     *
     * @param request   capture request with {@link CaptureRequest#sourceFrame()} set
     * @param sessionId liveness session ID written in the signed header, or {@code null}
     * @param nonce     pass-token nonce written in the signed header, or {@code null}
     * @return the signed capture
     * @throws DeviceException {@code CAPTURE_FAILED} if there is no frame or it cannot be encoded or signed
     */
    public SignedFaceCapture capture(CaptureRequest request, String sessionId, String nonce) throws DeviceException {
        FaceFrame frame = request.sourceFrame().orElseThrow(() ->
                new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "the built-in camera signs a session frame only"));
        ImageBuffer upright = null;
        Bitmap bitmap = null;
        try {
            upright = decoder.decode(frame);
            bitmap = AndroidImageDecoder.toBitmap(upright);
            Date now = new Date(clock.now().toEpochMilli());
            byte[] iso = FaceIsoRecords.toIsoRecord(FaceIsoRecords.encodeJp2(bitmap), upright.width(),
                    upright.height(), descriptor.purpose(), REPORTED_QUALITY, now);
            String frameSha = SbiCaptureCodec.sha256Hex(frame.data());
            SbiCaptureCodec.CaptureContent content = new SbiCaptureCodec.CaptureContent(descriptor, iso,
                    request.transactionId(), clock.now(), descriptor.purpose().code(), environment,
                    REPORTED_QUALITY, String.valueOf(REPORTED_QUALITY), sessionId, frameSha, nonce);
            String jws = SbiCaptureCodec.signCapture(content, signer);
            lastResponseJson = SbiCaptureCodec.responseJson(jws, iso);
            return SignedFaceCapture.builder().isoRecord(iso).signedData(jws).capturedAt(clock.now())
                    .transactionId(request.transactionId()).deviceSerial(descriptor.serialNumber())
                    .certification(CertificationLevel.L0).specVersion(SbiCaptureCodec.SPEC_VERSION)
                    .qualityScore((double) REPORTED_QUALITY).sessionFrameSha256(frameSha).build();
        } catch (ImageDecodeException | IOException | GeneralSecurityException | RuntimeException | LinkageError e) {
            throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED,
                    "built-in capture failed: " + e.getClass().getSimpleName(), e);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (upright != null) {
                upright.wipe();
            }
        }
    }

    /**
     * Returns the SBI 0.9.5 {@code rCapture} response of the last successful capture, for ARC's
     * {@code Biometrics095Service.handleRCaptureResponse}.
     *
     * @return the response JSON, or {@code null} before the first capture
     */
    public String lastResponseJson() {
        return lastResponseJson;
    }

    /**
     * Tells whether the device key has a certificate issued by a DEVICE certificate authority.
     *
     * @return {@code false} while the key is self-signed
     */
    public boolean isProvisioned() {
        return signer.isProvisioned();
    }
}
