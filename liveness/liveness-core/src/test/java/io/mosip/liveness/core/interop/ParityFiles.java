/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.core.interop;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the parity file and the frames its cases stream.
 *
 * <ul>
 *   <li>Parity file: {@value #GOLDEN_PATH}, found by walking up from the working directory (each client's test runs
 *       in its own module folder).</li>
 *   <li>Committed frames: {@code faces/<file>} under {@value #FACES_PATH} (the MockMDS images, MPL-2.0).</li>
 *   <li>Frame packs: the system property {@value #PACKS_PROPERTY}, else the environment variable
 *       {@value #PACKS_ENV}, else {@code ../run/frame-packs} next to the repository (docs/eval/README.md).</li>
 * </ul>
 *
 * <p>On a device there is no repository: {@link #committedOnly(Reader)} reads the committed images through the
 * caller (for example from the test APK's assets) and treats every frame-pack case as unavailable.
 */
public final class ParityFiles {

    /** Parity file, relative to the repository root. */
    public static final String GOLDEN_PATH = "eval/interop/liveness-parity.json";
    /** Folder of the committed images, relative to the repository root. */
    public static final String FACES_PATH = "liveness/liveness-onnx/src/test/resources";
    /** System property naming the frame-pack folder. */
    public static final String PACKS_PROPERTY = "liveness.framePacks";
    /** Environment variable naming the frame-pack folder. */
    public static final String PACKS_ENV = "LIVENESS_FRAME_PACKS";

    /** Reads a committed image. */
    @FunctionalInterface
    public interface Reader {

        /**
         * Reads one committed image.
         *
         * @param ref the reference, {@code faces/<file>}
         * @return the file bytes
         * @throws IOException if it cannot be read
         */
        byte[] read(String ref) throws IOException;
    }

    private final File root;
    private final File packs;
    private final Reader committed;
    private final Map<String, List<byte[]>> cache = new HashMap<>();

    private ParityFiles(File root, File packs, Reader committed) {
        this.root = root;
        this.packs = packs;
        this.committed = committed;
    }

    /**
     * Creates locations without a repository: committed images come from the reader, frame packs are unavailable.
     *
     * @param reader reads {@code faces/<file>} references
     * @return the locations
     */
    public static ParityFiles committedOnly(Reader reader) {
        return new ParityFiles(null, null, reader);
    }

    /**
     * Locates the repository from the working directory.
     *
     * @return the locations
     * @throws IllegalStateException if no parent folder holds the parity file
     */
    public static ParityFiles locate() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null && !new File(dir, GOLDEN_PATH).isFile()) {
            dir = dir.getParentFile();
        }
        if (dir == null) {
            throw new IllegalStateException(GOLDEN_PATH + " not found above " + System.getProperty("user.dir"));
        }
        String configured = System.getProperty(PACKS_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv(PACKS_ENV);
        }
        File packs = configured != null && !configured.trim().isEmpty() ? new File(configured.trim())
                : new File(dir.getParentFile(), "run/frame-packs");
        File faces = new File(dir, FACES_PATH);
        return new ParityFiles(dir, packs, ref -> Files.readAllBytes(new File(faces, ref).toPath()));
    }

    /**
     * Returns the parity file.
     *
     * @return {@value #GOLDEN_PATH} under the repository root
     * @throws IllegalStateException for {@link #committedOnly(Reader)} locations
     */
    public File golden() {
        if (root == null) {
            throw new IllegalStateException("no repository: the caller reads the parity file itself");
        }
        return new File(root, GOLDEN_PATH);
    }

    /**
     * Tells whether the frames of a case can be read here.
     *
     * @param c the case
     * @return {@code true} for committed cases, and for pack cases whose packs are all present
     */
    public boolean available(ParityCase c) {
        if (c.isCommitted()) {
            return true;
        }
        if (packs == null) {
            return false;
        }
        List<String> ids = new ArrayList<>(c.actions().values());
        ids.add(c.main());
        for (String id : ids) {
            if (!new File(packs, id).isDirectory()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the frame-pack folder.
     *
     * @return the configured or default folder (it may not exist); {@code null} without a repository
     */
    public File packs() {
        return packs;
    }

    /**
     * Reads the frames of a reference: one committed image ({@code faces/<file>}) or every JPEG and PNG file of a
     * frame pack in name order.
     *
     * @param ref the reference
     * @return the encoded frames, shared between calls; callers must not change them
     * @throws UncheckedIOException if a file cannot be read or the pack is empty
     */
    public synchronized List<byte[]> frames(String ref) {
        return cache.computeIfAbsent(ref, this::read);
    }

    private List<byte[]> read(String ref) {
        try {
            if (ref.startsWith("faces/")) {
                return Collections.singletonList(committed.read(ref));
            }
            File dir = new File(packs, ref);
            File[] files = dir.listFiles((d, name) -> {
                String n = name.toLowerCase(Locale.ROOT);
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
            });
            if (files == null || files.length == 0) {
                throw new IOException("no frames in " + dir);
            }
            Arrays.sort(files);
            List<byte[]> out = new ArrayList<>(files.length);
            for (File f : files) {
                out.add(Files.readAllBytes(f.toPath()));
            }
            return Collections.unmodifiableList(out);
        } catch (IOException e) {
            throw new UncheckedIOException("frames of " + ref, e);
        }
    }
}
