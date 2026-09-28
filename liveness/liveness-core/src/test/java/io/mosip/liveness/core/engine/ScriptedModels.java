package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.image.PixelFormat;
import io.mosip.liveness.core.vision.AnalyzerFeature;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceAnalyzer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceEmbedder;
import io.mosip.liveness.core.vision.FaceEmbedding;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.PadScore;
import io.mosip.liveness.core.vision.PassivePad;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Model adapters whose outputs the test scripts frame by frame. */
final class ScriptedModels {

    static final float[] PERSON_A = {1, 0.2f, 0.1f, 0, 0.3f, 0.1f, 0.05f, 0.2f};
    static final float[] PERSON_B = {0, 0.1f, 1, 0.4f, 0, 0.1f, 0.9f, 0};

    private ScriptedModels() {
    }

    /** Analyser returning the observation set by the test. */
    static final class Analyzer implements FaceAnalyzer {
        Set<AnalyzerFeature> features = EnumSet.of(AnalyzerFeature.LANDMARKS_5, AnalyzerFeature.HEAD_POSE,
                AnalyzerFeature.EYE_OPENNESS, AnalyzerFeature.MOUTH, AnalyzerFeature.QUALITY);
        FaceObservation next = FaceObservation.none(640, 480);
        EngineException failure;
        RuntimeException crash;
        FakeClock clock;
        long delayMs;
        int calls;
        FaceBox lastHint;

        void faces(DetectedFace... faces) {
            next = FaceObservation.of(640, 480, Arrays.asList(faces));
        }

        @Override
        public Set<AnalyzerFeature> features() {
            return Collections.unmodifiableSet(features);
        }

        @Override
        public FaceObservation analyze(ImageBuffer image) throws EngineException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            if (crash != null) {
                throw crash;
            }
            if (clock != null && delayMs > 0) {
                clock.advanceMs(delayMs);
            }
            return next;
        }

        @Override
        public FaceObservation analyze(ImageBuffer image, FaceBox hint) throws EngineException {
            lastHint = hint;
            return analyze(image);
        }
    }

    /** PAD model returning the live probability set by the test. */
    static final class Pad implements PassivePad {
        double next = 0.9;
        int calls;
        EngineException failure;

        @Override
        public PadScore score(ImageBuffer image, FaceBox face) throws EngineException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return PadScore.of(next);
        }
    }

    /** Embedder returning the identity vector set by the test. */
    static final class Embedder implements FaceEmbedder {
        float[] next = PERSON_A;
        int calls;

        @Override
        public FaceEmbedding embed(ImageBuffer image, FaceBox face) {
            calls++;
            return FaceEmbedding.of("sface-test", next);
        }
    }

    /**
     * Decoder for test frames: GRAY8 frames are wrapped as they are; JPEG frames must start with 0xFF (else they
     * count as corrupt) and become a 32x32 grey image derived from their bytes.
     */
    static final class Decoder implements ImageDecoder {
        @Override
        public ImageBuffer decode(FaceFrame frame) throws ImageDecodeException {
            if (frame.format() == PixelFormat.GRAY8) {
                return ImageBuffer.ofBytes(frame.width(), frame.height(), PixelFormat.GRAY8, frame.data());
            }
            return decode(frame.data());
        }

        @Override
        public ImageBuffer decode(byte[] encoded) throws ImageDecodeException {
            if (encoded.length < 2 || (encoded[0] & 0xFF) != 0xFF) {
                throw new ImageDecodeException("not a JPEG");
            }
            byte[] px = new byte[32 * 32];
            for (int i = 0; i < px.length; i++) {
                px[i] = encoded[i % encoded.length];
            }
            return ImageBuffer.ofBytes(32, 32, PixelFormat.GRAY8, px);
        }
    }

    static EngineException inferenceFailure() {
        return new EngineException(EngineErrorCode.INFERENCE, "inference failed");
    }
}
