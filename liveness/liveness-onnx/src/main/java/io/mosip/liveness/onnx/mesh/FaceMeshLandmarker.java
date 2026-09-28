package io.mosip.liveness.onnx.mesh;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceMesh;
import io.mosip.liveness.core.vision.FacePoint;
import io.mosip.liveness.core.vision.Landmark5;
import io.mosip.liveness.onnx.image.AffineTransform;
import io.mosip.liveness.onnx.image.ImageOps;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.image.TensorFormat;
import io.mosip.liveness.onnx.runtime.OrtModel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * MediaPipe Face Mesh V2 (478 landmarks including the irises) on a face found by the detector.
 *
 * <p>Region of interest as in MediaPipe's face landmarker: a square centred on the detector box, side
 * {@value #ROI_SCALE} times the longer box side (25% margin on each side), rotated so the line between the eyes
 * is horizontal. The region is warped to 256x256 RGB scaled to 0-1. Model outputs are 478 x (x, y, z) in crop
 * pixels and a face-presence logit; points are mapped back to image pixels (z scaled like x) and the logit goes
 * through a sigmoid.
 *
 * <p>Calls are serialised; one instance may be shared.
 */
public final class FaceMeshLandmarker {

    /** Side of the square model input in pixels. */
    public static final int INPUT_SIZE = 256;
    /** Region side relative to the longer side of the detector box. */
    public static final double ROI_SCALE = 1.5;

    private static final int VALUES = FaceMesh.POINTS_WITH_IRIS * 3;

    private final OrtModel model;
    private final float[] tensor = new float[3 * INPUT_SIZE * INPUT_SIZE];

    /**
     * Creates a landmarker.
     *
     * @param model the loaded Face Mesh V2 model (input 1x3x256x256)
     */
    public FaceMeshLandmarker(OrtModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * Computes the mesh of one face.
     *
     * @param image the image
     * @param face  the detector box; its eye landmarks, if present, set the rotation
     * @return the landmarks in image pixels and the presence probability
     * @throws EngineException if inference fails or the outputs do not have the expected shape
     */
    public synchronized MeshLandmarks detect(Rgb8Image image, FaceBox face) throws EngineException {
        AffineTransform imageToCrop = regionOfInterest(face);
        ImageOps.warpAffineToTensor(image, imageToCrop, INPUT_SIZE, INPUT_SIZE, TensorFormat.RGB_UNIT, tensor);
        Map<String, float[]> out = model.run(tensor, new long[] {1, 3, INPUT_SIZE, INPUT_SIZE});
        List<String> names = model.outputNames();
        float[] points = names.isEmpty() ? null : out.get(names.get(0));
        float[] flag = names.size() < 2 ? null : out.get(names.get(1));
        if (points == null || points.length != VALUES || flag == null || flag.length != 1) {
            throw new EngineException(EngineErrorCode.INFERENCE, "Face Mesh outputs have an unexpected shape");
        }
        AffineTransform cropToImage = imageToCrop.invert();
        double depthScale = 1.0 / imageToCrop.scale();
        float[] xyz = new float[VALUES];
        for (int i = 0; i < VALUES; i += 3) {
            double u = points[i];
            double v = points[i + 1];
            xyz[i] = (float) cropToImage.mapX(u, v);
            xyz[i + 1] = (float) cropToImage.mapY(u, v);
            xyz[i + 2] = (float) (points[i + 2] * depthScale);
        }
        double presence = 1.0 / (1.0 + Math.exp(-flag[0]));
        return new MeshLandmarks(FaceMesh.of(xyz), presence);
    }

    /**
     * Returns the transform from image pixels to model input pixels for a face.
     *
     * @param face detector box
     * @return the image-to-crop transform
     */
    public static AffineTransform regionOfInterest(FaceBox face) {
        double side = ROI_SCALE * Math.max(face.width(), face.height());
        double angle = 0;
        Optional<FacePoint> right = face.landmark(Landmark5.RIGHT_EYE);
        Optional<FacePoint> left = face.landmark(Landmark5.LEFT_EYE);
        if (right.isPresent() && left.isPresent()) {
            angle = Math.atan2(left.get().y() - right.get().y(), left.get().x() - right.get().x());
        }
        return AffineTransform.rotateScaleAbout(INPUT_SIZE / side, -angle, face.centerX(), face.centerY(),
                INPUT_SIZE / 2.0, INPUT_SIZE / 2.0);
    }
}
