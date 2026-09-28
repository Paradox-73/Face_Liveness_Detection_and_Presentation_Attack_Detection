package io.mosip.liveness.onnx.image;

import java.util.Objects;

/**
 * A 2-D affine mapping {@code x' = a*x + b*y + c}, {@code y' = d*x + e*y + f}, in pixel coordinates with the
 * origin at the centre of the top-left pixel (the OpenCV {@code warpAffine} convention).
 *
 * <p>Immutable. Used to cut rotated face crops for Face Mesh, to align faces for SFace on five landmarks, and
 * to map model outputs back to image coordinates.
 */
public final class AffineTransform {

    private static final AffineTransform IDENTITY = new AffineTransform(1, 0, 0, 0, 1, 0);

    private final double a;
    private final double b;
    private final double c;
    private final double d;
    private final double e;
    private final double f;

    private AffineTransform(double a, double b, double c, double d, double e, double f) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.e = e;
        this.f = f;
    }

    /**
     * Creates a transform from its six coefficients.
     *
     * @param a x' coefficient of x
     * @param b x' coefficient of y
     * @param c x' offset
     * @param d y' coefficient of x
     * @param e y' coefficient of y
     * @param f y' offset
     * @return the transform
     * @throws IllegalArgumentException if a coefficient is not finite
     */
    public static AffineTransform of(double a, double b, double c, double d, double e, double f) {
        for (double v : new double[] {a, b, c, d, e, f}) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("coefficients must be finite");
            }
        }
        return new AffineTransform(a, b, c, d, e, f);
    }

    /**
     * Returns the identity transform.
     *
     * @return the identity
     */
    public static AffineTransform identity() {
        return IDENTITY;
    }

    /**
     * Creates the similarity that rotates by {@code angleRad} and scales by {@code scale} around
     * ({@code cx}, {@code cy}), then moves that point to ({@code tx}, {@code ty}).
     *
     * @param scale    uniform scale; positive
     * @param angleRad rotation in radians; positive turns the x axis towards the y axis (clockwise on screen,
     *                 because y points down)
     * @param cx       centre x in the source
     * @param cy       centre y in the source
     * @param tx       where the centre lands, x
     * @param ty       where the centre lands, y
     * @return the transform
     */
    public static AffineTransform rotateScaleAbout(double scale, double angleRad, double cx, double cy, double tx,
                                                   double ty) {
        double cos = Math.cos(angleRad) * scale;
        double sin = Math.sin(angleRad) * scale;
        // [cos -sin; sin cos] applied to (p - centre), then + target.
        return of(cos, -sin, tx - cos * cx + sin * cy, sin, cos, ty - sin * cx - cos * cy);
    }

    /**
     * Estimates the least-squares similarity (rotation, uniform scale, translation, no reflection) that maps
     * {@code src} points onto {@code dst} points. This is the Umeyama estimate that OpenCV
     * {@code FaceRecognizerSF.alignCrop} uses for the five-point face alignment.
     *
     * @param src source points as {@code x0, y0, x1, y1, ...}; at least two distinct points
     * @param dst target points in the same layout and count
     * @return the transform from source to target
     * @throws IllegalArgumentException if the arrays differ in length, hold fewer than two points, or the source
     *                                  points all coincide
     */
    public static AffineTransform estimateSimilarity(double[] src, double[] dst) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dst, "dst");
        if (src.length != dst.length || src.length < 4 || src.length % 2 != 0) {
            throw new IllegalArgumentException("need the same even number of coordinates, at least two points");
        }
        int n = src.length / 2;
        double msx = 0;
        double msy = 0;
        double mdx = 0;
        double mdy = 0;
        for (int i = 0; i < n; i++) {
            msx += src[2 * i];
            msy += src[2 * i + 1];
            mdx += dst[2 * i];
            mdy += dst[2 * i + 1];
        }
        msx /= n;
        msy /= n;
        mdx /= n;
        mdy /= n;
        double dot = 0;
        double cross = 0;
        double norm = 0;
        for (int i = 0; i < n; i++) {
            double sx = src[2 * i] - msx;
            double sy = src[2 * i + 1] - msy;
            double dx = dst[2 * i] - mdx;
            double dy = dst[2 * i + 1] - mdy;
            dot += sx * dx + sy * dy;
            cross += sx * dy - sy * dx;
            norm += sx * sx + sy * sy;
        }
        if (norm <= 1e-12) {
            throw new IllegalArgumentException("source points coincide");
        }
        double p = dot / norm;
        double q = cross / norm;
        return of(p, -q, mdx - (p * msx - q * msy), q, p, mdy - (q * msx + p * msy));
    }

    /**
     * Maps the x coordinate of a point.
     *
     * @param x source x
     * @param y source y
     * @return target x
     */
    public double mapX(double x, double y) {
        return a * x + b * y + c;
    }

    /**
     * Maps the y coordinate of a point.
     *
     * @param x source x
     * @param y source y
     * @return target y
     */
    public double mapY(double x, double y) {
        return d * x + e * y + f;
    }

    /**
     * Returns the uniform scale factor of the linear part (square root of the absolute determinant).
     *
     * @return the scale; 1 for rotations and translations
     */
    public double scale() {
        return Math.sqrt(Math.abs(a * e - b * d));
    }

    /**
     * Returns the inverse transform.
     *
     * @return the inverse
     * @throws IllegalStateException if the transform is singular
     */
    public AffineTransform invert() {
        double det = a * e - b * d;
        if (Math.abs(det) < 1e-12) {
            throw new IllegalStateException("singular transform");
        }
        double ia = e / det;
        double ib = -b / det;
        double id = -d / det;
        double ie = a / det;
        return new AffineTransform(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f));
    }

    /**
     * Returns the six coefficients {@code a, b, c, d, e, f}.
     *
     * @return a new array
     */
    public double[] coefficients() {
        return new double[] {a, b, c, d, e, f};
    }

    @Override
    public String toString() {
        return "AffineTransform[" + a + ", " + b + ", " + c + "; " + d + ", " + e + ", " + f + "]";
    }
}
