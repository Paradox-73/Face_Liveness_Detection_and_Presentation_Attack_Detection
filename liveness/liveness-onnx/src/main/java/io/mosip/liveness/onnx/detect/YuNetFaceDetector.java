package io.mosip.liveness.onnx.detect;

import io.mosip.liveness.core.EngineErrorCode;
import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.vision.FaceBox;
import io.mosip.liveness.core.vision.FacePoint;
import io.mosip.liveness.onnx.image.ImageOps;
import io.mosip.liveness.onnx.image.Rgb8Image;
import io.mosip.liveness.onnx.image.TensorFormat;
import io.mosip.liveness.onnx.runtime.OrtModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * YuNet face detector (OpenCV Zoo {@code face_detection_yunet_2026may.onnx}): face boxes, detector scores and
 * five landmarks.
 *
 * <p>Preprocessing and decoding follow OpenCV {@code FaceDetectorYN}: the image is scaled so its longer side is
 * at most {@link Options#maxInputSide()}, placed in the top-left corner of a zero tensor whose height and width
 * are multiples of 32, fed as BGR with raw 0-255 values, and decoded per stride (8, 16, 32) as
 * {@code score = sqrt(cls * obj)}, centre {@code (col + dx, row + dy) * stride}, size
 * {@code exp(dw, dh) * stride}, landmarks {@code (kx + col, ky + row) * stride}; then greedy non-maximum
 * suppression. Landmarks come in {@link io.mosip.liveness.core.vision.Landmark5} order (subject's right eye
 * first). Coordinates are in pixels of the input image.
 *
 * <p>Calls are serialised; one instance may be shared.
 */
public final class YuNetFaceDetector {

    private static final int[] STRIDES = {8, 16, 32};
    private static final int ALIGN = 32;
    private static final Comparator<Candidate> BY_SCORE = (a, b) -> Float.compare(b.score, a.score);

    private final OrtModel model;
    private final Options options;
    private float[] tensor = new float[0];
    private int tensorWidth;
    private int tensorHeight;
    private int imageWidth;
    private int imageHeight;

    /**
     * Creates a detector.
     *
     * @param model   the loaded YuNet model
     * @param options thresholds and input size
     */
    public YuNetFaceDetector(OrtModel model, Options options) {
        this.model = Objects.requireNonNull(model, "model");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Returns the options.
     *
     * @return the options
     */
    public Options options() {
        return options;
    }

    /**
     * Detects faces.
     *
     * @param image the image
     * @return faces with score at least {@link Options#scoreThreshold()}, highest score first, at most
     *         {@link Options#maxFaces()}
     * @throws EngineException if inference fails or the outputs do not have the expected shape
     */
    public synchronized List<FaceBox> detect(Rgb8Image image) throws EngineException {
        double scale = options.maxInputSide() <= 0 ? 1.0
                : Math.min(1.0, (double) options.maxInputSide() / Math.max(image.width(), image.height()));
        int w = Math.max(1, (int) Math.round(image.width() * scale));
        int h = Math.max(1, (int) Math.round(image.height() * scale));
        prepareTensor(w, h);
        ImageOps.resizeRegionToTensor(image, 0, 0, image.width() - 1, image.height() - 1, w, h, TensorFormat.BGR_RAW,
                tensor, tensorWidth, tensorHeight);
        Map<String, float[]> out = model.run(tensor, new long[] {1, 3, tensorHeight, tensorWidth});
        List<Candidate> candidates = new ArrayList<>();
        for (int stride : STRIDES) {
            decodeStride(out, stride, candidates);
        }
        candidates.sort(BY_SCORE);
        List<Candidate> kept = nonMaximumSuppression(candidates, options.nmsThreshold(), options.maxFaces());
        double sx = (double) image.width() / w;
        double sy = (double) image.height() / h;
        List<FaceBox> faces = new ArrayList<>(kept.size());
        for (Candidate c : kept) {
            faces.add(c.toFaceBox(sx, sy));
        }
        return faces;
    }

    private void prepareTensor(int width, int height) {
        int alignedWidth = align(width);
        int alignedHeight = align(height);
        if (alignedWidth != tensorWidth || alignedHeight != tensorHeight) {
            tensorWidth = alignedWidth;
            tensorHeight = alignedHeight;
            tensor = new float[3 * alignedWidth * alignedHeight];
        } else if (width != imageWidth || height != imageHeight) {
            Arrays.fill(tensor, 0f);
        }
        // The padding right of and below the scaled image stays zero; the image area is overwritten on each call.
        imageWidth = width;
        imageHeight = height;
    }

    private void decodeStride(Map<String, float[]> out, int stride, List<Candidate> candidates) throws EngineException {
        int cols = tensorWidth / stride;
        int rows = tensorHeight / stride;
        float[] cls = output(out, "cls_" + stride, rows * cols);
        float[] obj = output(out, "obj_" + stride, rows * cols);
        float[] bbox = output(out, "bbox_" + stride, rows * cols * 4);
        float[] kps = output(out, "kps_" + stride, rows * cols * 10);
        float threshold = (float) options.scoreThreshold();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                float score = (float) Math.sqrt(clamp01(cls[i]) * clamp01(obj[i]));
                if (score < threshold) {
                    continue;
                }
                float cx = (c + bbox[i * 4]) * stride;
                float cy = (r + bbox[i * 4 + 1]) * stride;
                float bw = (float) Math.exp(bbox[i * 4 + 2]) * stride;
                float bh = (float) Math.exp(bbox[i * 4 + 3]) * stride;
                float[] lm = new float[10];
                for (int n = 0; n < 5; n++) {
                    lm[2 * n] = (kps[i * 10 + 2 * n] + c) * stride;
                    lm[2 * n + 1] = (kps[i * 10 + 2 * n + 1] + r) * stride;
                }
                candidates.add(new Candidate(cx - bw / 2, cy - bh / 2, bw, bh, score, lm));
            }
        }
    }

    private static float[] output(Map<String, float[]> out, String name, int expected) throws EngineException {
        float[] v = out.get(name);
        if (v == null || v.length != expected) {
            throw new EngineException(EngineErrorCode.INFERENCE,
                    "YuNet output " + name + " missing or of unexpected size");
        }
        return v;
    }

    static List<Candidate> nonMaximumSuppression(List<Candidate> sorted, double iouThreshold, int maxFaces) {
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : sorted) {
            boolean overlaps = false;
            for (Candidate k : kept) {
                if (c.iou(k) > iouThreshold) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(c);
                if (kept.size() >= maxFaces) {
                    break;
                }
            }
        }
        return kept;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int align(int v) {
        return ((v + ALIGN - 1) / ALIGN) * ALIGN;
    }

    /** One raw detection in detector input coordinates. */
    static final class Candidate {
        final float x;
        final float y;
        final float w;
        final float h;
        final float score;
        final float[] landmarks;

        Candidate(float x, float y, float w, float h, float score, float[] landmarks) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.score = score;
            this.landmarks = landmarks;
        }

        double iou(Candidate o) {
            double ix = Math.max(0, Math.min(x + w, o.x + o.w) - Math.max(x, o.x));
            double iy = Math.max(0, Math.min(y + h, o.y + o.h) - Math.max(y, o.y));
            double inter = ix * iy;
            double union = (double) w * h + (double) o.w * o.h - inter;
            return union <= 0 ? 0 : inter / union;
        }

        FaceBox toFaceBox(double sx, double sy) {
            List<FacePoint> points = new ArrayList<>(5);
            for (int n = 0; n < 5; n++) {
                points.add(FacePoint.of(landmarks[2 * n] * sx, landmarks[2 * n + 1] * sy));
            }
            return FaceBox.of(x * sx, y * sy, w * sx, h * sy, Math.min(1.0, score)).withLandmarks(points);
        }
    }

    /** Detector settings. */
    public static final class Options {

        private final double scoreThreshold;
        private final double nmsThreshold;
        private final int maxInputSide;
        private final int maxFaces;

        private Options(double scoreThreshold, double nmsThreshold, int maxInputSide, int maxFaces) {
            this.scoreThreshold = scoreThreshold;
            this.nmsThreshold = nmsThreshold;
            this.maxInputSide = maxInputSide;
            this.maxFaces = maxFaces;
        }

        /**
         * Returns the defaults: score 0.7, NMS IoU 0.3, longer input side 320 px, at most 10 faces.
         *
         * @return the default options
         */
        public static Options defaults() {
            return new Options(0.7, 0.3, 320, 10);
        }

        /**
         * Creates options.
         *
         * @param scoreThreshold lowest detector score kept, 0-1
         * @param nmsThreshold   boxes overlapping a better box by more than this IoU are dropped, 0-1
         * @param maxInputSide   longer side of the detector input in pixels (images are scaled down to it, never
         *                       up); 0 runs at full resolution
         * @param maxFaces       most faces returned; positive
         * @return the options
         * @throws IllegalArgumentException if a value is out of range
         */
        public static Options of(double scoreThreshold, double nmsThreshold, int maxInputSide, int maxFaces) {
            if (!(scoreThreshold >= 0 && scoreThreshold <= 1) || !(nmsThreshold >= 0 && nmsThreshold <= 1)
                    || maxInputSide < 0 || maxFaces < 1) {
                throw new IllegalArgumentException("invalid YuNet options");
            }
            return new Options(scoreThreshold, nmsThreshold, maxInputSide, maxFaces);
        }

        /**
         * Returns the lowest detector score kept.
         *
         * @return 0-1
         */
        public double scoreThreshold() {
            return scoreThreshold;
        }

        /**
         * Returns the non-maximum suppression overlap limit.
         *
         * @return IoU 0-1
         */
        public double nmsThreshold() {
            return nmsThreshold;
        }

        /**
         * Returns the longer side of the detector input.
         *
         * @return pixels; 0 means full resolution
         */
        public int maxInputSide() {
            return maxInputSide;
        }

        /**
         * Returns the most faces returned.
         *
         * @return a positive count
         */
        public int maxFaces() {
            return maxFaces;
        }

        @Override
        public String toString() {
            return "YuNet.Options[score=" + scoreThreshold + ", nms=" + nmsThreshold + ", maxSide=" + maxInputSide
                    + ", maxFaces=" + maxFaces + "]";
        }
    }
}
