package io.mosip.liveness.onnx.mesh;

/**
 * Eigen decomposition of a small symmetric matrix by cyclic Jacobi rotations.
 */
final class SymmetricEigen {

    private static final int MAX_SWEEPS = 50;

    private SymmetricEigen() {
    }

    /**
     * Returns a unit eigenvector of the largest eigenvalue.
     *
     * @param matrix symmetric square matrix; not modified
     * @return the eigenvector
     */
    static double[] largestEigenvector(double[][] matrix) {
        int n = matrix.length;
        double[][] a = new double[n][];
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            a[i] = matrix[i].clone();
            v[i][i] = 1;
        }
        for (int sweep = 0; sweep < MAX_SWEEPS && offDiagonal(a) > 1e-22; sweep++) {
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    rotate(a, v, p, q);
                }
            }
        }
        int best = 0;
        for (int i = 1; i < n; i++) {
            if (a[i][i] > a[best][best]) {
                best = i;
            }
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = v[i][best];
        }
        return out;
    }

    private static void rotate(double[][] a, double[][] v, int p, int q) {
        if (Math.abs(a[p][q]) < 1e-300) {
            return;
        }
        double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
        double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
        if (theta == 0) {
            t = 1;
        }
        double c = 1 / Math.sqrt(t * t + 1);
        double s = t * c;
        int n = a.length;
        for (int k = 0; k < n; k++) {
            double akp = a[k][p];
            double akq = a[k][q];
            a[k][p] = c * akp - s * akq;
            a[k][q] = s * akp + c * akq;
        }
        for (int k = 0; k < n; k++) {
            double apk = a[p][k];
            double aqk = a[q][k];
            a[p][k] = c * apk - s * aqk;
            a[q][k] = s * apk + c * aqk;
        }
        for (int k = 0; k < n; k++) {
            double vkp = v[k][p];
            double vkq = v[k][q];
            v[k][p] = c * vkp - s * vkq;
            v[k][q] = s * vkp + c * vkq;
        }
    }

    private static double offDiagonal(double[][] a) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < a.length; j++) {
                if (i != j) {
                    sum += a[i][j] * a[i][j];
                }
            }
        }
        return sum;
    }
}
