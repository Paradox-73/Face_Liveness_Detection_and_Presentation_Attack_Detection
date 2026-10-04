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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineContext;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessEngineProvider;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.onnx.OnnxLivenessEngineProvider;

@RunWith(RobolectricTestRunner.class)
public class EngineLoaderTest {

    @Test
    public void loadsOnceAndReturnsTheSameEngine() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LivenessEngine engine = new StubEngine();
        EngineLoader loader = new EngineLoader(() -> {
            calls.incrementAndGet();
            return engine;
        });
        loader.preload();
        assertSame(engine, loader.await(5_000));
        assertSame(engine, loader.await(5_000));
        assertTrue(loader.isReady());
        assertEquals(1, calls.get());
    }

    @Test
    public void modelIntegrityFailureIsKeptUntilRestart() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EngineLoader loader = new EngineLoader(() -> {
            calls.incrementAndGet();
            throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "hash mismatch");
        });
        for (int i = 0; i < 3; i++) {
            try {
                loader.await(5_000);
                fail("expected EngineException");
            } catch (EngineException e) {
                assertEquals(EngineErrorCode.MODEL_INTEGRITY, e.getErrorCode());
            }
        }
        assertEquals("fails closed without reloading", 1, calls.get());
    }

    @Test
    public void otherFailuresAreRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LivenessEngine engine = new StubEngine();
        EngineLoader loader = new EngineLoader(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new EngineException(EngineErrorCode.MODEL_LOAD, "out of memory");
            }
            return engine;
        });
        try {
            loader.await(5_000);
            fail("expected EngineException");
        } catch (EngineException e) {
            assertEquals(EngineErrorCode.MODEL_LOAD, e.getErrorCode());
        }
        assertSame(engine, loader.await(5_000));
    }

    @Test
    public void slowLoadReportsNotReady() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        EngineLoader loader = new EngineLoader(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new StubEngine();
        });
        try {
            loader.await(50);
            fail("expected EngineException");
        } catch (EngineException e) {
            assertEquals(EngineErrorCode.NOT_READY, e.getErrorCode());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void unexpectedErrorsBecomeModelLoadFailures() {
        EngineLoader loader = new EngineLoader(() -> {
            throw new UnsatisfiedLinkError("libonnxruntime.so");
        });
        try {
            loader.await(5_000);
            fail("expected EngineException");
        } catch (EngineException e) {
            assertEquals(EngineErrorCode.MODEL_LOAD, e.getErrorCode());
        }
    }

    @Test
    public void vendorEngineIsLoadedThroughServiceLoader() throws Exception {
        FakeVendorEngineProvider.LAST_CONTEXT.set(null);
        ConfigSource config = engineConfig(FakeVendorEngineProvider.ENGINE_ID);
        AtomicInteger builtInCalls = new AtomicInteger();
        EngineLoader loader = EngineLoader.select(config, countingBuiltIn(builtInCalls),
                EngineLoader::serviceProviders, () -> context(config));

        LivenessEngine engine = loader.await(5_000);

        assertTrue(engine instanceof FakeVendorEngineProvider.VendorEngine);
        assertEquals("the built-in engine is not loaded for a vendor ID", 0, builtInCalls.get());
        assertSame("the vendor engine gets the client's configuration", config,
                FakeVendorEngineProvider.LAST_CONTEXT.get().config());
        assertEquals("vendor engine " + FakeVendorEngineProvider.ENGINE_ID, loader.acceleratorStatus());
    }

    @Test
    public void serviceLoaderListsTheVendorJarAndTheOnnxJar() {
        List<String> ids = new ArrayList<>();
        List<Class<?>> types = new ArrayList<>();
        for (LivenessEngineProvider p : EngineLoader.serviceProviders()) {
            ids.add(p.engineId());
            types.add(p.getClass());
        }
        assertTrue(ids.toString(), ids.contains(FakeVendorEngineProvider.ENGINE_ID));
        assertTrue(types.toString(), types.contains(OnnxLivenessEngineProvider.class));
    }

    @Test
    public void defaultAndBlankEngineIdUseTheBuiltInOnnxEngine() throws Exception {
        for (String id : Arrays.asList(null, "", "  ", LivenessConfigKeys.DEFAULT_ENGINE,
                " " + LivenessConfigKeys.DEFAULT_ENGINE + " ")) {
            AtomicInteger builtInCalls = new AtomicInteger();
            AtomicInteger providerCalls = new AtomicInteger();
            // A listed provider for the default ID (the liveness-onnx jar has one) is not used either.
            LivenessEngineProvider onnxJar = new LivenessEngineProvider() {
                @Override
                public String engineId() {
                    return LivenessConfigKeys.DEFAULT_ENGINE;
                }

                @Override
                public LivenessEngine create(EngineContext context) {
                    providerCalls.incrementAndGet();
                    return new StubEngine();
                }
            };
            ConfigSource config = engineConfig(id);
            EngineLoader loader = EngineLoader.select(config, countingBuiltIn(builtInCalls),
                    () -> Collections.singletonList(onnxJar), () -> context(config));

            loader.await(5_000);

            assertEquals("engine id '" + id + "'", 1, builtInCalls.get());
            assertEquals("engine id '" + id + "'", 0, providerCalls.get());
        }
    }

    @Test
    public void builtInEngineReportsItsExecutionStatus() throws Exception {
        ConfigSource config = engineConfig(null);
        EngineLoader builtIn = new EngineLoader(StubEngine::new);
        EngineLoader loader = EngineLoader.select(config, builtIn, Collections::emptyList, () -> context(config));
        loader.await(5_000);
        assertEquals(builtIn.acceleratorStatus(), loader.acceleratorStatus());
    }

    @Test
    public void unknownEngineIdFailsClosedAndIsTriedAgain() throws Exception {
        AtomicInteger listings = new AtomicInteger();
        ConfigSource config = engineConfig("no-such-engine");
        AtomicInteger builtInCalls = new AtomicInteger();
        EngineLoader loader = EngineLoader.select(config, countingBuiltIn(builtInCalls), () -> {
            listings.incrementAndGet();
            return EngineLoader.serviceProviders();
        }, () -> context(config));
        for (int i = 0; i < 2; i++) {
            try {
                loader.await(5_000);
                fail("expected EngineException");
            } catch (EngineException e) {
                assertEquals(EngineErrorCode.CONFIG_INVALID, e.getErrorCode());
            }
        }
        assertFalse(loader.isReady());
        assertEquals("no fallback to the built-in engine", 0, builtInCalls.get());
        assertEquals("not a model integrity failure: listed again at the next load", 2, listings.get());
    }

    @Test
    public void vendorProviderFailureIsKeptAsItsOwnError() {
        ConfigSource config = engineConfig("broken-vendor");
        LivenessEngineProvider broken = new LivenessEngineProvider() {
            @Override
            public String engineId() {
                return "broken-vendor";
            }

            @Override
            public LivenessEngine create(EngineContext context) throws EngineException {
                throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "vendor bundle signature");
            }
        };
        EngineLoader loader = EngineLoader.select(config, new EngineLoader(StubEngine::new),
                () -> Collections.singletonList(broken), () -> context(config));
        try {
            loader.await(5_000);
            fail("expected EngineException");
        } catch (EngineException e) {
            assertEquals(EngineErrorCode.MODEL_INTEGRITY, e.getErrorCode());
        }
    }

    @Test
    public void vendorProviderWithoutEngineIsALoadFailure() {
        ConfigSource config = engineConfig("empty-vendor");
        LivenessEngineProvider empty = new LivenessEngineProvider() {
            @Override
            public String engineId() {
                return "empty-vendor";
            }

            @Override
            public LivenessEngine create(EngineContext context) {
                return null;
            }
        };
        EngineLoader loader = EngineLoader.select(config, new EngineLoader(StubEngine::new),
                () -> Collections.singletonList(empty), () -> context(config));
        try {
            loader.await(5_000);
            fail("expected EngineException");
        } catch (EngineException e) {
            assertEquals(EngineErrorCode.MODEL_LOAD, e.getErrorCode());
        }
    }

    private static EngineLoader countingBuiltIn(AtomicInteger calls) {
        return new EngineLoader(() -> {
            calls.incrementAndGet();
            return new StubEngine();
        });
    }

    private static ConfigSource engineConfig(String engineId) {
        Map<String, String> values = new HashMap<>();
        if (engineId != null) {
            values.put(LivenessConfigKeys.ENGINE, engineId);
        }
        return ConfigSource.of(values);
    }

    private static EngineContext context(ConfigSource config) {
        ImageDecoder decoder = new ImageDecoder() {
            @Override
            public ImageBuffer decode(FaceFrame frame) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ImageBuffer decode(byte[] encoded) {
                throw new UnsupportedOperationException();
            }
        };
        EngineContext context = EngineContext.builder().config(config).models(path -> new byte[0])
                .imageDecoder(decoder).build();
        assertNotNull(context);
        return context;
    }

    private static final class StubEngine implements LivenessEngine {
        @Override
        public EngineInfo info() {
            return EngineInfo.builder("stub", "1").build();
        }

        @Override
        public LivenessSession open(SessionRequest request, LivenessListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
