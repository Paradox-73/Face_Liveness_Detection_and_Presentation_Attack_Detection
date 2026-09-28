package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.api.LivenessListener;
import io.mosip.liveness.core.api.LivenessSession;
import io.mosip.liveness.core.api.SessionRequest;
import io.mosip.liveness.core.vision.FaceAnalyzer;
import io.mosip.liveness.core.vision.FaceEmbedder;
import io.mosip.liveness.core.vision.PassivePad;

import java.util.Objects;

/**
 * The platform-neutral liveness engine: runs the session state machine of design sections 4.5-4.10 on top of
 * injected model adapters.
 *
 * <p>A model module (for example {@code liveness-onnx}) builds one engine with its {@link FaceAnalyzer},
 * {@link PassivePad}, optional {@link FaceEmbedder} and the client's {@link ImageDecoder}, and returns it from its
 * {@link io.mosip.liveness.core.api.LivenessEngineProvider}. The engine itself holds no per-session state and is
 * safe to share; {@link #open} can be called from any thread, and each session is confined to the thread that
 * starts it. The adapters are called only from session threads; if several sessions may run at once they must
 * be thread-safe.
 *
 * <pre>{@code
 * LivenessEngine engine = DefaultLivenessEngine.builder(info)
 *         .faceAnalyzer(analyzer).passivePad(pad).faceEmbedder(embedder)
 *         .imageDecoder(decoder).parameters(EngineParameters.fromMap(manifestEngineParams))
 *         .build();
 * }</pre>
 */
public final class DefaultLivenessEngine implements LivenessEngine {

    private final EngineInfo info;
    private final FaceAnalyzer analyzer;
    private final PassivePad pad;
    private final FaceEmbedder embedder;
    private final ImageDecoder decoder;
    private final EngineParameters parameters;
    private final LivenessMetrics metrics;
    private volatile boolean closed;

    private DefaultLivenessEngine(Builder b) {
        this.info = b.info;
        this.analyzer = b.analyzer;
        this.pad = b.pad;
        this.embedder = b.embedder;
        this.decoder = b.decoder;
        this.parameters = b.parameters;
        this.metrics = b.metrics;
    }

    /**
     * Starts a builder.
     *
     * @param info engine identity, version and models
     * @return a builder
     */
    public static Builder builder(EngineInfo info) {
        return new Builder(info);
    }

    @Override
    public EngineInfo info() {
        return info;
    }

    @Override
    public boolean isReady() {
        return !closed;
    }

    /**
     * Opens a session in state {@code IDLE}.
     *
     * @param request  workflow, resolved policy, subject, device, clock and randomness
     * @param listener receiver of UI states, audit events, state changes and the result
     * @return the new session
     * @throws EngineException with {@code NOT_READY} after {@link #close()}
     */
    @Override
    public LivenessSession open(SessionRequest request, LivenessListener listener) throws EngineException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        if (closed) {
            throw new EngineException(EngineErrorCode.NOT_READY, "engine is closed");
        }
        return new DefaultLivenessSession(this, request, listener);
    }

    /**
     * Returns the metrics shared by the sessions of this engine.
     *
     * @return the metrics
     */
    public LivenessMetrics metrics() {
        return metrics;
    }

    /**
     * Returns the detection parameters.
     *
     * @return the parameters
     */
    public EngineParameters parameters() {
        return parameters;
    }

    FaceAnalyzer analyzer() {
        return analyzer;
    }

    PassivePad pad() {
        return pad;
    }

    FaceEmbedder embedder() {
        return embedder;
    }

    ImageDecoder decoder() {
        return decoder;
    }

    /** Marks the engine closed; sessions already open keep working. Model adapters are closed by their owner. */
    @Override
    public void close() {
        closed = true;
    }

    /** Builder for {@link DefaultLivenessEngine}. */
    public static final class Builder {

        private final EngineInfo info;
        private FaceAnalyzer analyzer;
        private PassivePad pad;
        private FaceEmbedder embedder;
        private ImageDecoder decoder;
        private EngineParameters parameters = EngineParameters.defaults();
        private LivenessMetrics metrics = new LivenessMetrics();

        private Builder(EngineInfo info) {
            this.info = Objects.requireNonNull(info, "info");
        }

        /**
         * Sets the face analyser; required.
         *
         * @param analyzer detector and landmark model adapter
         * @return this builder
         */
        public Builder faceAnalyzer(FaceAnalyzer analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        /**
         * Sets the passive PAD model; required.
         *
         * @param pad passive PAD adapter
         * @return this builder
         */
        public Builder passivePad(PassivePad pad) {
            this.pad = pad;
            return this;
        }

        /**
         * Sets the face embedder; optional. Without it the same-person and capture similarity checks are skipped.
         *
         * @param embedder face embedding adapter, or {@code null}
         * @return this builder
         */
        public Builder faceEmbedder(FaceEmbedder embedder) {
            this.embedder = embedder;
            return this;
        }

        /**
         * Sets the platform image decoder; required.
         *
         * @param decoder decoder of stream frames and captured images
         * @return this builder
         */
        public Builder imageDecoder(ImageDecoder decoder) {
            this.decoder = decoder;
            return this;
        }

        /**
         * Sets the detection parameters; default {@link EngineParameters#defaults()}.
         *
         * @param parameters parameters from the model bundle
         * @return this builder
         */
        public Builder parameters(EngineParameters parameters) {
            this.parameters = Objects.requireNonNull(parameters, "parameters");
            return this;
        }

        /**
         * Sets the metrics collector; default a new one.
         *
         * @param metrics metrics shared by the sessions
         * @return this builder
         */
        public Builder metrics(LivenessMetrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        /**
         * Validates the fields and creates the engine.
         *
         * @return the engine
         * @throws NullPointerException if the analyser, PAD model or decoder is missing
         */
        public DefaultLivenessEngine build() {
            Objects.requireNonNull(analyzer, "faceAnalyzer");
            Objects.requireNonNull(pad, "passivePad");
            Objects.requireNonNull(decoder, "imageDecoder");
            return new DefaultLivenessEngine(this);
        }
    }
}
