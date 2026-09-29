package io.mosip.registration.livenessmanager.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import io.mosip.registration.livenessmanager.BuildConfig;

/**
 * ONNX Runtime for Android stays on 1.28.0, the last version without the telemetry initializer (design section
 * 4.20). The Gradle task {@code verifyNoOnnxTelemetry} checks the library manifests; this test checks the
 * classes that are actually on the class path.
 */
public class OnnxRuntimeSupplyChainTest {

    @Test
    public void pinnedVersionIsUsed() {
        assertEquals("1.28.0", BuildConfig.ONNXRUNTIME_VERSION);
    }

    @Test
    public void onnxRuntimeClassesHoldNoTelemetryInitializer() throws Exception {
        URL location = ai.onnxruntime.OrtEnvironment.class.getProtectionDomain().getCodeSource().getLocation();
        File jar = new File(location.toURI());
        assertTrue("ONNX Runtime classes found at " + jar, jar.isFile());
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
