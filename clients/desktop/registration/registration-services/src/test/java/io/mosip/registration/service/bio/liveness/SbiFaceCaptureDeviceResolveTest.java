package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.mosip.liveness.core.device.DeviceErrorCode;
import io.mosip.liveness.core.device.DeviceException;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.exception.RegBaseCheckedException;
import io.mosip.registration.exception.RegistrationExceptionConstants;
import io.mosip.registration.mdm.dto.MdmBioDevice;
import io.mosip.registration.mdm.service.impl.MosipDeviceSpecificationFactory;
import io.mosip.registration.service.bio.liveness.LivenessFakes.TestClock;

/**
 * {@link SbiFaceCaptureDevice#resolve}: a liveness check that starts while the client rescans its devices waits for
 * the scan instead of reporting the camera as missing.
 */
public class SbiFaceCaptureDeviceResolveTest {

	private static final String KEY = "Face_Full face";

	private final TestClock clock = new TestClock();
	private MosipDeviceSpecificationFactory factory;
	private MdmBioDevice device;
	private Map<String, MdmBioDevice> registry;
	private Map<String, MdmBioDevice> saved;

	@Before
	public void setUp() {
		factory = mock(MosipDeviceSpecificationFactory.class);
		device = new MdmBioDevice();
		device.setSerialNumber(LivenessFakes.SERIAL);
		device.setDeviceId("dev-1");
		device.setCertification("L0");
		// The client's device registry is static: start from an empty one and put the original back afterwards.
		registry = MosipDeviceSpecificationFactory.getDeviceRegistryInfo();
		saved = new LinkedHashMap<>(registry);
		registry.clear();
	}

	@After
	public void tearDown() {
		registry.clear();
		registry.putAll(saved);
	}

	@Test
	public void foundAtOnceWithoutWaiting() throws Exception {
		when(factory.getDeviceInfoByModality(RegistrationConstants.FACE_FULLFACE)).thenReturn(device);

		SbiFaceCaptureDevice found = SbiFaceCaptureDevice.resolve(factory, RegistrationConstants.FACE_FULLFACE, clock, 0);

		assertEquals(LivenessFakes.SERIAL, found.describe().serialNumber());
	}

	@Test
	public void waitsForARunningScan() throws Exception {
		when(factory.getDeviceInfoByModality(RegistrationConstants.FACE_FULLFACE)).thenThrow(notFound())
				.thenReturn(device);
		// The scan thread fills the registry a little later.
		Thread scan = new Thread(() -> {
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			registry.put(KEY, device);
		});
		scan.start();

		SbiFaceCaptureDevice found = SbiFaceCaptureDevice.resolve(factory, RegistrationConstants.FACE_FULLFACE, clock,
				3_000);

		scan.join();
		assertEquals(LivenessFakes.SERIAL, found.describe().serialNumber());
		verify(factory, times(2)).getDeviceInfoByModality(RegistrationConstants.FACE_FULLFACE);
	}

	@Test
	public void givesUpWhenTheScanFindsNoFaceDevice() throws Exception {
		when(factory.getDeviceInfoByModality(RegistrationConstants.FACE_FULLFACE)).thenThrow(notFound());
		registry.put(KEY, device);

		long start = System.nanoTime();
		try {
			SbiFaceCaptureDevice.resolve(factory, RegistrationConstants.FACE_FULLFACE, clock, 3_000);
			fail("no face device");
		} catch (DeviceException e) {
			assertEquals(DeviceErrorCode.NOT_FOUND, e.getErrorCode());
		}
		assertTrue("gave up after one more lookup", System.nanoTime() - start < 2_000_000_000L);
		verify(factory, times(2)).getDeviceInfoByModality(RegistrationConstants.FACE_FULLFACE);
	}

	@Test
	public void givesUpAfterTheWaitWhenNothingIsDiscovered() throws Exception {
		when(factory.getDeviceInfoByModality(RegistrationConstants.FACE_FULLFACE)).thenThrow(notFound());

		try {
			SbiFaceCaptureDevice.resolve(factory, RegistrationConstants.FACE_FULLFACE, clock, 500);
			fail("no face device");
		} catch (DeviceException e) {
			assertEquals(DeviceErrorCode.NOT_FOUND, e.getErrorCode());
		}
		verify(factory, times(1)).getDeviceInfoByModality(RegistrationConstants.FACE_FULLFACE);
	}

	private static RegBaseCheckedException notFound() {
		return new RegBaseCheckedException(RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorCode(),
				RegistrationExceptionConstants.MDS_BIODEVICE_NOT_FOUND.getErrorMessage());
	}
}
