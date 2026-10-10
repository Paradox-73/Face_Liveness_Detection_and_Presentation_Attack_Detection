/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelBundleStoreTest {

    @TempDir
    Path tmp;

    @Test
    void deletedOrBrokenPointerKeepsTheFloorAndTheNewestBundle() throws Exception {
        ModelBundleStore store = new ModelBundleStore(tmp);
        Path older = store.newBundleFolder("liveness-models", "1.3.1");
        store.activate(new ModelBundleStore.Installed(older, "liveness-models", "1.3.1"));
        Path newer = store.newBundleFolder("liveness-models", "1.4.0");
        store.activate(new ModelBundleStore.Installed(newer, "liveness-models", "1.4.0"));

        Path pointer = store.root().resolve(ModelBundleStore.ACTIVE_FILE);
        Files.delete(pointer);
        assertEquals("1.4.0", store.floor().get());
        assertEquals(newer, store.active().get().folder());

        Files.write(pointer, "directory".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("1.4.0", store.floor().get());
        assertEquals("1.4.0", store.active().get().version());
    }

    @Test
    void emptyStoreHasNoFloor() {
        ModelBundleStore store = new ModelBundleStore(tmp.resolve("none"));
        assertFalse(store.floor().isPresent());
        assertFalse(store.active().isPresent());
        assertEquals("1.3.0", store.minimumVersion("1.3.0"));
    }

    @Test
    void pruneKeepsOnlyTheActiveAndThePreviousBundle() throws Exception {
        ModelBundleStore store = new ModelBundleStore(tmp);
        Path first = store.newBundleFolder("m", "1.1.0");
        store.activate(new ModelBundleStore.Installed(first, "m", "1.1.0"));
        Path second = store.newBundleFolder("m", "1.2.0");
        store.activate(new ModelBundleStore.Installed(second, "m", "1.2.0"));
        Path third = store.newBundleFolder("m", "1.3.0");
        store.activate(new ModelBundleStore.Installed(third, "m", "1.3.0"));

        store.prune();

        assertFalse(Files.exists(first));
        assertTrue(Files.isDirectory(second));
        assertEquals(second, store.previous().get().folder());
        assertEquals(third, store.active().get().folder());
        assertEquals("1.3.0", store.minimumVersion("1.0.0"), "the floor wins over a lower configured minimum");
    }
}
