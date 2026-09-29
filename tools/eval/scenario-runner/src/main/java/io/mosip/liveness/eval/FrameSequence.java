package io.mosip.liveness.eval;

/**
 * An ordered, finite list of encoded frames (a frame pack), read by index.
 */
interface FrameSequence {

    /**
     * Returns the pack identifier.
     *
     * @return for example {@code "s1-blink"}
     */
    String id();

    /**
     * Returns the number of frames.
     *
     * @return at least 1
     */
    int size();

    /**
     * Returns the encoded bytes of one frame. Callers must not modify the array.
     *
     * @param index 0-based frame index
     * @return JPEG or PNG bytes
     */
    byte[] frame(int index);
}
