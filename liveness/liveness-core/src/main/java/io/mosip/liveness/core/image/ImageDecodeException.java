package io.mosip.liveness.core.image;

/**
 * Thrown when image data cannot be decoded (corrupt or truncated JPEG, unsupported format).
 *
 * <p>A frame that fails to decode is counted as an invalid frame by the session; it is not an engine error.
 */
public class ImageDecodeException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message description safe for logs (no image data)
     */
    public ImageDecodeException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and the underlying cause.
     *
     * @param message description safe for logs (no image data)
     * @param cause   underlying exception
     */
    public ImageDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
