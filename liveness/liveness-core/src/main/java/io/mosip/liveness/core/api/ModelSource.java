package io.mosip.liveness.core.api;

import java.io.IOException;

/**
 * Read access to the files of a model bundle ({@code manifest.json}, {@code manifest.jws} and the model files).
 *
 * <p>The desktop client reads them from the {@code liveness-onnx} jar on the class path; the Android client
 * from {@code assets/models/}. The engine verifies hashes and the bundle signature on the bytes it reads, so a
 * source does not need to be trusted.
 */
public interface ModelSource {

    /**
     * Reads a whole file of the bundle.
     *
     * @param path path relative to the bundle root, with {@code /} separators, for example {@code "manifest.json"}
     * @return the file bytes
     * @throws java.io.FileNotFoundException if the file does not exist
     * @throws IOException                   if it cannot be read
     */
    byte[] read(String path) throws IOException;
}
