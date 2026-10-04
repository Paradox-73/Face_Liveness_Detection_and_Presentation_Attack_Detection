/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.eval;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ImageDecoder;
import io.mosip.liveness.core.device.FaceFrame;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.image.ImageDecodeException;
import io.mosip.liveness.core.vision.AnalyzerFeature;
import io.mosip.liveness.core.vision.FaceAnalyzer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceEmbedder;
import io.mosip.liveness.core.vision.FaceEmbedding;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.PadScore;
import io.mosip.liveness.core.vision.PassivePad;

import java.util.Objects;
import java.util.Set;

/**
 * Decorators that time each call of the engine's platform and model adapters with {@link System#nanoTime()} and
 * add the result to a {@link StageTimer}. They change no result.
 */
final class TimedModels {

    private TimedModels() {
    }

    /** Timed {@link ImageDecoder}. */
    static final class Decoder implements ImageDecoder {
        private final ImageDecoder delegate;
        private final StageTimer timer;

        Decoder(ImageDecoder delegate, StageTimer timer) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.timer = Objects.requireNonNull(timer, "timer");
        }

        @Override
        public ImageBuffer decode(FaceFrame frame) throws ImageDecodeException {
            long t0 = System.nanoTime();
            try {
                return delegate.decode(frame);
            } finally {
                timer.add(StageTimer.Stage.DECODE, System.nanoTime() - t0);
            }
        }

        @Override
        public ImageBuffer decode(byte[] encoded) throws ImageDecodeException {
            long t0 = System.nanoTime();
            try {
                return delegate.decode(encoded);
            } finally {
                timer.add(StageTimer.Stage.DECODE, System.nanoTime() - t0);
            }
        }
    }

    /** Timed {@link FaceAnalyzer}. */
    static final class Analyzer implements FaceAnalyzer {
        private final FaceAnalyzer delegate;
        private final StageTimer timer;

        Analyzer(FaceAnalyzer delegate, StageTimer timer) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.timer = Objects.requireNonNull(timer, "timer");
        }

        @Override
        public Set<AnalyzerFeature> features() {
            return delegate.features();
        }

        @Override
        public FaceObservation analyze(ImageBuffer image) throws EngineException {
            long t0 = System.nanoTime();
            try {
                return delegate.analyze(image);
            } finally {
                timer.add(StageTimer.Stage.ANALYZE, System.nanoTime() - t0);
            }
        }

        @Override
        public FaceObservation analyze(ImageBuffer image, FaceBox hint) throws EngineException {
            long t0 = System.nanoTime();
            try {
                return delegate.analyze(image, hint);
            } finally {
                timer.add(StageTimer.Stage.ANALYZE, System.nanoTime() - t0);
            }
        }
    }

    /** Timed {@link PassivePad}. */
    static final class Pad implements PassivePad {
        private final PassivePad delegate;
        private final StageTimer timer;

        Pad(PassivePad delegate, StageTimer timer) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.timer = Objects.requireNonNull(timer, "timer");
        }

        @Override
        public PadScore score(ImageBuffer image, FaceBox face) throws EngineException {
            long t0 = System.nanoTime();
            try {
                return delegate.score(image, face);
            } finally {
                timer.add(StageTimer.Stage.PAD, System.nanoTime() - t0);
            }
        }
    }

    /** Timed {@link FaceEmbedder}. */
    static final class Embedder implements FaceEmbedder {
        private final FaceEmbedder delegate;
        private final StageTimer timer;

        Embedder(FaceEmbedder delegate, StageTimer timer) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.timer = Objects.requireNonNull(timer, "timer");
        }

        @Override
        public FaceEmbedding embed(ImageBuffer image, FaceBox face) throws EngineException {
            long t0 = System.nanoTime();
            try {
                return delegate.embed(image, face);
            } finally {
                timer.add(StageTimer.Stage.EMBED, System.nanoTime() - t0);
            }
        }
    }
}
