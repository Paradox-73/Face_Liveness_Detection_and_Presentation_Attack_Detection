package io.mosip.liveness.onnx.runtime;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.onnx.TestSupport;
import io.mosip.liveness.onnx.model.ModelIds;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ONNX Runtime loads on the JVM running the build. Run with the Zulu 11.0.8 JDK (the runtime MOSIP ships with the
 * desktop client) to check that ONNX Runtime 1.20.0 works there.
 */
class OrtModelTest {

    @Test
    void runtimeLoadsAndRunsAModel() throws Exception {
        System.out.println("[runtime] ONNX Runtime " + OrtModel.runtimeVersion() + " on Java "
                + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ", "
                + System.getProperty("java.vm.version") + ") at " + System.getProperty("java.home"));
        assertEquals("1.20.0", OrtModel.runtimeVersion());
        byte[] bytes = TestSupport.registry().load(ModelIds.MINIFASNET_V2);
        try (OrtModel model = OrtModel.create(ModelIds.MINIFASNET_V2, bytes,
                OnnxRuntimeOptions.builder().intraOpThreads(1).interOpThreads(1).build())) {
            assertEquals("input", model.inputName());
            Map<String, float[]> out = model.run(new float[3 * 80 * 80], new long[] {1, 3, 80, 80});
            assertEquals(3, out.get(model.outputNames().get(0)).length);
            assertThrows(IllegalArgumentException.class, () -> model.run(new float[5], new long[] {1, 3, 80, 80}));
            EngineException e = assertThrows(EngineException.class,
                    () -> model.run(new float[3 * 8 * 8], new long[] {1, 3, 8, 8}));
            assertEquals(EngineErrorCode.INFERENCE, e.getErrorCode());
        }
    }

    @Test
    void garbageModelBytesFailToLoad() {
        EngineException e = assertThrows(EngineException.class,
                () -> OrtModel.create("junk", new byte[] {1, 2, 3}, OnnxRuntimeOptions.defaults()));
        assertEquals(EngineErrorCode.MODEL_LOAD, e.getErrorCode());
    }

    @Test
    void customizerIsApplied() throws Exception {
        boolean[] called = {false};
        OnnxRuntimeOptions options = OnnxRuntimeOptions.builder().customizer(o -> called[0] = true).build();
        try (OrtModel yunet = OrtModel.create(ModelIds.YUNET, TestSupport.registry().load(ModelIds.YUNET), options)) {
            assertEquals(true, called[0]);
            assertEquals(12, yunet.outputNames().size());
        }
        assertThrows(IllegalArgumentException.class, () -> OnnxRuntimeOptions.builder().intraOpThreads(0));
    }
}
