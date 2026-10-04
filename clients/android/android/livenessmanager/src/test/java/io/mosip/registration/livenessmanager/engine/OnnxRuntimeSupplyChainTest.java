/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.registration.livenessmanager.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import io.mosip.registration.livenessmanager.BuildConfig;

/**
 * ONNX Runtime for Android is pinned to 1.20.0, the version the desktop client pins (ADR 0003), so both clients run
 * the models on the same runtime release; 1.29 and later would also add a telemetry initializer (design section
 * 4.20). The Gradle task {@code verifyNoOnnxTelemetry} checks the library manifests; this test checks the classes of
 * the onnxruntime-android jar that the app packs. Unit tests themselves run on the JVM package of the same version,
 * so the Gradle build passes that jar's path in the system property {@value #CLASSES_PROPERTY}.
 */
public class OnnxRuntimeSupplyChainTest {

    private static final String CLASSES_PROPERTY = "liveness.onnxruntimeAndroidClasses";

    @Test
    public void pinnedVersionIsUsed() {
        assertEquals("1.20.0", BuildConfig.ONNXRUNTIME_VERSION);
    }

    @Test
    public void onnxRuntimeClassesHoldNoTelemetryInitializer() throws Exception {
        String path = System.getProperty(CLASSES_PROPERTY);
        assertNotNull("the Gradle build sets " + CLASSES_PROPERTY, path);
        File jar = new File(path);
        assertTrue("ONNX Runtime for Android classes found at " + jar, jar.isFile());
        int classes = 0;
        try (JarFile file = new JarFile(jar)) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("ai/onnxruntime/") && name.endsWith(".class")) {
                    classes++;
                    assertFalse("telemetry class " + name, name.toLowerCase().contains("telemetry"));
                    assertFalse("initializer class " + name, name.contains("Initializer"));
                }
            }
        }
        assertTrue("expected the ONNX Runtime Java API, found " + classes + " classes", classes > 20);
    }
}
