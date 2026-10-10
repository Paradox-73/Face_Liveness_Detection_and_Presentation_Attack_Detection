/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mosip.liveness.onnx.model.SemanticVersion;

/**
 * The folder that holds model bundles imported without a client update (bonus B6), and the pointer to the active
 * one. Used by both clients.
 *
 * <p>Layout: {@code <root>/<bundle>-<version>-<millis>/} holds the files of one verified bundle
 * ({@code manifest.json}, {@code manifest.jws} and the model files); {@code <root>/active.properties} names the
 * active folder, its version, the folder that was active before it (kept for rollback) and the <em>version
 * floor</em>: the highest version ever activated. The engine never loads a bundle below the floor, so switching
 * back to an older signed bundle is refused even when its signature is valid.
 *
 * <p>The pointer file is replaced atomically, so a crash leaves either the old or the new pointer. The store holds
 * only data that the engine verifies again at every load (signature, hashes, version); it is not a trust anchor.
 * It lives in a folder only the client can write (the app's private files folder on Android). A missing or
 * unreadable pointer is rebuilt from the bundle folders, so deleting it neither drops the floor nor falls back to
 * the shipped bundle.
 *
 * <p>Not thread-safe; {@link ModelBundleImporter} serialises imports.
 */
public final class ModelBundleStore {

    /** Name of the pointer file. */
    public static final String ACTIVE_FILE = "active.properties";
    static final String KEY_DIRECTORY = "directory";
    static final String KEY_VERSION = "version";
    static final String KEY_BUNDLE = "bundle";
    static final String KEY_FLOOR = "floor";
    static final String KEY_IMPORTED_AT = "importedAt";
    static final String KEY_PREVIOUS_DIRECTORY = "previous.directory";
    static final String KEY_PREVIOUS_VERSION = "previous.version";
    static final String KEY_PREVIOUS_BUNDLE = "previous.bundle";

    /** Folder name written by {@link #newBundleFolder}: {@code <bundle>-<version>-<epoch ms>}. */
    private static final Pattern FOLDER = Pattern.compile("(.+)-(\\d+\\.\\d+\\.\\d+)-\\d+");

    private final Path root;

    /**
     * Creates a store.
     *
     * @param root store folder; created on the first install
     */
    public ModelBundleStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /**
     * Returns the store folder.
     *
     * @return absolute path
     */
    public Path root() {
        return root;
    }

    /**
     * Returns the active imported bundle.
     *
     * @return the bundle; empty when nothing was imported or the pointer is unreadable or points outside the store
     */
    public Optional<Installed> active() {
        Properties p = pointer();
        return installed(p.getProperty(KEY_DIRECTORY), p.getProperty(KEY_BUNDLE, ""), p.getProperty(KEY_VERSION));
    }

    /**
     * Returns the imported bundle that was active before the active one. Its folder is kept, so the pointer can be
     * put back if the new bundle cannot start.
     *
     * @return the bundle; empty when the app's built-in bundle ran before, or the folder is gone
     */
    public Optional<Installed> previous() {
        Properties p = readPointer();
        return installed(p.getProperty(KEY_PREVIOUS_DIRECTORY), p.getProperty(KEY_PREVIOUS_BUNDLE, ""),
                p.getProperty(KEY_PREVIOUS_VERSION));
    }

    /**
     * Returns the version floor: the highest bundle version ever activated from this store.
     *
     * @return the floor; empty when nothing was imported
     */
    public Optional<String> floor() {
        String floor = pointer().getProperty(KEY_FLOOR);
        if (floor == null) {
            return Optional.empty();
        }
        try {
            SemanticVersion.parse(floor);
            return Optional.of(floor);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the lowest bundle version the engine may load: the configured {@code model.min_version} or the
     * floor, whichever is higher (anti-rollback).
     *
     * @param configured the configured minimum ({@code MAJOR.MINOR.PATCH})
     * @return the minimum; {@code configured} when it cannot be compared (the engine builder reports it)
     */
    public String minimumVersion(String configured) {
        Optional<String> floor = floor();
        if (!floor.isPresent()) {
            return configured;
        }
        try {
            return SemanticVersion.parse(floor.get()).compareTo(SemanticVersion.parse(configured.trim())) > 0
                    ? floor.get() : configured;
        } catch (IllegalArgumentException e) {
            return configured;
        }
    }

    /**
     * Creates a new, empty folder for a bundle.
     *
     * @param bundle  bundle name from the verified manifest
     * @param version bundle version from the verified manifest
     * @return the folder
     * @throws IOException if it cannot be created
     */
    public Path newBundleFolder(String bundle, String version) throws IOException {
        Files.createDirectories(root);
        String safe = (bundle + "-" + version).replaceAll("[^A-Za-z0-9._-]", "_");
        Path folder = root.resolve(safe + "-" + System.currentTimeMillis());
        Files.createDirectory(folder);
        return folder;
    }

    /**
     * Makes an installed folder the active bundle, records the active one as the previous bundle and raises the
     * floor to the new version.
     *
     * @param installed the folder, inside the store
     * @return the previous pointer, for {@link #restore(Properties)}
     * @throws IOException if the pointer cannot be written
     */
    public Properties activate(Installed installed) throws IOException {
        Properties previous = readPointer();
        Properties next = new Properties();
        next.setProperty(KEY_DIRECTORY, relative(installed.folder()));
        next.setProperty(KEY_BUNDLE, installed.bundle());
        next.setProperty(KEY_VERSION, installed.version());
        next.setProperty(KEY_FLOOR, higher(previous.getProperty(KEY_FLOOR), installed.version()));
        next.setProperty(KEY_IMPORTED_AT, Instant.now().toString());
        Optional<Installed> before = active();
        if (before.isPresent()) {
            next.setProperty(KEY_PREVIOUS_DIRECTORY, relative(before.get().folder()));
            next.setProperty(KEY_PREVIOUS_BUNDLE, before.get().bundle());
            next.setProperty(KEY_PREVIOUS_VERSION, before.get().version());
        }
        writePointer(next);
        return previous;
    }

    /**
     * Puts back a pointer returned by {@link #activate(Installed)} (the new bundle could not be started).
     *
     * @param previous the previous pointer; empty removes the pointer
     * @throws IOException if the pointer cannot be written
     */
    public void restore(Properties previous) throws IOException {
        if (previous == null || previous.isEmpty()) {
            Files.deleteIfExists(root.resolve(ACTIVE_FILE));
            return;
        }
        writePointer(previous);
    }

    /**
     * Deletes a bundle folder of this store (a failed import); never the active or the previous one.
     *
     * @param folder folder inside the store
     */
    public void discard(Path folder) {
        Path f = folder.toAbsolutePath().normalize();
        if (!f.startsWith(root) || f.equals(root) || isKept(f)) {
            return;
        }
        deleteTree(f);
    }

    /** Deletes every bundle folder except the active and the previous one (older imports). */
    public void prune() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            for (Path child : children) {
                if (Files.isDirectory(child) && !isKept(child.toAbsolutePath().normalize())) {
                    deleteTree(child);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // Leftovers are harmless: the engine loads only the folder the pointer names.
        }
    }

    /**
     * Deletes a folder and its content, ignoring failures.
     *
     * @param folder folder to delete
     */
    public static void deleteTree(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    deleteTree(child);
                } else {
                    Files.deleteIfExists(child);
                }
            }
            Files.deleteIfExists(folder);
        } catch (IOException | UncheckedIOException e) {
            // Leftovers are harmless: the engine loads only the folder the pointer names.
        }
    }

    /** Kept: the folders the pointer file names. Not the rebuilt pointer, which would keep a failed import. */
    private boolean isKept(Path folder) {
        Properties p = readPointer();
        for (String dir : new String[] {p.getProperty(KEY_DIRECTORY), p.getProperty(KEY_PREVIOUS_DIRECTORY)}) {
            if (dir != null && root.resolve(dir).normalize().equals(folder)) {
                return true;
            }
        }
        return false;
    }

    private Optional<Installed> installed(String dir, String bundle, String version) {
        if (dir == null || version == null) {
            return Optional.empty();
        }
        Path folder = root.resolve(dir).normalize();
        if (!folder.startsWith(root) || folder.equals(root) || !Files.isDirectory(folder)) {
            return Optional.empty();
        }
        return Optional.of(new Installed(folder, bundle, version));
    }

    private String relative(Path folder) {
        return root.relativize(folder).toString().replace('\\', '/');
    }

    private static String higher(String floor, String version) {
        if (floor == null) {
            return version;
        }
        try {
            return SemanticVersion.parse(floor).compareTo(SemanticVersion.parse(version)) > 0 ? floor : version;
        } catch (IllegalArgumentException e) {
            return version;
        }
    }

    /** The pointer file, or the pointer rebuilt from the bundle folders when the file is missing or unreadable. */
    private Properties pointer() {
        Properties p = readPointer();
        boolean usable = !p.getProperty(KEY_DIRECTORY, "").isEmpty() && !p.getProperty(KEY_VERSION, "").isEmpty();
        return usable ? p : recoverPointer();
    }

    /**
     * Rebuilds a missing or unreadable pointer from the bundle folders: the highest version is active and is the
     * floor. The engine still verifies the folder at load and fails closed if it is not a valid bundle.
     */
    private Properties recoverPointer() {
        Properties p = new Properties();
        String bestDir = null;
        SemanticVersion best = null;
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (Path child : children) {
                    Matcher m = FOLDER.matcher(child.getFileName().toString());
                    if (!m.matches()) {
                        continue;
                    }
                    SemanticVersion v = SemanticVersion.parse(m.group(2));
                    if (best == null || v.compareTo(best) > 0) {
                        best = v;
                        bestDir = child.getFileName().toString();
                        p.setProperty(KEY_BUNDLE, m.group(1));
                    }
                }
            } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
                return new Properties();
            }
        }
        if (bestDir != null) {
            p.setProperty(KEY_DIRECTORY, bestDir);
            p.setProperty(KEY_VERSION, best.toString());
            p.setProperty(KEY_FLOOR, best.toString());
        }
        return p;
    }

    private Properties readPointer() {
        Properties p = new Properties();
        Path file = root.resolve(ACTIVE_FILE);
        if (!Files.isRegularFile(file)) {
            return p;
        }
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return new Properties();
        }
        return p;
    }

    private void writePointer(Properties p) throws IOException {
        Files.createDirectories(root);
        Path tmp = root.resolve(ACTIVE_FILE + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "Active face liveness model bundle (written by the model import)");
        }
        try {
            Files.move(tmp, root.resolve(ACTIVE_FILE), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, root.resolve(ACTIVE_FILE), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** One bundle folder of the store. */
    public static final class Installed {

        private final Path folder;
        private final String bundle;
        private final String version;

        /**
         * Creates the description.
         *
         * @param folder  bundle folder
         * @param bundle  bundle name
         * @param version bundle version
         */
        public Installed(Path folder, String bundle, String version) {
            this.folder = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
            this.bundle = Objects.requireNonNull(bundle, "bundle");
            this.version = Objects.requireNonNull(version, "version");
        }

        /**
         * Returns the folder.
         *
         * @return absolute folder
         */
        public Path folder() {
            return folder;
        }

        /**
         * Returns the bundle name.
         *
         * @return name from the manifest
         */
        public String bundle() {
            return bundle;
        }

        /**
         * Returns the bundle version.
         *
         * @return version from the manifest
         */
        public String version() {
            return version;
        }
    }
}
