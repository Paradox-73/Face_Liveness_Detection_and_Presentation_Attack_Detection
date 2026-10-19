/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.liveness.core.Clock;
import io.mosip.liveness.core.device.CaptureRequest;
import io.mosip.liveness.core.device.DeviceDescriptor;
import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.DevicePurpose;
import io.mosip.liveness.core.device.DeviceStatus;
import io.mosip.liveness.core.device.DeviceTransport;
import io.mosip.liveness.core.device.FaceCaptureDevice;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.SignedFaceCapture;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.dto.packetmanager.BiometricsDto;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.MDMRequestDto;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.mdm.integrator.MosipDeviceSpecificationProvider;
import io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory;

/**
 * The common face capture device interface over the client's SBI integration (device adapter pattern): any
 * discovered SBI 0.9.5 or 1.0 face device, found through {@link MosipDeviceSpecificationFactory} and driven
 * through its specification provider.
 *
 * <p>{@link #openStream} opens the SBI {@code STREAM} call with the liveness timeouts and returns a
 * {@link FaceStreamSession}; {@link #capture} performs {@code RCAPTURE} and returns the signed ISO record. The
 * engine never sees vendor or SBI details.
 */
public final class SbiFaceCaptureDevice implements FaceCaptureDevice {

	private static final Logger LOGGER = AppConfig.getLogger(SbiFaceCaptureDevice.class);

	/** Longest wait for a device scan that is still running when a liveness check starts. */
	static final long DISCOVERY_WAIT_MS = 3_000L;
	private static final long DISCOVERY_POLL_MS = 200L;
	/** Longest wait for a device that reports Busy while it closes the previous stream. */
	static final long BUSY_WAIT_MS = 2_000L;

	private final MosipDeviceSpecificationFactory factory;
	private final MdmBioDevice device;
	private final String modality;
	private final Clock clock;
	private final DeviceDescriptor descriptor;

	private SbiFaceCaptureDevice(MosipDeviceSpecificationFactory factory, MdmBioDevice device, String modality,
			Clock clock) {
		this.factory = factory;
		this.device = device;
		this.modality = modality;
		this.clock = clock;
		this.descriptor = describe(device);
	}

	/**
	 * Finds the face device among the discovered SBI devices.
	 *
	 * @param factory  the client's device registry
	 * @param modality capture modality, normally {@link RegistrationConstants#FACE_FULLFACE}
	 * @param clock    clock for frame timestamps
	 * @return the device adapter
	 * @throws DeviceException {@link DeviceErrorCode#NOT_FOUND} if no face device was discovered
	 */
	public static SbiFaceCaptureDevice resolve(MosipDeviceSpecificationFactory factory, String modality, Clock clock)
			throws DeviceException {
		return resolve(factory, modality, clock, DISCOVERY_WAIT_MS);
	}

	/**
	 * Finds the face device, waiting at most {@code waitMs} for a device scan that is still running.
	 *
	 * <p>The client clears its device registry and fills it again on a background thread whenever it scans
	 * (after login, from scheduled jobs, and when a lookup misses). A liveness check started during such a scan
	 * used to fail with "The face camera is not available" although the camera was connected; it now waits for
	 * the registry to be filled and looks again once.
	 *
	 * @param factory  the client's device registry
	 * @param modality capture modality
	 * @param clock    clock for frame timestamps
	 * @param waitMs   longest wait for a running scan
	 * @return the device adapter
	 * @throws DeviceException {@link DeviceErrorCode#NOT_FOUND} if no face device was discovered
	 */
	static SbiFaceCaptureDevice resolve(MosipDeviceSpecificationFactory factory, String modality, Clock clock,
			long waitMs) throws DeviceException {
		Objects.requireNonNull(factory, "factory");
		try {
			return new SbiFaceCaptureDevice(factory, factory.getDeviceInfoByModality(modality), modality, clock);
		} catch (RegBaseCheckedException | RuntimeException first) {
			long deadline = System.nanoTime() + waitMs * 1_000_000L;
			while (System.nanoTime() < deadline) {
				if (!sleep(DISCOVERY_POLL_MS)) {
					break;
				}
				if (MosipDeviceSpecificationFactory.getDeviceRegistryInfo().isEmpty()) {
					continue;
				}
				try {
					LOGGER.info("Face device found after waiting for the device scan");
					return new SbiFaceCaptureDevice(factory, factory.getDeviceInfoByModality(modality), modality,
							clock);
				} catch (RegBaseCheckedException | RuntimeException again) {
					break;
				}
			}
			throw new DeviceException(DeviceErrorCode.NOT_FOUND, "no face device discovered", first);
		}
	}

	private static boolean sleep(long ms) {
		try {
			Thread.sleep(ms);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Builds the device-independent description of an SBI device.
	 *
	 * @param device the discovered device
	 * @return identity, trust level, specification version and capabilities
	 */
	public static DeviceDescriptor describe(MdmBioDevice device) {
		return DeviceDescriptor.builder().deviceId(nonNull(device.getDeviceId()))
				.serialNumber(SignedCaptureReader.serialOf(device)).make(nonNull(device.getDeviceMake()))
				.model(nonNull(device.getDeviceModel())).providerId(nonNull(device.getDeviceProviderId()))
				.certification(SignedCaptureReader.certificationOf(device))
				.specVersion(device.getSpecVersion() == null || device.getSpecVersion().isEmpty() ? "unknown"
						: device.getSpecVersion())
				.purpose(DevicePurpose.fromCode(device.getPurpose()).orElse(DevicePurpose.REGISTRATION))
				.transport(DeviceTransport.SBI_HTTP).streamCapable(true).padCapable(false).build();
	}

	/**
	 * Returns the underlying SBI device.
	 *
	 * @return the discovered device
	 */
	public MdmBioDevice bioDevice() {
		return device;
	}

	@Override
	public DeviceDescriptor describe() {
		return descriptor;
	}

	@Override
	public DeviceStatus status() {
		try {
			MosipDeviceSpecificationProvider provider = factory.getMdsProvider(device.getSpecVersion());
			return provider.isDeviceAvailable(device) ? DeviceStatus.READY : DeviceStatus.NOT_READY;
		} catch (RegBaseCheckedException | RuntimeException e) {
			return DeviceStatus.NOT_FOUND;
		}
	}

	@Override
	public FaceFrameSource openStream(StreamRequest request) throws DeviceException {
		Objects.requireNonNull(request, "request");
		FaceStreamSession.Connector connector = () -> open(request);
		return new FaceStreamSession(open(request), connector, request, clock, descriptor.serialNumber());
	}

	@Override
	public SignedFaceCapture capture(CaptureRequest request) throws DeviceException {
		Objects.requireNonNull(request, "request");
		MDMRequestDto dto = new MDMRequestDto(modality, null, "Registration",
				ApplicationContext.getStringValueFromApplicationMap(RegistrationConstants.SERVER_ACTIVE_PROFILE),
				(int) request.timeoutMs(), 1, 0);
		try {
			List<BiometricsDto> captures = factory.getMdsProvider(device.getSpecVersion()).rCapture(device, dto);
			for (BiometricsDto c : captures) {
				if (c != null && c.getAttributeISO() != null) {
					return SignedCaptureReader.read(c, device, clock.now());
				}
			}
			throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "capture returned no face record");
		} catch (RegBaseCheckedException | RuntimeException e) {
			throw new DeviceException(DeviceErrorCode.CAPTURE_FAILED, "face capture failed", e);
		}
	}

	/**
	 * Opens the SBI stream. A device reports itself Busy (not Ready) until it notices that the previous stream was
	 * closed, which it does only at its next frame write; a retry or reconnect started at once after a stream ended
	 * used to fail with "The face camera is not available" (resident workflows keep the stream open, so the driver's
	 * wait for Ready between sessions, {@code releaseDeviceBeforeCapture}, is not on for them). A device that is not
	 * Ready is asked again for at most {@link #BUSY_WAIT_MS}.
	 */
	private InputStream open(StreamRequest request) throws DeviceException {
		long deadline = System.nanoTime() + BUSY_WAIT_MS * 1_000_000L;
		while (true) {
			try {
				MosipDeviceSpecificationProvider provider = factory.getMdsProvider(device.getSpecVersion());
				return provider.stream(device, modality, request);
			} catch (RegBaseCheckedException e) {
				boolean notFound = RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorCode()
						.equals(e.getErrorCode());
				if (notFound && System.nanoTime() < deadline && sleep(DISCOVERY_POLL_MS)) {
					LOGGER.info("Face device not Ready for a new stream; asking again");
					continue;
				}
				throw streamFailure(e, notFound);
			} catch (RuntimeException e) {
				throw new DeviceException(DeviceErrorCode.STREAM_OPEN_FAILED, "face stream could not be opened", e);
			}
		}
	}

	private static DeviceException streamFailure(RegBaseCheckedException e, boolean notFound) {
		LOGGER.error("Face stream could not be opened: {}", e.getErrorCode());
		return new DeviceException(notFound ? DeviceErrorCode.NOT_READY : DeviceErrorCode.STREAM_OPEN_FAILED,
				"face stream could not be opened: " + e.getErrorCode(), e);
	}

	private static String nonNull(String value) {
		return value == null ? "" : value;
	}

	@Override
	public String toString() {
		return "SbiFaceCaptureDevice[" + descriptor + "]";
	}
}
