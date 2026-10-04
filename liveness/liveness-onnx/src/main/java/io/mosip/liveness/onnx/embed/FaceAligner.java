/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.liveness.onnx.embed;

import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FacePoint;
import io.mosip.liveness.onnx.image.AffineTransform;
import io.mosip.liveness.onnx.image.ImageOps;
import io.mosip.liveness.onnx.image.Rgb8Image;

import java.util.List;

/**
 * Five-point face alignment to the 112x112 template of OpenCV {@code FaceRecognizerSF.alignCrop}: the
 * least-squares similarity (rotation, uniform scale, translation) that maps the detector landmarks onto the
 * template, then a bilinear warp with zero outside the image.
 */
public final class FaceAligner {

    /** Side of the aligned face in pixels. */
    public static final int SIZE = 112;

    /**
     * Template points in {@link io.mosip.liveness.core.vision.Landmark5} order (right eye, left eye, nose tip,
     * right and left mouth corner), from OpenCV {@code face_recognize.cpp}.
     */
    private static final double[] TEMPLATE = {
        38.2946, 51.6963,
        73.5318, 51.5014,
        56.0252, 71.7366,
        41.5493, 92.3655,
        70.7299, 92.2041,
    };

    private FaceAligner() {
    }

    /**
     * Returns the similarity that maps a face's landmarks onto the template.
     *
     * @param face a box with five landmarks
     * @return transform from image pixels to aligned-face pixels
     * @throws IllegalArgumentException if the box has no landmarks
     */
    public static AffineTransform transform(FaceBox face) {
        if (!face.hasLandmarks()) {
            throw new IllegalArgumentException("face alignment needs the five landmarks");
        }
        List<FacePoint> pts = face.landmarks();
        double[] src = new double[10];
        for (int i = 0; i < 5; i++) {
            src[2 * i] = pts.get(i).x();
            src[2 * i + 1] = pts.get(i).y();
        }
        return AffineTransform.estimateSimilarity(src, TEMPLATE.clone());
    }

    /**
     * Returns the aligned 112x112 face (for diagnostics; the embedder warps straight into its tensor).
     *
     * @param image the image
     * @param face  a box with five landmarks
     * @return the aligned face in the image's channel order
     */
    public static Rgb8Image align(Rgb8Image image, FaceBox face) {
        return ImageOps.warpAffine(image, transform(face), SIZE, SIZE);
    }
}
