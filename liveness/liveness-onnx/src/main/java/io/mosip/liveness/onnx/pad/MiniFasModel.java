/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.pad;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.onnx.image.ImageOps;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.image.TensorFormat;
import io.mosip.liveness.onnx.runtime.OrtModel;

import java.util.Map;
import java.util.Objects;

/**
 * One MiniFASNet passive anti-spoofing model (Minivision Silent-Face-Anti-Spoofing weights) with its crop scale.
 *
 * <p>Preprocessing is the upstream one, pinned by unit tests because a wrong variant silently flips results:
 * <ol>
 *   <li>face box as integers {@code [x, y, w, h]} (truncated);</li>
 *   <li>crop box from {@code generate_patches.py::_get_new_box}: scale clamped to
 *       {@code min((H-1)/h, (W-1)/w, scale)}, box centred on the face, then shifted (not cut) to stay inside
 *       the image;</li>
 *   <li>bilinear resize of the crop to 80x80;</li>
 *   <li>BGR planes with raw 0-255 values (the upstream {@code to_tensor} has its {@code /255} commented
 *       out);</li>
 *   <li>softmax over 3 classes; class {@value #LIVE_CLASS} is live, 0 and 2 are attack types.</li>
 * </ol>
 */
public final class MiniFasModel {

    /** Model input side in pixels. */
    public static final int INPUT_SIZE = 80;
    /** Index of the live class in the model output. */
    public static final int LIVE_CLASS = 1;
    /** Number of output classes. */
    public static final int CLASSES = 3;

    private final String id;
    private final OrtModel model;
    private final double cropScale;
    private final float[] tensor = new float[3 * INPUT_SIZE * INPUT_SIZE];

    /**
     * Creates the model wrapper.
     *
     * @param id        model identifier (reported per model in the PAD score)
     * @param model     the loaded ONNX model (input Nx3x80x80, output Nx3)
     * @param cropScale crop size relative to the face box: 2.7 for MiniFASNetV2, 4.0 for MiniFASNetV1SE
     */
    public MiniFasModel(String id, OrtModel model, double cropScale) {
        this.id = Objects.requireNonNull(id, "id");
        this.model = Objects.requireNonNull(model, "model");
        if (!(cropScale > 0 && cropScale <= 10)) {
            throw new IllegalArgumentException("crop scale must be in (0, 10]");
        }
        this.cropScale = cropScale;
    }

    /**
     * Returns the model identifier.
     *
     * @return the identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the crop scale.
     *
     * @return crop size relative to the face box
     */
    public double cropScale() {
        return cropScale;
    }

    /**
     * Returns the class probabilities of one face with the upstream preprocessing.
     *
     * @param image the image
     * @param face  the face box from the detector
     * @return 3 probabilities summing to 1; index {@value #LIVE_CLASS} is live
     * @throws EngineException if inference fails
     */
    public float[] probabilities(Rgb8Image image, FaceBox face) throws EngineException {
        return probabilities(image, face, TensorFormat.BGR_RAW);
    }

    /**
     * Returns the class probabilities with a chosen tensor format. Only {@link TensorFormat#BGR_RAW} is correct;
     * other formats exist so tests can show that they change the answer.
     *
     * @param image  the image
     * @param face   the face box
     * @param format tensor format
     * @return 3 probabilities summing to 1
     * @throws EngineException if inference fails
     */
    public synchronized float[] probabilities(Rgb8Image image, FaceBox face, TensorFormat format)
            throws EngineException {
        int[] r = cropRegion(image.width(), image.height(), face, cropScale);
        ImageOps.resizeRegionToTensor(image, r[0], r[1], r[2], r[3], INPUT_SIZE, INPUT_SIZE, format, tensor, INPUT_SIZE,
                INPUT_SIZE);
        Map<String, float[]> out = model.run(tensor, new long[] {1, 3, INPUT_SIZE, INPUT_SIZE});
        float[] logits = out.get(model.outputNames().get(0));
        if (logits == null || logits.length != CLASSES) {
            throw new EngineException(EngineErrorCode.INFERENCE, "MiniFASNet output has an unexpected shape");
        }
        return softmax(logits);
    }

    /**
     * Computes the crop region exactly as upstream {@code CropImage._get_new_box}.
     *
     * @param imageWidth  image width
     * @param imageHeight image height
     * @param face        face box (truncated to integers first, as the upstream detector returns them)
     * @param scale       wanted crop scale
     * @return inclusive {@code {left, top, right, bottom}} inside the image
     */
    public static int[] cropRegion(int imageWidth, int imageHeight, FaceBox face, double scale) {
        int x = (int) face.x();
        int y = (int) face.y();
        int boxW = Math.max(1, (int) face.width());
        int boxH = Math.max(1, (int) face.height());
        double s = Math.min((imageHeight - 1) / (double) boxH, Math.min((imageWidth - 1) / (double) boxW, scale));
        double newW = boxW * s;
        double newH = boxH * s;
        double cx = boxW / 2.0 + x;
        double cy = boxH / 2.0 + y;
        double left = cx - newW / 2;
        double top = cy - newH / 2;
        double right = cx + newW / 2;
        double bottom = cy + newH / 2;
        if (left < 0) {
            right -= left;
            left = 0;
        }
        if (top < 0) {
            bottom -= top;
            top = 0;
        }
        if (right > imageWidth - 1) {
            left -= right - imageWidth + 1;
            right = imageWidth - 1;
        }
        if (bottom > imageHeight - 1) {
            top -= bottom - imageHeight + 1;
            bottom = imageHeight - 1;
        }
        int l = clamp((int) left, imageWidth - 1);
        int t = clamp((int) top, imageHeight - 1);
        int r = Math.max(l, clamp((int) right, imageWidth - 1));
        int b = Math.max(t, clamp((int) bottom, imageHeight - 1));
        return new int[] {l, t, r, b};
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) {
            max = Math.max(max, v);
        }
        double sum = 0;
        double[] e = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            e[i] = Math.exp(logits[i] - max);
            sum += e[i];
        }
        float[] p = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            p[i] = (float) (e[i] / sum);
        }
        return p;
    }

    @Override
    public String toString() {
        return "MiniFasModel[" + id + ", scale=" + cropScale + "]";
    }
}
