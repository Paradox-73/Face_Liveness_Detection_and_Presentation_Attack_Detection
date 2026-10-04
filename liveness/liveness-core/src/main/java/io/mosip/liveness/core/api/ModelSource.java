/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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
