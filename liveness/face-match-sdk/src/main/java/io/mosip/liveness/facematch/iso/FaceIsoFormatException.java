package io.mosip.liveness.facematch.iso;

/** The data is not a readable ISO/IEC 19794-5 face record. The message never contains record bytes. */
public final class FaceIsoFormatException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the record
     */
    public FaceIsoFormatException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what is wrong with the record
     * @param cause   the underlying error
     */
    public FaceIsoFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
