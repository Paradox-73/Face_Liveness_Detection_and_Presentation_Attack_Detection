package io.mosip.liveness.onnx;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.image.ImageBuffer;
import io.mosip.liveness.core.vision.AnalyzerFeature;
import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceAnalyzer;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FaceMesh;
import io.mosip.liveness.core.vision.FaceObservation;
import io.mosip.liveness.core.vision.FacePoint;
import io.mosip.liveness.core.vision.HeadPose;
import io.mosip.liveness.core.vision.Landmark5;
import io.mosip.liveness.onnx.detect.YuNetFaceDetector;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.mesh.FaceMeasures;
import io.mosip.liveness.onnx.mesh.FaceMeshLandmarker;
import io.mosip.liveness.onnx.mesh.HeadPoseEstimator;
import io.mosip.liveness.onnx.mesh.MeshLandmarks;
import io.mosip.liveness.onnx.quality.FaceQualityMeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link FaceAnalyzer} on ONNX models: YuNet finds every face; for the subject's face, Face Mesh V2 adds 478
 * landmarks, from which come head pose, eye aspect ratios, mouth width ratio, gaze and the inter-eye distance;
 * brightness and sharpness come from {@link FaceQualityMeter}. Other faces get their box and a five-point pose.
 *
 * <p>Face order: with a hint (the subject's box in the previous frame), the face overlapping it most (IoU at least
 * 0.3) comes first, so the session keeps following the same person; otherwise faces are ranked by area,
 * discounted by distance from the image centre. Without a landmarker (detector-only fallback), or when the mesh
 * presence is below {@link Options#minMeshPresence()}, the subject gets the five-point pose and no eye, mouth
 * or gaze measures.
 *
 * <p>Images must be upright and not mirrored. Calls are serialised by the underlying models; one instance may
 * be shared.
 */
public final class OnnxFaceAnalyzer implements FaceAnalyzer {

    private static final double TRACK_MIN_IOU = 0.3;

    private final YuNetFaceDetector detector;
    private final FaceMeshLandmarker landmarker;
    private final Options options;
    private final Set<AnalyzerFeature> features;

    /**
     * Creates an analyser.
     *
     * @param detector   the face detector
     * @param landmarker the Face Mesh landmarker, or {@code null} for the detector-only fallback
     * @param options    analyser settings
     */
    public OnnxFaceAnalyzer(YuNetFaceDetector detector, FaceMeshLandmarker landmarker, Options options) {
        this.detector = Objects.requireNonNull(detector, "detector");
        this.landmarker = landmarker;
        this.options = Objects.requireNonNull(options, "options");
        EnumSet<AnalyzerFeature> f = EnumSet.of(AnalyzerFeature.LANDMARKS_5, AnalyzerFeature.HEAD_POSE,
                AnalyzerFeature.QUALITY);
        if (landmarker != null) {
            f.addAll(EnumSet.of(AnalyzerFeature.MESH, AnalyzerFeature.EYE_OPENNESS, AnalyzerFeature.MOUTH,
                    AnalyzerFeature.GAZE));
        }
        this.features = Collections.unmodifiableSet(f);
    }

    @Override
    public Set<AnalyzerFeature> features() {
        return features;
    }

    @Override
    public FaceObservation analyze(ImageBuffer image) throws EngineException {
        return analyze(Rgb8Image.of(image), null);
    }

    @Override
    public FaceObservation analyze(ImageBuffer image, FaceBox hint) throws EngineException {
        return analyze(Rgb8Image.of(image), hint);
    }

    /**
     * Analyses an already converted image.
     *
     * @param image the image
     * @param hint  the subject's box in the previous frame, or {@code null}
     * @return every face found, subject first
     * @throws EngineException if inference fails
     */
    public FaceObservation analyze(Rgb8Image image, FaceBox hint) throws EngineException {
        List<FaceBox> boxes = order(detector.detect(image), hint, image.width(), image.height());
        List<DetectedFace> faces = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            faces.add(i == 0 ? describeSubject(image, boxes.get(0)) : describeOther(boxes.get(i)));
        }
        return FaceObservation.of(image.width(), image.height(), faces);
    }

    private DetectedFace describeSubject(Rgb8Image image, FaceBox box) throws EngineException {
        DetectedFace.Builder b = DetectedFace.builder(box);
        MeshLandmarks mesh = landmarker == null ? null : landmarker.detect(image, box);
        double ied;
        if (mesh != null && mesh.presence() >= options.minMeshPresence()) {
            FaceMesh m = mesh.mesh();
            b.mesh(m).pose(poseFromMesh(m).orElse(poseFromBox(box).orElse(null)));
            double earR = FaceMeasures.rightEyeAspectRatio(m);
            double earL = FaceMeasures.leftEyeAspectRatio(m);
            if (finite(earR, earL)) {
                b.eyeAspectRatios(earL, earR);
            }
            double mouth = FaceMeasures.mouthWidthRatio(m);
            if (finite(mouth)) {
                b.mouthWidthRatio(mouth);
            }
            double[] gaze = FaceMeasures.gaze(m);
            if (finite(gaze[0], gaze[1])) {
                b.gaze(gaze[0], gaze[1]);
            }
            ied = FaceMeasures.interEyeDistance(m);
        } else {
            b.pose(poseFromBox(box).orElse(null));
            ied = boxInterEyeDistance(box);
        }
        return b.quality(FaceQualityMeter.measure(image, box, finite(ied) ? ied : 0)).build();
    }

    private static DetectedFace describeOther(FaceBox box) {
        return DetectedFace.builder(box).pose(poseFromBox(box).orElse(null)).build();
    }

    private static Optional<HeadPose> poseFromMesh(FaceMesh mesh) {
        try {
            return Optional.of(HeadPoseEstimator.fromMesh(mesh));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<HeadPose> poseFromBox(FaceBox box) {
        try {
            return box.hasLandmarks() ? Optional.of(HeadPoseEstimator.fromLandmarks5(box)) : Optional.<HeadPose>empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static double boxInterEyeDistance(FaceBox box) {
        Optional<FacePoint> r = box.landmark(Landmark5.RIGHT_EYE);
        Optional<FacePoint> l = box.landmark(Landmark5.LEFT_EYE);
        return r.isPresent() && l.isPresent() ? r.get().distanceTo(l.get()) : 0;
    }

    /**
     * Orders faces subject first.
     *
     * @param boxes  detected faces
     * @param hint   previous subject box, or {@code null}
     * @param width  image width
     * @param height image height
     * @return a new list, subject first
     */
    static List<FaceBox> order(List<FaceBox> boxes, FaceBox hint, int width, int height) {
        List<FaceBox> sorted = new ArrayList<>(boxes);
        double cx = width / 2.0;
        double cy = height / 2.0;
        double halfDiagonal = Math.hypot(cx, cy);
        sorted.sort((a, b) -> Double.compare(priority(b, cx, cy, halfDiagonal), priority(a, cx, cy, halfDiagonal)));
        if (hint != null && !sorted.isEmpty()) {
            FaceBox best = null;
            double bestIou = TRACK_MIN_IOU;
            for (FaceBox f : sorted) {
                double iou = f.iou(hint);
                if (iou >= bestIou) {
                    best = f;
                    bestIou = iou;
                }
            }
            if (best != null) {
                sorted.remove(best);
                sorted.add(0, best);
            }
        }
        return sorted;
    }

    private static double priority(FaceBox f, double cx, double cy, double halfDiagonal) {
        double distance = Math.hypot(f.centerX() - cx, f.centerY() - cy) / halfDiagonal;
        return f.area() * (1 - 0.5 * Math.min(1, distance));
    }

    private static boolean finite(double... values) {
        for (double v : values) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "OnnxFaceAnalyzer[" + detector.options() + ", mesh=" + (landmarker != null) + ", " + options + "]";
    }

    /** Analyser settings. */
    public static final class Options {

        private final double minMeshPresence;

        private Options(double minMeshPresence) {
            this.minMeshPresence = minMeshPresence;
        }

        /**
         * Returns the defaults: mesh presence at least 0.5 (MediaPipe's default).
         *
         * @return the default options
         */
        public static Options defaults() {
            return new Options(0.5);
        }

        /**
         * Creates options.
         *
         * @param minMeshPresence lowest Face Mesh presence probability for mesh measures to be used, 0-1
         * @return the options
         */
        public static Options of(double minMeshPresence) {
            if (!(minMeshPresence >= 0 && minMeshPresence <= 1)) {
                throw new IllegalArgumentException("minMeshPresence must be 0-1");
            }
            return new Options(minMeshPresence);
        }

        /**
         * Returns the lowest mesh presence probability accepted.
         *
         * @return 0-1
         */
        public double minMeshPresence() {
            return minMeshPresence;
        }

        @Override
        public String toString() {
            return "Options[minMeshPresence=" + minMeshPresence + "]";
        }
    }
}
