/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;

import org.junit.Before;
import org.junit.Test;

import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.liveness.core.device.FaceFrameSource;
import io.mosip.liveness.core.device.StreamRequest;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.mdm.integrator.MosipDeviceSpecificationProvider;
import io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory;
import io.mosip.registration.service.bio.liveness.LivenessFakes.TestClock;

/**
 * {@link SbiFaceCaptureDevice#openStream}: a device that still reports Busy just after the previous stream closed
 * (a Retry selected at once) is asked again instead of being reported as missing.
 */
public class SbiFaceCaptureDeviceStreamTest {

	private final StreamRequest request = StreamRequest.builder().streamTimeoutMs(20_000).firstFrameTimeoutMs(3_000)
			.socketTimeoutMs(5_000).reconnectAttempts(0).build();
	private MosipDeviceSpecificationProvider provider;
	private SbiFaceCaptureDevice camera;

	@Before
	public void setUp() throws Exception {
		MosipDeviceSpecificationFactory factory = mock(MosipDeviceSpecificationFactory.class);
		provider = mock(MosipDeviceSpecificationProvider.class);
		MdmBioDevice device = new MdmBioDevice();
		device.setSerialNumber(LivenessFakes.SERIAL);
		device.setDeviceId("dev-1");
		device.setSpecVersion("0.9.5");
		when(factory.getDeviceInfoByModality(RegistrationConstants.FACE_FULLFACE)).thenReturn(device);
		when(factory.getMdsProvider(anyString())).thenReturn(provider);
		camera = SbiFaceCaptureDevice.resolve(factory, RegistrationConstants.FACE_FULLFACE, new TestClock(), 0);
	}

	@Test
	public void waitsWhileTheDeviceIsBusy() throws Exception {
		when(provider.stream(any(), anyString(), any(StreamRequest.class))).thenThrow(notReady())
				.thenReturn(new ByteArrayInputStream(new byte[0]));

		FaceFrameSource source = camera.openStream(request);

		assertNotNull(source);
		verify(provider, times(2)).stream(any(), anyString(), any(StreamRequest.class));
	}

	@Test
	public void givesUpWhenTheDeviceStaysUnavailable() throws Exception {
		when(provider.stream(any(), anyString(), any(StreamRequest.class))).thenThrow(notReady());

		long start = System.nanoTime();
		try {
			camera.openStream(request);
			fail("device never ready");
		} catch (DeviceException e) {
			assertEquals(DeviceErrorCode.NOT_READY, e.getErrorCode());
		}
		long waitedMs = (System.nanoTime() - start) / 1_000_000L;
		assertTrue("waited " + waitedMs + " ms", waitedMs >= SbiFaceCaptureDevice.BUSY_WAIT_MS
				&& waitedMs < SbiFaceCaptureDevice.BUSY_WAIT_MS + 1_000);
		verify(provider, atLeast(3)).stream(any(), anyString(), any(StreamRequest.class));
	}

	@Test
	public void otherStreamErrorsFailAtOnce() throws Exception {
		when(provider.stream(any(), anyString(), any(StreamRequest.class))).thenThrow(new RegBaseCheckedException(
				RegistrationExceptionConstants.MDS_STREAM_ERROR.getErrorCode(),
				RegistrationExceptionConstants.MDS_STREAM_ERROR.getErrorMessage()));

		try {
			camera.openStream(request);
			fail("stream error");
		} catch (DeviceException e) {
			assertEquals(DeviceErrorCode.STREAM_OPEN_FAILED, e.getErrorCode());
		}
		verify(provider, times(1)).stream(any(), anyString(), any(StreamRequest.class));
	}

	private static RegBaseCheckedException notReady() {
		return new RegBaseCheckedException(RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorCode(),
				RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorMessage());
	}
}
