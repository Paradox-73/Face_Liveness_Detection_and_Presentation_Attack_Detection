/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Finds frame packs under a root folder: pack {@code <id>} is the folder {@code <root>/<id>} holding
 * {@code .jpg}, {@code .jpeg} or {@code .png} files, played in file-name order.
 *
 * <p>Frame bytes are read from disk on each access; the operating system's file cache keeps repeated reads fast
 * and the runner's heap small. Frame packs never live in the repository; {@code tools/eval/fetch-frame-packs.py}
 * rebuilds them from the manifests in {@code eval/packs/}.
 */
final class FramePackStore {

    private static final Pattern PACK_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final Path root;
    private final Map<String, FrameSequence> packs = new HashMap<>();

    /**
     * Creates a store.
     *
     * @param root folder that holds one sub-folder per pack
     */
    FramePackStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Returns the root folder.
     *
     * @return the root
     */
    Path root() {
        return root;
    }

    /**
     * Opens a pack.
     *
     * @param id pack identifier: lower-case letters, digits, dot, dash and underscore
     * @return the pack
     * @throws IllegalArgumentException if the identifier is malformed, the folder is missing or holds no frames
     */
    FrameSequence pack(String id) {
        FrameSequence cached = packs.get(id);
        if (cached != null) {
            return cached;
        }
        if (id == null || !PACK_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("bad frame pack id: " + id);
        }
        Path dir = root.resolve(id);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("frame pack not found: " + dir
                    + " (run tools/eval/fetch-frame-packs.py and tools/eval/make-attack-packs.py)");
        }
        List<Path> files = listFrames(dir);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("frame pack has no .jpg/.png frames: " + dir);
        }
        FrameSequence pack = new DirectoryPack(id, files);
        packs.put(id, pack);
        return pack;
    }

    private static List<Path> listFrames(Path dir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(p) && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
                    files.add(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
        Collections.sort(files);
        return files;
    }

    /** A pack backed by files in one folder. */
    private static final class DirectoryPack implements FrameSequence {
        private final String id;
        private final List<Path> files;

        DirectoryPack(String id, List<Path> files) {
            this.id = id;
            this.files = files;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int size() {
            return files.size();
        }

        @Override
        public byte[] frame(int index) {
            try {
                return Files.readAllBytes(files.get(index));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + files.get(index), e);
            }
        }
    }
}
