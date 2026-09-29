package io.mosip.registration.livenessmanager.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Date;

import io.mosip.biometrics.util.face.FaceBDIR;
import io.mosip.liveness.core.device.DevicePurpose;

/** The ISO/IEC 19794-5 record written for the built-in camera reads back with MOSIP biometrics-util. */
@RunWith(RobolectricTestRunner.class)
public class FaceIsoRecordsTest {

    private static final byte[] IMAGE = {0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, (byte) 0x87,
            0x0A, 9, 8, 7, 6, 5, 4, 3, 2, 1};

    @Test
    public void recordCarriesImageSizeAndQuality() throws Exception {
        byte[] iso = FaceIsoRecords.toIsoRecord(IMAGE, 640, 480, DevicePurpose.REGISTRATION, 90, new Date());
        FaceBDIR bdir = new FaceBDIR(new DataInputStream(new ByteArrayInputStream(iso)));
        assertEquals(640, bdir.getWidth());
        assertEquals(480, bdir.getHeight());
        assertEquals(0x46414300L, bdir.getFormatIdentifier());
        assertEquals(1, bdir.getNoOfRepresentations());
        assertArrayEquals(IMAGE, FaceIsoRecords.imageOf(iso));
    }

    @Test(expected = IOException.class)
    public void malformedRecordIsRejected() throws Exception {
        FaceIsoRecords.imageOf(new byte[] {1, 2, 3});
    }
}
