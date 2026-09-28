package io.mosip.liveness.onnx.mesh;

import io.mosip.liveness.core.vision.FaceMesh;

/**
 * Eye, mouth and gaze measures from MediaPipe Face Mesh points (image pixels, non-mirrored image).
 *
 * <p>Indices are MediaPipe's; "right" and "left" are the subject's own. The subject's right eye appears on the
 * left of a non-mirrored image. Iris centres are points 468 (right eye) and 473 (left eye).
 */
public final class FaceMeasures {

    /** Right eye, six points for the eye aspect ratio: outer corner, two upper lid, inner corner, two lower lid. */
    static final int[] RIGHT_EYE_EAR = {33, 160, 158, 133, 153, 144};
    /** Left eye, same order as {@link #RIGHT_EYE_EAR}: inner corner, two upper lid, outer corner, two lower lid. */
    static final int[] LEFT_EYE_EAR = {362, 385, 387, 263, 373, 380};
    /** Right mouth corner. */
    static final int MOUTH_RIGHT = 61;
    /** Left mouth corner. */
    static final int MOUTH_LEFT = 291;
    /** Iris centre of the right eye. */
    static final int IRIS_RIGHT = 468;
    /** Iris centre of the left eye. */
    static final int IRIS_LEFT = 473;
    /** Right eye corners, image-left to image-right: outer 33, inner 133; lids: upper 159, lower 145. */
    static final int[] RIGHT_EYE_BOX = {33, 133, 159, 145};
    /** Left eye corners, image-left to image-right: inner 362, outer 263; lids: upper 386, lower 374. */
    static final int[] LEFT_EYE_BOX = {362, 263, 386, 374};

    private FaceMeasures() {
    }

    /**
     * Eye aspect ratio (Soukupova and Cech 2016): {@code (|p2-p6| + |p3-p5|) / (2 |p1-p4|)} on the subject's right
     * eye. About 0.25-0.35 when open, below 0.2 when closed.
     *
     * @param mesh Face Mesh
     * @return the ratio, 0 or more
     */
    public static double rightEyeAspectRatio(FaceMesh mesh) {
        return eyeAspectRatio(mesh, RIGHT_EYE_EAR);
    }

    /**
     * Eye aspect ratio of the subject's left eye; see {@link #rightEyeAspectRatio(FaceMesh)}.
     *
     * @param mesh Face Mesh
     * @return the ratio, 0 or more
     */
    public static double leftEyeAspectRatio(FaceMesh mesh) {
        return eyeAspectRatio(mesh, LEFT_EYE_EAR);
    }

    private static double eyeAspectRatio(FaceMesh m, int[] p) {
        double width = distance(m, p[0], p[3]);
        if (width < 1e-6) {
            return 0;
        }
        return (distance(m, p[1], p[5]) + distance(m, p[2], p[4])) / (2 * width);
    }

    /**
     * Distance between the eye centres: the iris centres of a 478-point mesh, otherwise the midpoints of the eye
     * corners.
     *
     * @param mesh Face Mesh
     * @return pixels
     */
    public static double interEyeDistance(FaceMesh mesh) {
        if (mesh.hasIris()) {
            return distance(mesh, IRIS_RIGHT, IRIS_LEFT);
        }
        double rx = (mesh.x(33) + mesh.x(133)) / 2.0;
        double ry = (mesh.y(33) + mesh.y(133)) / 2.0;
        double lx = (mesh.x(362) + mesh.x(263)) / 2.0;
        double ly = (mesh.y(362) + mesh.y(263)) / 2.0;
        return Math.hypot(lx - rx, ly - ry);
    }

    /**
     * Mouth-corner distance divided by the inter-eye distance; rises when the subject smiles.
     *
     * @param mesh Face Mesh
     * @return the ratio, 0 or more
     */
    public static double mouthWidthRatio(FaceMesh mesh) {
        double ied = interEyeDistance(mesh);
        return ied < 1e-6 ? 0 : distance(mesh, MOUTH_RIGHT, MOUTH_LEFT) / ied;
    }

    /**
     * Gaze direction from the iris centres, averaged over both eyes and clamped to -1..1.
     *
     * <p>Horizontal: iris offset from the midpoint of the eye corners along the corner line, divided by half the
     * eye width; positive towards the subject's own left (image right). Vertical: offset perpendicular to the
     * corner line divided by half the lid opening; positive upwards.
     *
     * @param mesh a 478-point Face Mesh
     * @return {@code {horizontal, vertical}}
     * @throws IllegalArgumentException if the mesh has no iris points
     */
    public static double[] gaze(FaceMesh mesh) {
        if (!mesh.hasIris()) {
            throw new IllegalArgumentException("gaze needs the 478-point mesh with iris");
        }
        double[] r = eyeGaze(mesh, RIGHT_EYE_BOX, IRIS_RIGHT);
        double[] l = eyeGaze(mesh, LEFT_EYE_BOX, IRIS_LEFT);
        return new double[] {clamp((r[0] + l[0]) / 2), clamp((r[1] + l[1]) / 2)};
    }

    private static double[] eyeGaze(FaceMesh m, int[] box, int iris) {
        double ax = m.x(box[0]);
        double ay = m.y(box[0]);
        double bx = m.x(box[1]);
        double by = m.y(box[1]);
        double width = Math.hypot(bx - ax, by - ay);
        if (width < 1e-6) {
            return new double[] {0, 0};
        }
        double ux = (bx - ax) / width;
        double uy = (by - ay) / width;
        // Unit vector pointing up in the image, perpendicular to the corner line.
        double nx = uy;
        double ny = -ux;
        double cx = (ax + bx) / 2;
        double cy = (ay + by) / 2;
        double dx = m.x(iris) - cx;
        double dy = m.y(iris) - cy;
        double halfHeight = Math.abs((m.x(box[2]) - m.x(box[3])) * nx + (m.y(box[2]) - m.y(box[3])) * ny) / 2;
        double horizontal = (dx * ux + dy * uy) / (width / 2);
        double vertical = halfHeight < 1e-6 ? 0 : (dx * nx + dy * ny) / halfHeight;
        return new double[] {horizontal, vertical};
    }

    private static double distance(FaceMesh m, int a, int b) {
        return Math.hypot(m.x(a) - m.x(b), m.y(a) - m.y(b));
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }
}
