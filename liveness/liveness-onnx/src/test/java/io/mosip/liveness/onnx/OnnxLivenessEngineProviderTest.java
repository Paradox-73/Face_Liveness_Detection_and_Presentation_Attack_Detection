/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineContext;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessEngineProvider;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.config.PolicyKey;
import io.mosip.liveness.core.engine.EngineParameter;
import io.mosip.liveness.onnx.model.ModelSources;
import io.mosip.liveness.onnx.model.ModelTrustAnchors;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        assertEquals(PolicyKey.ENGINE.defaultValue(), found.get(0).engineId());
    }

    @Test
    void createsAReadyEngineFromTheContext() throws Exception {
        LivenessEngine engine = new OnnxLivenessEngineProvider().create(context("1.3.0"));
        try {
            assertInstanceOf(OnnxLivenessEngine.class, engine);
            assertTrue(engine.isReady());
            assertEquals("mosip-onnx-default@1.0.0", engine.info().tag());
            assertEquals(5, engine.info().models().size());
            // Signed engineParams of the repository bundle: the long loop window and the analyser's sharpness scale.
            OnnxLivenessEngine onnx = (OnnxLivenessEngine) engine;
            assertEquals(300, onnx.parameters().getInt(EngineParameter.LOOP_WINDOW_FRAMES));
            assertEquals(40, onnx.parameters().get(EngineParameter.MIN_SHARPNESS), 0);
        } finally {
            engine.close();
        }
        assertFalse(engine.isReady());
    }

    @Test
    void appliesTheConfiguredMinimumBundleVersion() {
        EngineException tooOld = assertThrows(EngineException.class,
                () -> new OnnxLivenessEngineProvider().create(context("1.4.0")));
        assertEquals(EngineErrorCode.MODEL_VERSION, tooOld.getErrorCode());
    }

    @Test
    void invalidMinimumVersionKeepsTheDefaultInsteadOfStoppingTheEngine() throws Exception {
        LivenessEngine engine = new OnnxLivenessEngineProvider().create(context("one"));
        engine.close();
    }

    /** A production key replaces the development key: the development-signed test bundle is then refused. */
    @Test
    void configuredTrustAnchorReplacesTheDevelopmentKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        Map<String, String> config = new HashMap<>();
        config.put(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM, ModelTrustAnchors.toPem(g.generateKeyPair().getPublic()));
        EngineException refused = assertThrows(EngineException.class,
                () -> new OnnxLivenessEngineProvider().create(context(config)));
        assertTrue(refused.getErrorCode().isModelIntegrityFailure(), refused.getErrorCode().name());

        config.put(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM, ModelTrustAnchors.DEVELOPMENT_KEY_PEM.replace("\n", "\\n"));
        new OnnxLivenessEngineProvider().create(context(config)).close();

        config.put(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM, "not a key");
        EngineException invalid = assertThrows(EngineException.class,
                () -> new OnnxLivenessEngineProvider().create(context(config)));
        assertEquals(EngineErrorCode.CONFIG_INVALID, invalid.getErrorCode());
    }

    /**
     * A fingerprint picks a key shipped with the client and fits the desktop client's 512-character global-parameter
     * column, which the PEM RSA 3072 key does not (the desktop config sync failed on it); a fingerprint of no shipped
     * key trusts nothing.
     */
    @Test
    void fingerprintPicksTheShippedKeyAndFitsTheDesktopColumn() throws Exception {
        String fp = ModelTrustAnchors.fingerprint(ModelTrustAnchors.development());
        assertEquals("sha256:99538367853153c2969000626bcd6bdd28e7267d0649c0057662f85ee23e8b80", fp);
        assertTrue(fp.length() <= 512 && ModelTrustAnchors.DEVELOPMENT_KEY_PEM.length() > 512);
        Map<String, String> config = new HashMap<>();
        config.put(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM, fp.toUpperCase().replace("SHA256:", "sha256:"));
        new OnnxLivenessEngineProvider().create(context(config)).close();

        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        config.put(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM,
                ModelTrustAnchors.fingerprint(g.generateKeyPair().getPublic()));
        EngineException unknown = assertThrows(EngineException.class,
                () -> new OnnxLivenessEngineProvider().create(context(config)));
        assertEquals(EngineErrorCode.CONFIG_INVALID, unknown.getErrorCode());

        config.put(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM, "sha256:1234");
        assertEquals(EngineErrorCode.CONFIG_INVALID, assertThrows(EngineException.class,
                () -> new OnnxLivenessEngineProvider().create(context(config))).getErrorCode());
    }

    /** No configured key: nothing is trusted and no model loads, not even the development-signed bundle. */
    @Test
    void unsetTrustAnchorLoadsNoModel() {
        EngineException refused = assertThrows(EngineException.class, () -> new OnnxLivenessEngineProvider()
                .create(context(Collections.singletonMap(LivenessConfigKeys.MODEL_MIN_VERSION, "1.3.0"))));
        assertEquals(EngineErrorCode.CONFIG_INVALID, refused.getErrorCode());
        assertTrue(refused.getMessage().contains(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM), refused.getMessage());
    }

    private static EngineContext context(String minimumVersion) {
        Map<String, String> config = new HashMap<>();
        config.put(LivenessConfigKeys.MODEL_MIN_VERSION, minimumVersion);
        config.put(LivenessConfigKeys.MODEL_TRUST_ANCHOR_PEM, ModelTrustAnchors.DEVELOPMENT_KEY_PEM);
        return context(config);
    }

    private static EngineContext context(Map<String, String> config) {
        return EngineContext.builder().config(ConfigSource.of(config))
                .models(ModelSources.directory(TestSupport.modelsDir())).imageDecoder(new TestImageDecoder()).build();
    }
}
