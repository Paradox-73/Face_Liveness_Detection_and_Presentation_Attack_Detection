package io.mosip.liveness.core.engine;

/**
 * Solves small dense linear systems by Gaussian elimination with partial pivoting (used for the least-squares
 * normal equations of the planarity check).
 */
final class LinearSolver {

    private static final double SINGULAR = 1e-12;

    private LinearSolver() {
    }

    /**
     * Solves {@code a * x = b}. Both arguments are overwritten.
     *
     * @param a square matrix
     * @param b right-hand side
     * @return the solution, or {@code null} if the matrix is singular or the result is not finite
     */
    static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][col]) < SINGULAR) {
                return null;
            }
            double[] rowTmp = a[col];
            a[col] = a[pivot];
            a[pivot] = rowTmp;
            double bTmp = b[col];
            b[col] = b[pivot];
            b[pivot] = bTmp;
            for (int r = col + 1; r < n; r++) {
                double f = a[r][col] / a[col][col];
                for (int c = col; c < n; c++) {
                    a[r][c] -= f * a[col][c];
                }
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int c = r + 1; c < n; c++) {
                s -= a[r][c] * x[c];
            }
            x[r] = s / a[r][r];
            if (Double.isNaN(x[r]) || Double.isInfinite(x[r])) {
                return null;
            }
        }
        return x;
    }
}
