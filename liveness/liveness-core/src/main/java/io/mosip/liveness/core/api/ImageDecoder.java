package io.mosip.liveness.core.api;

import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;

/**
 * Platform service that turns frames and encoded images into {@link ImageBuffer}s.
 *
 * <p>The core cannot decode JPEG, PNG or JPEG 2000 itself (no ImageIO on Android, no BitmapFactory on the
 * desktop), so each client supplies one implementation: the desktop client with ImageIO, the Android client
 * with {@code BitmapFactory} and its JPEG 2000 decoder. Implementations also convert raw formats when asked
 * and apply {@link FaceFrame#rotationDegrees()}, so the result is always upright.
 */
public interface ImageDecoder {

    /**
     * Decodes one stream frame into an upright image.
     *
     * @param frame frame in any {@link PixelFormat}
     * @return the decoded image; a raw format of the implementation's choice
     * @throws ImageDecodeException if the frame data is corrupt, truncated or in an unsupported format
     */
    ImageBuffer decode(FaceFrame frame) throws ImageDecodeException;

    /**
     * Decodes an encoded image whose format is identified from its leading bytes (JPEG, PNG or JPEG 2000).
     * Used for the image extracted from a signed ISO/IEC 19794-5 capture.
     *
     * @param encoded file bytes; not modified
     * @return the decoded image
     * @throws ImageDecodeException if the data is corrupt or in an unsupported format
     */
    ImageBuffer decode(byte[] encoded) throws ImageDecodeException;
}
