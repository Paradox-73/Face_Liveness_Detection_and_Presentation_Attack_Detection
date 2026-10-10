/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.service.bio.liveness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LivenessModelStoreTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void deletedOrBrokenPointerKeepsTheFloorAndTheNewestBundle() throws Exception {
		LivenessModelStore store = new LivenessModelStore(tmp.getRoot().toPath());
		Path older = store.newBundleFolder("liveness-models", "1.3.1");
		store.activate(new LivenessModelStore.Installed(older, "liveness-models", "1.3.1"));
		Path newer = store.newBundleFolder("liveness-models", "1.4.0");
		store.activate(new LivenessModelStore.Installed(newer, "liveness-models", "1.4.0"));

		Path pointer = store.root().resolve(LivenessModelStore.ACTIVE_FILE);
		Files.delete(pointer);
		assertEquals("1.4.0", store.floor().get());
		assertEquals(newer, store.active().get().folder());

		Files.write(pointer, "directory".getBytes(StandardCharsets.ISO_8859_1));
		assertEquals("1.4.0", store.floor().get());
		assertEquals("1.4.0", store.active().get().version());
	}

	@Test
	public void emptyStoreHasNoFloor() {
		LivenessModelStore store = new LivenessModelStore(tmp.getRoot().toPath().resolve("none"));
		assertFalse(store.floor().isPresent());
		assertFalse(store.active().isPresent());
	}
}
