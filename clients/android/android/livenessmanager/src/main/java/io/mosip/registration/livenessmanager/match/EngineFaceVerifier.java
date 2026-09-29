package io.mosip.registration.livenessmanager.match;

import android.util.Log;

import java.util.List;
import java.util.Objects;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.api.LivenessEngine;
import io.mosip.liveness.core.config.ConfigSource;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.facematch.FaceImageDecoder;
import io.mosip.liveness.facematch.FaceMatchException;
import io.mosip.liveness.facematch.FaceSample;
import io.mosip.liveness.facematch.SFaceMatcher;
import io.mosip.liveness.onnx.OnnxLivenessEngine;
import io.mosip.liveness.onnx.OnnxModelSet;
import io.mosip.registration.livenessmanager.engine.EngineLoader;

/**
 * {@link FaceVerifier} over the liveness engine's own YuNet detector and SFace embedder, through the
 * {@code face-match-sdk} matcher (the same code as the desktop client's SFace biometric SDK). No second copy of the
 * models is loaded: the matcher uses the model sessions of the engine the {@link EngineLoader} verified and loaded.
 *
 * <p>Threshold: {@value #MATCH_THRESHOLD_KEY} (0-100, default {@value #DEFAULT_THRESHOLD}), on the same
 * {@code round(100 * max(0, cosine))} scale as the SDK and {@code binding.min_similarity}.
 */
public final class EngineFaceVerifier implements FaceVerifier {

    /** Configuration key of the match threshold. */
    public static final String MATCH_THRESHOLD_KEY = "mosip.registration.liveness.face_auth.match_threshold";
    /** Threshold when the key is missing or malformed; the SDK's default. */
    public static final int DEFAULT_THRESHOLD = 40;
    /** Lowest threshold accepted from the configuration; lower values are raised to it. */
    public static final int MIN_THRESHOLD = 20;

    private static final String TAG = "Liveness";
    private static final long ENGINE_WAIT_MS = 30_000L;

    /** Supplies the matcher; separated for tests. */
    @FunctionalInterface
    public interface MatcherSource {
        /**
         * Returns the matcher.
         *
         * @return the matcher
         * @throws EngineException if the models are not available
         */
        SFaceMatcher get() throws EngineException;
    }

    private final MatcherSource source;
    private final ConfigSource config;

    /**
     * Creates a verifier over the engine loader.
     *
     * @param engines the process-wide engine loader
     * @param decoder the platform image decoder
     * @param config  configuration (threshold)
     * @return the verifier
     */
    public static EngineFaceVerifier over(EngineLoader engines, ImageDecoder decoder, ConfigSource config) {
        Objects.requireNonNull(engines, "engines");
        FaceImageDecoder faceDecoder = FaceImageDecoder.of(Objects.requireNonNull(decoder, "decoder"));
        return new EngineFaceVerifier(new MatcherSource() {
            private SFaceMatcher matcher;

            @Override
            public synchronized SFaceMatcher get() throws EngineException {
                if (matcher == null) {
                    LivenessEngine engine = engines.await(ENGINE_WAIT_MS);
                    if (!(engine instanceof OnnxLivenessEngine)) {
                        throw new EngineException(EngineErrorCode.UNSUPPORTED, "face matching needs the ONNX engine");
                    }
                    OnnxModelSet set = ((OnnxLivenessEngine) engine).modelSet();
                    // The engine owns the model sessions; the matcher must not close them.
                    matcher = new SFaceMatcher(set.faceAnalyzer(), set.faceEmbedder(),
                            set.faceEmbedder().modelId(), faceDecoder, set.models(), null);
                }
                return matcher;
            }
        }, config);
    }

    /**
     * Creates a verifier.
     *
     * @param source supplies the matcher
     * @param config configuration (threshold)
     */
    public EngineFaceVerifier(MatcherSource source, ConfigSource config) {
        this.source = Objects.requireNonNull(source, "source");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public FaceMatch verify(byte[] probe, List<byte[]> gallery) {
        int threshold = threshold();
        if (gallery == null || gallery.isEmpty()) {
            return FaceMatch.notCompared(FaceMatch.Status.NO_GALLERY, threshold);
        }
        SFaceMatcher matcher;
        FaceSample probeSample;
        try {
            matcher = source.get();
            probeSample = matcher.analyse(probe);
        } catch (EngineException e) {
            Log.w(TAG, "Face matching unavailable: " + e.getErrorCode());
            return FaceMatch.notCompared(FaceMatch.Status.UNAVAILABLE, threshold);
        } catch (FaceMatchException e) {
            Log.w(TAG, "Face matching: probe not usable (" + e.problem() + ")");
            return FaceMatch.notCompared(e.problem() == FaceMatchException.Problem.ENGINE
                    ? FaceMatch.Status.UNAVAILABLE : FaceMatch.Status.NO_PROBE_FACE, threshold);
        }
        int best = bestScore(matcher, probeSample, gallery);
        if (best < 0) {
            return FaceMatch.notCompared(FaceMatch.Status.NO_GALLERY, threshold);
        }
        return FaceMatch.compared(best, threshold);
    }

    /**
     * Returns the configured threshold, with the default for a missing or malformed value and the floor applied.
     *
     * @return 0-100
     */
    public int threshold() {
        String raw = config.get(MATCH_THRESHOLD_KEY).map(String::trim).orElse("");
        int value;
        try {
            value = raw.isEmpty() ? DEFAULT_THRESHOLD : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            value = DEFAULT_THRESHOLD;
        }
        return Math.max(MIN_THRESHOLD, Math.min(100, value));
    }

    private static int bestScore(SFaceMatcher matcher, FaceSample probe, List<byte[]> gallery) {
        int best = -1;
        for (byte[] enrolled : gallery) {
            try {
                FaceSample sample = matcher.analyse(enrolled);
                best = Math.max(best, SFaceMatcher.similarity(probe.embedding(), sample.embedding()));
            } catch (FaceMatchException | IllegalArgumentException e) {
                Log.w(TAG, "Face matching: an enrolled face is not usable (" + e.getClass().getSimpleName() + ")");
            }
        }
        return best;
    }
}
