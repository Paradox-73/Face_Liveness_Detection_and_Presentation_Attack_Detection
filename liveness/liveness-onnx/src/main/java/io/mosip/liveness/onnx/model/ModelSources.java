/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.model;

import io.mosip.liveness.core.api.ModelSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ready-made {@link ModelSource}s: the bundle inside a jar on the class path (desktop) or in a directory
 * (development, tests, an unpacked Android asset folder). Paths are checked so a manifest cannot point outside
 * the bundle.
 */
public final class ModelSources {

    /** Class-path folder of the bundle inside {@code liveness-onnx-<version>-models.jar}. */
    public static final String CLASSPATH_ROOT = "io/mosip/liveness/onnx/models/";

    private static final Pattern SAFE_PATH =
            Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*(/[A-Za-z0-9_][A-Za-z0-9._-]*)*");
    private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;

    private ModelSources() {
    }

    /**
     * Reads the bundle from the class path under {@value #CLASSPATH_ROOT}.
     *
     * @param loader class loader that sees the models jar
     * @return the source
     */
    public static ModelSource classpath(ClassLoader loader) {
        return classpath(loader, CLASSPATH_ROOT);
    }

    /**
     * Reads the bundle from a class-path folder.
     *
     * @param loader class loader
     * @param root   folder ending with {@code /}, for example {@code "io/mosip/liveness/onnx/models/"}
     * @return the source
     */
    public static ModelSource classpath(ClassLoader loader, String root) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(root, "root");
        return path -> {
            String resource = root + checkPath(path);
            InputStream in = loader.getResourceAsStream(resource);
            if (in == null) {
                throw new FileNotFoundException("class-path resource not found: " + resource);
            }
            try (InputStream stream = in) {
                return readAll(stream);
            }
        };
    }

    /**
     * Reads the bundle from a directory.
     *
     * @param directory bundle root
     * @return the source
     */
    public static ModelSource directory(File directory) {
        Objects.requireNonNull(directory, "directory");
        return path -> {
            File file = new File(directory, checkPath(path));
            if (!file.isFile()) {
                throw new FileNotFoundException("file not found: " + file.getPath());
            }
            try (InputStream in = new FileInputStream(file)) {
                return readAll(in);
            }
        };
    }

    /**
     * Checks that a bundle path is relative, uses {@code /} and has no {@code ..} segment.
     *
     * @param path path relative to the bundle root
     * @return the same path
     * @throws IOException if the path is unsafe
     */
    static String checkPath(String path) throws IOException {
        if (path == null || !SAFE_PATH.matcher(path).matches() || path.contains("..")) {
            throw new IOException("unsafe bundle path: " + path);
        }
        return path;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > MAX_FILE_BYTES) {
                throw new IOException("bundle file larger than " + MAX_FILE_BYTES + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
