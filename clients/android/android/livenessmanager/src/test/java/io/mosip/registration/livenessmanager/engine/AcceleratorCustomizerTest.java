package io.mosip.registration.livenessmanager.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Execution provider selection and CPU fallback. ONNX Runtime itself is replaced by a fake, so the test runs on
 * the JVM; the real providers were exercised on the emulator (docs/test-report/android-e2e.md).
 */
public class AcceleratorCustomizerTest {

    private final List<AcceleratorCustomizer.Outcome> outcomes = new ArrayList<>();
    private final List<Accelerator> added = new ArrayList<>();

    @Test
    public void parsesConfigurationValues() {
        assertEquals(Accelerator.CPU, Accelerator.parse(null));
        assertEquals(Accelerator.CPU, Accelerator.parse(" "));
        assertEquals(Accelerator.CPU, Accelerator.parse("gpu"));
        assertEquals(Accelerator.XNNPACK, Accelerator.parse(" xnnpack "));
        assertEquals(Accelerator.NNAPI, Accelerator.parse("NNAPI"));
        assertEquals("mosip.registration.liveness.engine.execution_provider", Accelerator.CONFIG_KEY);
    }

    @Test
    public void cpuAddsNothing() throws OrtException {
        customizer(Accelerator.CPU, providers(Collections.singleton("XNNPACK"), false)).customize(null);
        assertEquals(Collections.singletonList(AcceleratorCustomizer.Outcome.NOT_REQUESTED), outcomes);
        assertTrue(added.isEmpty());
    }

    @Test
    public void availableProviderIsAdded() throws OrtException {
        customizer(Accelerator.XNNPACK, providers(new HashSet<>(java.util.Arrays.asList("CPU", "XNNPACK")), false))
                .customize(null);
        assertEquals(Collections.singletonList(AcceleratorCustomizer.Outcome.ENABLED), outcomes);
        assertEquals(Collections.singletonList(Accelerator.XNNPACK), added);
    }

    @Test
    public void missingProviderFallsBackToCpu() throws OrtException {
        customizer(Accelerator.NNAPI, providers(Collections.singleton("CPU"), false)).customize(null);
        assertEquals(Collections.singletonList(AcceleratorCustomizer.Outcome.UNAVAILABLE), outcomes);
        assertTrue(added.isEmpty());
    }

    @Test
    public void rejectingProviderFallsBackToCpuWithoutFailingTheSession() throws OrtException {
        customizer(Accelerator.NNAPI, providers(Collections.singleton("NNAPI"), true)).customize(null);
        assertEquals(Collections.singletonList(AcceleratorCustomizer.Outcome.REJECTED), outcomes);
    }

    @Test
    public void runtimeWithoutProviderListFallsBackToCpu() throws OrtException {
        AcceleratorCustomizer.ExecutionProviders broken = new AcceleratorCustomizer.ExecutionProviders() {
            @Override
            public Set<String> available() {
                throw new UnsatisfiedLinkError("no onnxruntime");
            }

            @Override
            public void add(OrtSession.SessionOptions options, Accelerator accelerator, int threads) {
                added.add(accelerator);
            }
        };
        customizer(Accelerator.XNNPACK, broken).customize(null);
        assertEquals(Collections.singletonList(AcceleratorCustomizer.Outcome.UNAVAILABLE), outcomes);
    }

    @Test
    public void statusDescribesWhatRuns() {
        assertEquals("CPU", EngineLoader.describe(Accelerator.CPU,
                EnumSet.of(AcceleratorCustomizer.Outcome.NOT_REQUESTED)));
        assertEquals("XNNPACK", EngineLoader.describe(Accelerator.XNNPACK,
                EnumSet.of(AcceleratorCustomizer.Outcome.ENABLED)));
        assertEquals("XNNPACK (partly CPU)", EngineLoader.describe(Accelerator.XNNPACK,
                EnumSet.of(AcceleratorCustomizer.Outcome.ENABLED, AcceleratorCustomizer.Outcome.REJECTED)));
        assertEquals("CPU (NNAPI unavailable)", EngineLoader.describe(Accelerator.NNAPI,
                EnumSet.of(AcceleratorCustomizer.Outcome.UNAVAILABLE)));
        assertEquals("CPU (NNAPI rejected)", EngineLoader.describe(Accelerator.NNAPI,
                EnumSet.of(AcceleratorCustomizer.Outcome.REJECTED)));
    }

    private AcceleratorCustomizer customizer(Accelerator accelerator, AcceleratorCustomizer.ExecutionProviders p) {
        return new AcceleratorCustomizer(accelerator, 2, p, outcomes::add);
    }

    private AcceleratorCustomizer.ExecutionProviders providers(Set<String> available, boolean reject) {
        return new AcceleratorCustomizer.ExecutionProviders() {
            @Override
            public Set<String> available() {
                return available;
            }

            @Override
            public void add(OrtSession.SessionOptions options, Accelerator accelerator, int threads)
                    throws OrtException {
                if (reject) {
                    throw new OrtException("provider rejected the options");
                }
                added.add(accelerator);
            }
        };
    }
}
