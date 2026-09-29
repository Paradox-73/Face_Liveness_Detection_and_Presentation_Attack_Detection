package io.mosip.registration.livenessmanager.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;

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
