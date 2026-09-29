package io.mosip.liveness.onnx;

import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.EngineInfo;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.api.ModelSource;
import io.mosip.liveness.core.config.LivenessConfigKeys;
import io.mosip.liveness.core.engine.DefaultLivenessEngine;
import io.mosip.liveness.core.engine.EngineParameters;
import io.mosip.liveness.core.engine.LivenessMetrics;
import io.mosip.liveness.core.vision.AnalyzerFeature;
import io.mosip.liveness.onnx.detect.YuNetFaceDetector;
import io.mosip.liveness.onnx.model.ModelRegistry;
import io.mosip.liveness.onnx.runtime.OnnxRuntimeOptions;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the default liveness engine from a signed model bundle: verifies the bundle, loads the models into
 * ONNX Runtime and wires {@link OnnxFaceAnalyzer} (YuNet + Face Mesh), {@link io.mosip.liveness.onnx.pad.MiniFasPassivePad}
 * and {@link io.mosip.liveness.onnx.embed.SFaceEmbedder} into a
 * {@link io.mosip.liveness.core.engine.DefaultLivenessEngine}.
 *
 * <pre>{@code
 * // Desktop: bundle from liveness-onnx-<version>-models.jar on the class path.
 * OnnxLivenessEngine engine = OnnxLivenessEngines
 *         .builder(ModelSources.classpath(getClass().getClassLoader()))
 *         .minimumVersion(config.get(LivenessConfigKeys.MODEL_MIN_VERSION).orElse("1.0.0"))
 *         .imageDecoder(clientImageDecoder)   // the client's ImageIO or BitmapFactory decoder
 *         .build();                           // loads 5 models (about 1-2 s); call it off the UI thread
 * LivenessSession session = engine.open(request, listener);
 * ...
 * engine.close();                         // at shutdown, after the last session
 * }</pre>
 *
 * <p>Detection parameters are resolved in this order: the shipped defaults ({@link EngineParameters#defaults()}),
 * then the {@code engineParams} of the signed manifest, then {@link Builder#parameterOverrides(Map)}. Invalid
 * signed parameters fail the build with {@code MODEL_INTEGRITY} (fail closed, audit {@code REG-EVT-LV14});
 * invalid overrides with {@code CONFIG_INVALID}.
 *
 * <p>The same builder runs on Android: pass a {@link ModelSource} over {@code assets/models/} and the client's
 * {@code BitmapFactory}-based {@link ImageDecoder}. {@link OnnxLivenessEngineProvider} does the same through
 * {@link java.util.ServiceLoader}.
 */
public final class OnnxLivenessEngines {

    /** Engine identifier, matched against {@code mosip.registration.liveness.engine}. */
    public static final String ENGINE_ID = LivenessConfigKeys.DEFAULT_ENGINE;
    /** Engine version recorded in audits and packet evidence ({@code mosip-onnx-default@1.0.0}). */
    public static final String ENGINE_VERSION = "1.0.0";
    /** Vendor name recorded in {@link EngineInfo#vendor()}. */
    public static final String VENDOR = "MOSIP";

    private OnnxLivenessEngines() {
    }

    /**
     * Builds an engine from a bundle with the built-in development trust anchor, the default minimum version
     * and default settings.
     *
     * @param source  bundle files
     * @param decoder platform image decoder
     * @return the ready engine
     * @throws EngineException if the bundle fails verification or a model cannot be loaded
     */
    public static OnnxLivenessEngine create(ModelSource source, ImageDecoder decoder) throws EngineException {
        return builder(source).imageDecoder(decoder).build();
    }

    /**
     * Builds an engine from an already verified bundle with default settings.
     *
     * @param registry verified bundle
     * @param decoder  platform image decoder
     * @return the ready engine
     * @throws EngineException if a model cannot be loaded or the signed engine parameters are invalid
     */
    public static OnnxLivenessEngine create(ModelRegistry registry, ImageDecoder decoder) throws EngineException {
        return builder(registry).imageDecoder(decoder).build();
    }

    /**
     * Starts a builder that verifies the bundle itself at {@link Builder#build()}.
     *
     * @param source bundle files
     * @return a builder
     */
    public static Builder builder(ModelSource source) {
        return new Builder(Objects.requireNonNull(source, "source"), null);
    }

    /**
     * Starts a builder over an already verified bundle.
     *
     * @param registry verified bundle
     * @return a builder; {@link Builder#trustAnchor} and {@link Builder#minimumVersion} do not apply
     */
    public static Builder builder(ModelRegistry registry) {
        return new Builder(null, Objects.requireNonNull(registry, "registry"));
    }

    /**
     * Resolves the detection parameters: defaults, then the bundle's signed {@code engineParams}, then the
     * overrides.
     *
     * @param signed    the manifest's {@code engineParams}
     * @param overrides caller overrides, possibly empty
     * @return the parameters
     * @throws EngineException with {@code MODEL_INTEGRITY} if the signed values are invalid, or
     *                         {@code CONFIG_INVALID} if the overrides are
     */
    static EngineParameters resolveParameters(Map<String, String> signed, Map<String, ?> overrides)
            throws EngineException {
        try {
            EngineParameters.fromMap(signed);
        } catch (IllegalArgumentException e) {
            throw new EngineException(EngineErrorCode.MODEL_INTEGRITY, "signed engine parameters are invalid", e);
        }
        Map<String, Object> merged = new LinkedHashMap<>(signed);
        merged.putAll(overrides);
        try {
            return EngineParameters.fromMap(merged);
        } catch (IllegalArgumentException e) {
            throw new EngineException(EngineErrorCode.CONFIG_INVALID, "engine parameter overrides are invalid", e);
        }
    }

    /**
     * Lists the challenges the analyser can measure: head turns and looks need the head pose, a blink the eye
     * openness, a smile the mouth width and a gaze challenge the gaze and head pose.
     *
     * @param features analyser features
     * @return the supported challenge types
     */
    static Set<ChallengeType> supportedChallenges(Set<AnalyzerFeature> features) {
        Set<ChallengeType> types = EnumSet.noneOf(ChallengeType.class);
        if (features.contains(AnalyzerFeature.HEAD_POSE)) {
            types.addAll(EnumSet.of(ChallengeType.TURN_LEFT, ChallengeType.TURN_RIGHT, ChallengeType.LOOK_UP,
                    ChallengeType.LOOK_DOWN));
            if (features.contains(AnalyzerFeature.GAZE)) {
                types.addAll(EnumSet.of(ChallengeType.GAZE_LEFT, ChallengeType.GAZE_RIGHT));
            }
        }
        if (features.contains(AnalyzerFeature.EYE_OPENNESS)) {
            types.add(ChallengeType.BLINK);
        }
        if (features.contains(AnalyzerFeature.MOUTH)) {
            types.add(ChallengeType.SMILE);
        }
        return types;
    }

    /** Builder for {@link OnnxLivenessEngine}. */
    public static final class Builder {

        private final ModelSource source;
        private final ModelRegistry registry;
        private final List<PublicKey> trustAnchors = new ArrayList<>();
        private String minimumVersion;
        private ImageDecoder decoder;
        private OnnxRuntimeOptions runtimeOptions = OnnxRuntimeOptions.defaults();
        private YuNetFaceDetector.Options detectorOptions = YuNetFaceDetector.Options.defaults();
        private boolean embedder = true;
        private Map<String, ?> overrides = Collections.emptyMap();
        private LivenessMetrics metrics;

        private Builder(ModelSource source, ModelRegistry registry) {
            this.source = source;
            this.registry = registry;
        }

        /**
         * Adds a trusted bundle signing key; without any, the built-in development key is trusted.
         *
         * @param key RSA public key
         * @return this builder
         * @throws IllegalStateException if the builder was started from a verified registry
         */
        public Builder trustAnchor(PublicKey key) {
            requireSource("trustAnchor");
            trustAnchors.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        /**
         * Sets the lowest accepted bundle version ({@code mosip.registration.liveness.model.min_version}).
         *
         * @param version {@code MAJOR.MINOR.PATCH}
         * @return this builder
         * @throws IllegalStateException if the builder was started from a verified registry
         */
        public Builder minimumVersion(String version) {
            requireSource("minimumVersion");
            this.minimumVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        /**
         * Sets the platform image decoder; required.
         *
         * @param decoder decoder of stream frames and captured images
         * @return this builder
         */
        public Builder imageDecoder(ImageDecoder decoder) {
            this.decoder = Objects.requireNonNull(decoder, "decoder");
            return this;
        }

        /**
         * Sets the ONNX Runtime settings; default {@link OnnxRuntimeOptions#defaults()}.
         *
         * @param options thread counts and session customizer
         * @return this builder
         */
        public Builder runtimeOptions(OnnxRuntimeOptions options) {
            this.runtimeOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Sets the face detector settings; default {@link YuNetFaceDetector.Options#defaults()}.
         *
         * @param options score, overlap and input size
         * @return this builder
         */
        public Builder detectorOptions(YuNetFaceDetector.Options options) {
            this.detectorOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        /**
         * Chooses whether to load SFace; without it the engine skips the same-person and capture similarity
         * checks. Default {@code true}.
         *
         * @param load {@code true} to load the embedder
         * @return this builder
         */
        public Builder withEmbedder(boolean load) {
            this.embedder = load;
            return this;
        }

        /**
         * Overrides detection parameters on top of the bundle's signed values, for calibration runs and tests.
         * Production clients leave this empty so only signed values apply.
         *
         * @param values keys from {@code EngineParameter.key()} with numeric values; copied
         * @return this builder
         */
        public Builder parameterOverrides(Map<String, ?> values) {
            this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
            return this;
        }

        /**
         * Sets the metrics collector shared by the sessions; default a new one.
         *
         * @param metrics metrics collector
         * @return this builder
         */
        public Builder metrics(LivenessMetrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        /**
         * Verifies the bundle (when started from a source), loads the models and creates the engine.
         *
         * @return the ready engine; the caller owns it and closes it at shutdown
         * @throws EngineException with {@code MODEL_MISSING}, {@code MODEL_INTEGRITY} or {@code MODEL_VERSION} if
         *                         the bundle fails verification (audit {@code REG-EVT-LV14}), {@code MODEL_LOAD} if
         *                         ONNX Runtime cannot load a model, {@code CONFIG_INVALID} for a bad minimum
         *                         version or override
         * @throws NullPointerException if no image decoder was set
         */
        public OnnxLivenessEngine build() throws EngineException {
            Objects.requireNonNull(decoder, "imageDecoder");
            ModelRegistry verified = registry != null ? registry : openRegistry();
            EngineParameters parameters = resolveParameters(verified.manifest().engineParams(), overrides);
            OnnxModelSet models = OnnxModelSet.builder(verified).runtimeOptions(runtimeOptions)
                    .detectorOptions(detectorOptions).withEmbedder(embedder).load();
            try {
                DefaultLivenessEngine.Builder b = DefaultLivenessEngine.builder(engineInfo(models))
                        .faceAnalyzer(models.faceAnalyzer()).passivePad(models.passivePad())
                        .faceEmbedder(embedder ? models.faceEmbedder() : null).imageDecoder(decoder)
                        .parameters(parameters);
                if (metrics != null) {
                    b.metrics(metrics);
                }
                return new OnnxLivenessEngine(b.build(), models, verified.bundleVersion());
            } catch (RuntimeException e) {
                models.close();
                throw e;
            }
        }

        private ModelRegistry openRegistry() throws EngineException {
            ModelRegistry.Builder b = ModelRegistry.builder(source);
            for (PublicKey key : trustAnchors) {
                b.trustAnchor(key);
            }
            if (minimumVersion != null) {
                b.minimumVersion(minimumVersion);
            }
            return b.open();
        }

        private static EngineInfo engineInfo(OnnxModelSet models) {
            EngineInfo.Builder b = EngineInfo.builder(ENGINE_ID, ENGINE_VERSION).vendor(VENDOR)
                    .supportedChallenges(supportedChallenges(models.faceAnalyzer().features())).requiresStream(true);
            models.models().forEach(b::model);
            return b.build();
        }

        private void requireSource(String option) {
            if (source == null) {
                throw new IllegalStateException(option + " applies only when the builder verifies the bundle itself");
            }
        }
    }
}
