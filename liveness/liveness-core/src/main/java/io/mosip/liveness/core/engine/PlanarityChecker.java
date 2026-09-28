package io.mosip.liveness.core.engine;

import io.mosip.liveness.core.vision.DetectedFace;
import io.mosip.liveness.core.vision.FaceMesh;
import io.mosip.liveness.core.vision.FacePoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Planarity check on a head turn (design section 4.8, after Wang et al., ICB 2013).
 *
 * <p>The rigid landmarks of the neutral face are mapped onto the turned face with the best plane-to-plane
 * mapping (a homography when at least 8 points are available, an affine map for the five detector landmarks).
 * A flat photo or screen, however it is moved, follows such a mapping, so the residual is only landmark noise.
 * A real head has depth: the nose tip, cheeks and jaw move by different amounts, and the residual is larger.
 * The result is the RMS residual divided by the inter-eye distance, compared with a calibrated minimum.
 */
final class PlanarityChecker {

    /**
     * Face Mesh indices of rigid points (nose bridge and tip, eye corners, brows, forehead, cheeks, jaw and
     * chin); eyelids and lips are left out because they move with expressions.
     */
    static final int[] RIGID_MESH_POINTS = {
        1, 4, 5, 6, 168, 197, 195, 33, 133, 362, 263, 98, 327, 10, 151, 9,
        234, 454, 93, 323, 132, 361, 58, 288, 152, 175, 199, 70, 300, 105, 334
    };

    private static final int MIN_HOMOGRAPHY_POINTS = 8;
    private static final int MIN_AFFINE_POINTS = 4;

    private PlanarityChecker() {
    }

    /**
     * Returns the rigid landmarks of a face: the mesh subset when a mesh is present, else the five detector
     * landmarks.
     *
     * @param face the face
     * @return the points, or an empty list when the face has no landmarks
     */
    static List<FacePoint> rigidPoints(DetectedFace face) {
        if (face.mesh().isPresent()) {
            FaceMesh mesh = face.mesh().get();
            List<FacePoint> points = new ArrayList<>(RIGID_MESH_POINTS.length);
            for (int i : RIGID_MESH_POINTS) {
                points.add(FacePoint.of(mesh.x(i), mesh.y(i)));
            }
            return points;
        }
        return face.box().hasLandmarks() ? face.box().landmarks() : Collections.<FacePoint>emptyList();
    }

    /**
     * Computes the planarity residual between two views of the same landmarks.
     *
     * @param neutral landmarks of the neutral face
     * @param turned  the same landmarks on the turned face
     * @param ied     inter-eye distance in pixels, for normalisation
     * @return residual RMS divided by the inter-eye distance, or {@code NaN} when it cannot be computed
     */
    static double residual(List<FacePoint> neutral, List<FacePoint> turned, double ied) {
        if (neutral == null || turned == null || neutral.size() != turned.size()
                || neutral.size() < MIN_AFFINE_POINTS + 1 || !(ied > 0)) {
            return Double.NaN;
        }
        double[] map = neutral.size() >= MIN_HOMOGRAPHY_POINTS ? fitHomography(neutral, turned) : fitAffine(neutral, turned);
        if (map == null) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = 0; i < neutral.size(); i++) {
            FacePoint p = neutral.get(i);
            FacePoint q = turned.get(i);
            double w = map[6] * p.x() + map[7] * p.y() + map[8];
            if (Math.abs(w) < 1e-12) {
                return Double.NaN;
            }
            double u = (map[0] * p.x() + map[1] * p.y() + map[2]) / w;
            double v = (map[3] * p.x() + map[4] * p.y() + map[5]) / w;
            sum += (u - q.x()) * (u - q.x()) + (v - q.y()) * (v - q.y());
        }
        return Math.sqrt(sum / neutral.size()) / ied;
    }

    /**
     * Least-squares homography (direct linear transform with h33 = 1) on normalised coordinates.
     *
     * @return the 3x3 matrix row by row, or {@code null} if the points are degenerate
     */
    static double[] fitHomography(List<FacePoint> src, List<FacePoint> dst) {
        double[] ts = normalisation(src);
        double[] td = normalisation(dst);
        int n = src.size();
        double[][] ata = new double[8][8];
        double[] atb = new double[8];
        for (int i = 0; i < n; i++) {
            double x = (src.get(i).x() - ts[0]) * ts[2];
            double y = (src.get(i).y() - ts[1]) * ts[2];
            double u = (dst.get(i).x() - td[0]) * td[2];
            double v = (dst.get(i).y() - td[1]) * td[2];
            accumulate(ata, atb, new double[] {x, y, 1, 0, 0, 0, -x * u, -y * u}, u);
            accumulate(ata, atb, new double[] {0, 0, 0, x, y, 1, -x * v, -y * v}, v);
        }
        double[] h = LinearSolver.solve(ata, atb);
        if (h == null) {
            return null;
        }
        double[] hn = {h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1};
        // H = Td^-1 * Hn * Ts, with Ts = [s 0 -s*cx; 0 s -s*cy; 0 0 1].
        double[] tsM = {ts[2], 0, -ts[2] * ts[0], 0, ts[2], -ts[2] * ts[1], 0, 0, 1};
        double[] tdInv = {1 / td[2], 0, td[0], 0, 1 / td[2], td[1], 0, 0, 1};
        return multiply(tdInv, multiply(hn, tsM));
    }

    /**
     * Least-squares affine map.
     *
     * @return the 3x3 matrix row by row (last row 0 0 1), or {@code null} if the points are degenerate
     */
    static double[] fitAffine(List<FacePoint> src, List<FacePoint> dst) {
        double[][] ata = new double[3][3];
        double[] atbX = new double[3];
        double[] atbY = new double[3];
        for (int i = 0; i < src.size(); i++) {
            double[] row = {src.get(i).x(), src.get(i).y(), 1};
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    ata[r][c] += row[r] * row[c];
                }
                atbX[r] += row[r] * dst.get(i).x();
                atbY[r] += row[r] * dst.get(i).y();
            }
        }
        double[] ax = LinearSolver.solve(copy(ata), atbX);
        double[] ay = LinearSolver.solve(copy(ata), atbY);
        if (ax == null || ay == null) {
            return null;
        }
        return new double[] {ax[0], ax[1], ax[2], ay[0], ay[1], ay[2], 0, 0, 1};
    }

    private static void accumulate(double[][] ata, double[] atb, double[] row, double b) {
        for (int r = 0; r < row.length; r++) {
            for (int c = 0; c < row.length; c++) {
                ata[r][c] += row[r] * row[c];
            }
            atb[r] += row[r] * b;
        }
    }

    /** Returns {centre x, centre y, scale} that moves the centroid to 0 and the mean distance to sqrt(2). */
    private static double[] normalisation(List<FacePoint> pts) {
        double cx = 0;
        double cy = 0;
        for (FacePoint p : pts) {
            cx += p.x();
            cy += p.y();
        }
        cx /= pts.size();
        cy /= pts.size();
        double mean = 0;
        for (FacePoint p : pts) {
            mean += Math.hypot(p.x() - cx, p.y() - cy);
        }
        mean /= pts.size();
        return new double[] {cx, cy, mean > 0 ? Math.sqrt(2) / mean : 1};
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] m = new double[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                m[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return m;
    }

    private static double[][] copy(double[][] m) {
        double[][] c = new double[m.length][];
        for (int i = 0; i < m.length; i++) {
            c[i] = m[i].clone();
        }
        return c;
    }
}
