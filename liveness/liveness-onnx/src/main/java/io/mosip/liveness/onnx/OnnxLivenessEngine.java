package io.mosip.liveness.onnx;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.engine.DefaultLivenessEngine;
import io.mosip.liveness.core.engine.EngineParameters;
import io.mosip.liveness.core.engine.LivenessMetrics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The default liveness engine on ONNX models: a {@link DefaultLivenessEngine} together with the
 * {@link OnnxModelSet} whose native sessions it uses. Created by {@link OnnxLivenessEngines}; closing it closes
 * both.
 *
 * <p>Thread-safety: {@link #open} may be called from any thread and each session is confined to the thread that
 * runs it (see {@link LivenessSession}). All sessions share one set of ONNX Runtime sessions; every model call
 * is synchronised, so two sessions running at the same time are correct but take turns on each model. The
 * clients run one liveness session at a time, so this costs nothing in practice. Call {@link #close()} only
 * after the last session has ended: a session that is still running afterwards fails with an
 * {@code INFERENCE} engine error (fail closed), never with a crash.
 */
public final class OnnxLivenessEngine implements LivenessEngine {

    private final DefaultLivenessEngine engine;
    private final OnnxModelSet models;
    private final String bundleVersion;
    private final AtomicBoolean closed = new AtomicBoolean();

    OnnxLivenessEngine(DefaultLivenessEngine engine, OnnxModelSet models, String bundleVersion) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.models = Objects.requireNonNull(models, "models");
        this.bundleVersion = Objects.requireNonNull(bundleVersion, "bundleVersion");
    }

    @Override
    public EngineInfo info() {
        return engine.info();
    }

    @Override
    public boolean isReady() {
        return !closed.get() && engine.isReady();
    }

    @Override
    public LivenessSession open(SessionRequest request, LivenessListener listener) throws EngineException {
        return engine.open(request, listener);
    }

    /**
     * Returns the platform-neutral engine this object wraps, for code that needs its concrete type.
     *
     * @return the engine
     */
    public DefaultLivenessEngine engine() {
        return engine;
    }

    /**
     * Returns the loaded models, for example to reuse the SFace embedder for face matching.
     *
     * @return the model set; closed together with this engine
     */
    public OnnxModelSet modelSet() {
        return models;
    }

    /**
     * Returns the metrics shared by the sessions of this engine.
     *
     * @return the metrics
     */
    public LivenessMetrics metrics() {
        return engine.metrics();
    }

    /**
     * Returns the detection parameters in use (defaults, then the signed bundle's {@code engineParams}, then the
     * caller's overrides).
     *
     * @return the parameters
     */
    public EngineParameters parameters() {
        return engine.parameters();
    }

    /**
     * Returns the version of the model bundle the engine was built from.
     *
     * @return for example {@code "1.0.0"}
     */
    public String bundleVersion() {
        return bundleVersion;
    }

    /** Stops new sessions and releases the native model sessions. Idempotent; never throws. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            engine.close();
            models.close();
        }
    }

    @Override
    public String toString() {
        return "OnnxLivenessEngine[" + engine.info().tag() + ", bundle=" + bundleVersion + ", ready=" + isReady()
                + "]";
    }
}
