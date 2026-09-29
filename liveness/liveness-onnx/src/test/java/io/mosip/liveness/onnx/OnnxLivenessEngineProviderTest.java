package io.mosip.liveness.onnx;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineContext;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessEngineProvider;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.onnx.model.ModelSources;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Discovery of the default engine through {@link ServiceLoader} and creation from an {@link EngineContext}. */
class OnnxLivenessEngineProviderTest {

    @Test
    void serviceLoaderFindsTheDefaultEngine() {
        List<LivenessEngineProvider> found = new ArrayList<>();
        for (LivenessEngineProvider p : ServiceLoader.load(LivenessEngineProvider.class)) {
            found.add(p);
        }
        assertEquals(1, found.size(), found.toString());
        assertInstanceOf(OnnxLivenessEngineProvider.class, found.get(0));
        assertEquals(LivenessConfigKeys.DEFAULT_ENGINE, found.get(0).engineId());
    }

    @Test
    void createsAReadyEngineFromTheContext() throws Exception {
        LivenessEngine engine = new OnnxLivenessEngineProvider().create(context("1.0.0"));
        try {
            assertInstanceOf(OnnxLivenessEngine.class, engine);
            assertTrue(engine.isReady());
            assertEquals("mosip-onnx-default@1.0.0", engine.info().tag());
            assertEquals(5, engine.info().models().size());
        } finally {
            engine.close();
        }
        assertFalse(engine.isReady());
    }

    @Test
    void appliesTheConfiguredMinimumBundleVersion() {
        EngineException tooOld = assertThrows(EngineException.class,
                () -> new OnnxLivenessEngineProvider().create(context("1.1.0")));
        assertEquals(EngineErrorCode.MODEL_VERSION, tooOld.getErrorCode());
        EngineException invalid = assertThrows(EngineException.class,
                () -> new OnnxLivenessEngineProvider().create(context("one")));
        assertEquals(EngineErrorCode.CONFIG_INVALID, invalid.getErrorCode());
    }

    private static EngineContext context(String minimumVersion) {
        return EngineContext.builder()
                .config(ConfigSource.of(Collections.singletonMap(LivenessConfigKeys.MODEL_MIN_VERSION, minimumVersion)))
                .models(ModelSources.directory(TestSupport.modelsDir())).imageDecoder(new TestImageDecoder()).build();
    }
}
