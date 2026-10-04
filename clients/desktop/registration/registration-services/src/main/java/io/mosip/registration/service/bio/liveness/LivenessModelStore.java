/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

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

import io.mosip.liveness.onnx.model.SemanticVersion;

/**
 * The folder that holds model bundles imported without a client upgrade (bonus B6), and the pointer to the active
 * one.
 *
 * <p>Layout: {@code <root>/<bundle>-<version>-<millis>/} holds the files of one verified bundle ({@code manifest.json},
 * {@code manifest.jws} and the model files); {@code <root>/active.properties} names the active folder, its version
 * and the <em>version floor</em>: the highest version ever activated. The engine never loads a bundle below the
 * floor, so switching back to an older signed bundle (rollback) is refused even when its signature is valid.
 *
 * <p>The pointer file is replaced atomically, so a crash leaves either the old or the new pointer. The store holds
 * only data that the engine verifies again at every load (signature, hashes, version); it is not a trust anchor.
 *
 * <p>Not thread-safe; {@link LivenessModelImporter} serialises imports.
 */
public final class LivenessModelStore {

	/** Name of the pointer file. */
	public static final String ACTIVE_FILE = "active.properties";
	static final String KEY_DIRECTORY = "directory";
	static final String KEY_VERSION = "version";
	static final String KEY_BUNDLE = "bundle";
	static final String KEY_FLOOR = "floor";
	static final String KEY_IMPORTED_AT = "importedAt";

	private final Path root;

	/**
	 * Creates a store.
	 *
	 * @param root store folder; created on the first install
	 */
	public LivenessModelStore(Path root) {
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
		Properties p = readPointer();
		String dir = p.getProperty(KEY_DIRECTORY);
		String version = p.getProperty(KEY_VERSION);
		if (dir == null || version == null) {
			return Optional.empty();
		}
		Path folder = root.resolve(dir).normalize();
		if (!folder.startsWith(root) || folder.equals(root) || !Files.isDirectory(folder)) {
			return Optional.empty();
		}
		return Optional.of(new Installed(folder, p.getProperty(KEY_BUNDLE, ""), version));
	}

	/**
	 * Returns the version floor: the highest bundle version ever activated from this store.
	 *
	 * @return the floor; empty when nothing was imported
	 */
	public Optional<String> floor() {
		String floor = readPointer().getProperty(KEY_FLOOR);
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
	 * Makes an installed folder the active bundle and raises the floor to its version.
	 *
	 * @param installed the folder, inside the store
	 * @return the previous pointer, for {@link #restore(Properties)}
	 * @throws IOException if the pointer cannot be written
	 */
	public Properties activate(Installed installed) throws IOException {
		Properties previous = readPointer();
		Properties next = new Properties();
		next.setProperty(KEY_DIRECTORY, root.relativize(installed.folder()).toString().replace('\\', '/'));
		next.setProperty(KEY_BUNDLE, installed.bundle());
		next.setProperty(KEY_VERSION, installed.version());
		next.setProperty(KEY_FLOOR, higher(previous.getProperty(KEY_FLOOR), installed.version()));
		next.setProperty(KEY_IMPORTED_AT, Instant.now().toString());
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
	 * Deletes a bundle folder of this store (a failed import); never the active one.
	 *
	 * @param folder folder inside the store
	 */
	public void discard(Path folder) {
		Path f = folder.toAbsolutePath().normalize();
		Optional<Installed> active = active();
		if (!f.startsWith(root) || f.equals(root) || (active.isPresent() && active.get().folder().equals(f))) {
			return;
		}
		deleteTree(f);
	}

	/**
	 * Deletes a folder and its content, ignoring failures.
	 *
	 * @param folder folder to delete
	 */
	static void deleteTree(Path folder) {
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
