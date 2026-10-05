/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.pad;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.PadScore;
import io.mosip.liveness.core.vision.PassivePad;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.image.TensorFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Passive presentation attack detection with the MiniFASNet ensemble: MiniFASNetV2 on a 2.7x crop and
 * MiniFASNetV1SE on a 4.0x crop. As in upstream {@code test.py}, the class probabilities of the models are
 * averaged; the live probability is the mean of their class-1 probabilities.
 *
 * <p>This is a weak detector against good 2-D attacks (a vendor benchmark, axonlab.ai 2026, reports that it
 * accepts about two thirds of them) and it does not detect digital injection or frozen frames; the engine
 * combines it with active challenges and frame-integrity checks. Calls are serialised per model; one
 * instance may be shared.
 *
 * <p>Two additions to the upstream scoring, both on by default: in a dark frame the PAD crop is brightened first
 * ({@link SceneExposure}), and the live probability is capped by the screen-pattern check
 * ({@link ScreenPatternMeter}), reported as model {@value #SCREEN_PATTERN_ID}.
 */
public final class MiniFasPassivePad implements PassivePad {

    /** Entry of the screen-pattern check in {@link PadScore#modelProbabilities()}. */
    public static final String SCREEN_PATTERN_ID = "screen-pattern";

    private final List<MiniFasModel> models;
    private final boolean exposureCorrection;
    private final boolean screenCheck;

    /**
     * Creates the ensemble with exposure correction and the screen-pattern check.
     *
     * @param models one or more models; the standard ensemble is V2 (2.7) and V1SE (4.0)
     */
    public MiniFasPassivePad(List<MiniFasModel> models) {
        this(models, true, true);
    }

    /**
     * Creates the ensemble.
     *
     * @param models             one or more models; the standard ensemble is V2 (2.7) and V1SE (4.0)
     * @param exposureCorrection {@code true} to brighten the crop of a dark frame ({@link SceneExposure})
     * @param screenCheck        {@code true} to cap the live probability with {@link ScreenPatternMeter}
     */
    public MiniFasPassivePad(List<MiniFasModel> models, boolean exposureCorrection, boolean screenCheck) {
        this.exposureCorrection = exposureCorrection;
        this.screenCheck = screenCheck;
        Objects.requireNonNull(models, "models");
        if (models.isEmpty()) {
            throw new IllegalArgumentException("at least one model required");
        }
        this.models = Collections.unmodifiableList(new ArrayList<>(models));
    }

    /**
     * Returns the models of the ensemble.
     *
     * @return an unmodifiable list
     */
    public List<MiniFasModel> models() {
        return models;
    }

    @Override
    public PadScore score(ImageBuffer image, FaceBox face) throws EngineException {
        return score(Rgb8Image.of(image), face);
    }

    /**
     * Scores one face on an already converted image.
     *
     * @param image the image
     * @param face  the face box
     * @return mean live probability of the models, capped by the screen-pattern check, with each model's live
     * probability (and the screen-pattern check's, when it is on)
     * @throws EngineException if inference fails
     */
    public PadScore score(Rgb8Image image, FaceBox face) throws EngineException {
        Map<String, Double> perModel = new LinkedHashMap<>();
        double gain = exposureCorrection ? SceneExposure.gain(image) : 1.0;
        double sum = 0;
        for (MiniFasModel m : models) {
            double live = clamp01(m.probabilities(image, face, TensorFormat.BGR_RAW, gain)[MiniFasModel.LIVE_CLASS]);
            perModel.put(m.id(), live);
            sum += live;
        }
        double live = clamp01(sum / models.size());
        if (screenCheck) {
            double screen = ScreenPatternMeter.liveProbability(ScreenPatternMeter.peakRatio(image, face));
            perModel.put(SCREEN_PATTERN_ID, screen);
            live = Math.min(live, screen);
        }
        return PadScore.of(live, perModel);
    }

    /**
     * Tells whether a dark frame is brightened before scoring.
     *
     * @return {@code true} when exposure correction is on
     */
    public boolean exposureCorrection() {
        return exposureCorrection;
    }

    /**
     * Tells whether the screen-pattern check caps the score.
     *
     * @return {@code true} when the check is on
     */
    public boolean screenCheck() {
        return screenCheck;
    }

    /**
     * Returns the mean class probabilities of the ensemble with the upstream preprocessing only (no exposure
     * correction, no screen-pattern check; diagnostics and tests).
     *
     * @param image the image
     * @param face  the face box
     * @return 3 mean probabilities; index {@value MiniFasModel#LIVE_CLASS} is live
     * @throws EngineException if inference fails
     */
    public double[] meanProbabilities(Rgb8Image image, FaceBox face) throws EngineException {
        double[] mean = new double[MiniFasModel.CLASSES];
        for (MiniFasModel m : models) {
            float[] p = m.probabilities(image, face);
            for (int i = 0; i < mean.length; i++) {
                mean[i] += p[i] / (double) models.size();
            }
        }
        return mean;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    @Override
    public String toString() {
        return "MiniFasPassivePad" + models + (exposureCorrection ? "+exposure" : "") + (screenCheck ? "+screen" : "");
    }
}
