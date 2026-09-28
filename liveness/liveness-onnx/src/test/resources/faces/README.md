# Test face images

Test-only images; they are not packaged in any jar. All three come from the MOSIP mock biometric device in this
repository (`devices/mock-mds/`, upstream `mosip/mosip-mock-services` `MockMDS`, MPL-2.0), so each file keeps
the MPL-2.0 licence.

| File | Source | Change |
|---|---|---|
| `mockmds-stream-0.jpeg` | `devices/mock-mds/Biometric Devices/Face/Stream Image/0.jpeg` (490x360, the frame MockMDS streams; has the device's overlay text and markers) | none (byte-identical copy) |
| `mockmds-registration-face.png` | JPEG 2000 image inside `devices/mock-mds/Profile/Default/Registration/Face.iso` (ISO/IEC 19794-5 record, image at byte offset 73) | decoded to PNG (lossless) with OpenCV 5.0 |
| `mockmds-exception-photo.jpg` | JPEG 2000 image inside `devices/mock-mds/Profile/Default/Registration/Exception_Photo.iso` (offset 73) | decoded with OpenCV 5.0, cropped to x 100-499, y 0-399, saved as JPEG quality 92 |

The stream frame and the registration face show the same person; the exception photo shows a different person.
The golden tests use them for detection, landmarks, passive PAD, and same-person / different-person
similarity.
