package io.mosip.liveness.onnx.pad;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.PadScore;
import io.mosip.liveness.core.vision.PassivePad;
import io.mosip.liveness.onnx.image.Rgb8Image;

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
 */
public final class MiniFasPassivePad implements PassivePad {

    private final List<MiniFasModel> models;

    /**
     * Creates the ensemble.
     *
     * @param models one or more models; the standard ensemble is V2 (2.7) and V1SE (4.0)
     */
    public MiniFasPassivePad(List<MiniFasModel> models) {
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
     * @return mean live probability, with each model's live probability
     * @throws EngineException if inference fails
     */
    public PadScore score(Rgb8Image image, FaceBox face) throws EngineException {
        Map<String, Double> perModel = new LinkedHashMap<>();
        double sum = 0;
        for (MiniFasModel m : models) {
            double live = clamp01(m.probabilities(image, face)[MiniFasModel.LIVE_CLASS]);
            perModel.put(m.id(), live);
            sum += live;
        }
        return PadScore.of(clamp01(sum / models.size()), perModel);
    }

    /**
     * Returns the mean class probabilities of the ensemble (diagnostics and tests).
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
        return "MiniFasPassivePad" + models;
    }
}
