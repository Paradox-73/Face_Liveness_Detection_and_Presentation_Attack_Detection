/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.facematch.iso;

import io.mosip.biometrics.util.ConvertRequestDto;
import io.mosip.biometrics.util.face.FaceBDIR;
import io.mosip.biometrics.util.face.FaceDecoder;
import io.mosip.liveness.facematch.SdkTestData;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ISO/IEC 19794-5 face record reading, cross-checked against MOSIP's own reader (biometrics-util). */
class FaceIsoRecordTest {

    @Test
    void readsTheMockMdsRecordsExactlyAsMosipDoes() throws Exception {
        for (byte[] iso : Arrays.asList(SdkTestData.registrationFaceIso(), SdkTestData.authFaceIso(),
                SdkTestData.exceptionPhotoIso())) {
            FaceIsoRecord ours = FaceIsoRecord.parse(iso);
            FaceBDIR mosip = mosipRead(iso);
            assertEquals(FaceIsoRecord.Edition.ISO_19794_5_2011, ours.edition());
            assertEquals(mosip.getWidth(), ours.width());
            assertEquals(mosip.getHeight(), ours.height());
            assertEquals(mosip.getImageDataType(), ours.imageDataType());
            assertEquals(mosip.getFaceImageType(), ours.faceImageType());
            assertEquals(mosip.getQualityBlocks()[0].getQualityScore(), ours.quality());
            assertArrayEquals(mosip.getImage(), ours.image(), "same image bytes as MOSIP's FaceDecoder");
            assertEquals(ours.image().length, ours.imageLength());
        }
    }

    @Test
    void registrationRecordHoldsAJpeg2000Image() {
        FaceIsoRecord r = FaceIsoRecord.parse(SdkTestData.registrationFaceIso());
        byte[] image = r.image();
        assertArrayEquals(new byte[] {0, 0, 0, 0x0c, 'j', 'P', ' ', ' '}, Arrays.copyOf(image, 8));
        assertTrue(r.toString().contains("ISO_19794_5_2011"));
        assertFalse(r.toString().contains("jP"), "toString has no image data");
    }

    @Test
    void readsBuiltRecordsWithQualityBlocksAndLandmarks() {
        byte[] image = {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9};
        FaceIsoRecord r = FaceIsoRecord.parse(SdkTestData.iso2011(image, 0, 640, 480, new int[] {61, 20}, 3));
        assertEquals(640, r.width());
        assertEquals(480, r.height());
        assertEquals(0, r.imageDataType());
        assertEquals(61, r.quality(), "the first quality block");
        assertArrayEquals(image, r.image());

        FaceIsoRecord none = FaceIsoRecord.parse(SdkTestData.iso2011(image, 3, 10, 20, new int[0], 0));
        assertEquals(-1, none.quality());
        assertEquals(3, none.imageDataType());
    }

    @Test
    void reads2005Records() {
        byte[] image = {(byte) 0xff, (byte) 0xd8, 9, 9, (byte) 0xff, (byte) 0xd9};
        FaceIsoRecord r = FaceIsoRecord.parse(SdkTestData.iso2005(image, 320, 240, 2));
        assertEquals(FaceIsoRecord.Edition.ISO_19794_5_2005, r.edition());
        assertEquals(320, r.width());
        assertEquals(240, r.height());
        assertEquals(77, r.quality());
        assertArrayEquals(image, r.image());
    }

    @Test
    void rejectsDataThatIsNotAFaceRecord() {
        assertFalse(FaceIsoRecord.isFaceRecord(null));
        assertFalse(FaceIsoRecord.isFaceRecord(new byte[] {'F', 'A'}));
        assertFalse(FaceIsoRecord.isFaceRecord(new byte[] {'F', 'I', 'R', 0}));
        assertThrows(FaceIsoFormatException.class, () -> FaceIsoRecord.parse(new byte[] {'F', 'I', 'R', 0, 1}));
    }

    @Test
    void everyTruncationFailsCleanly() {
        byte[] iso = SdkTestData.registrationFaceIso();
        for (int length : new int[] {4, 8, 11, 12, 16, 17, 30, 60, 72, 73, 100, iso.length - 1}) {
            byte[] cut = Arrays.copyOf(iso, length);
            assertThrows(FaceIsoFormatException.class, () -> FaceIsoRecord.parse(cut), "length " + length);
        }
    }

    @Test
    void rejectsInconsistentLengthsAndVersions() {
        byte[] image = {1, 2, 3};
        byte[] version = SdkTestData.iso2011(image, 0, 1, 1, new int[] {50}, 0);
        version[5] = '2';
        assertThrows(FaceIsoFormatException.class, () -> FaceIsoRecord.parse(version));

        byte[] recordTooLong = SdkTestData.iso2011(image, 0, 1, 1, new int[] {50}, 0);
        recordTooLong[11]++;
        assertThrows(FaceIsoFormatException.class, () -> FaceIsoRecord.parse(recordTooLong));

        byte[] imageTooLong = SdkTestData.iso2011(image, 0, 1, 1, new int[] {50}, 0);
        imageTooLong[imageTooLong.length - image.length - 1] = 9;
        assertThrows(FaceIsoFormatException.class, () -> FaceIsoRecord.parse(imageTooLong));

        byte[] noImages2005 = SdkTestData.iso2005(image, 1, 1, 0);
        noImages2005[13] = 0;
        assertThrows(FaceIsoFormatException.class, () -> FaceIsoRecord.parse(noImages2005));
    }

    @Test
    void aRecordWithOnly3dImagesHasNoUsableFace() {
        byte[] iso = SdkTestData.iso2011(new byte[] {1, 2, 3}, 0, 1, 1, new int[] {50}, 0);
        iso[iso.length - 3 - 4 - 5 - 2 - 2 - 1 - 1] = (byte) 0x80;
        assertThrows(FaceIsoFormatException.class, () -> FaceIsoRecord.parse(iso));
    }

    private static FaceBDIR mosipRead(byte[] iso) throws Exception {
        ConvertRequestDto dto = new ConvertRequestDto();
        dto.setVersion("ISO19794_5_2011");
        dto.setModality("Face");
        dto.setInputBytes(iso);
        return FaceDecoder.getFaceBDIR(dto);
    }
}
