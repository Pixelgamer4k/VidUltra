package dev.vidultra.proto;

/** Unit quaternion helpers, [w, x, y, z], double precision. */
final class Quat {
    private Quat() {}

    static double[] fromRotVec(double x, double y, double z) {
        double a = Math.sqrt(x * x + y * y + z * z);
        if (a < 1e-12) return new double[]{1, x * 0.5, y * 0.5, z * 0.5};
        double s = Math.sin(a * 0.5) / a;
        return new double[]{Math.cos(a * 0.5), x * s, y * s, z * s};
    }

    /** Rotation vector (axis * angle) of a unit quaternion. */
    static double[] toRotVec(double[] q) {
        double w = q[0], x = q[1], y = q[2], z = q[3];
        if (w < 0) {   // shortest path
            w = -w;
            x = -x;
            y = -y;
            z = -z;
        }
        double s = Math.sqrt(x * x + y * y + z * z);
        if (s < 1e-12) return new double[]{2 * x, 2 * y, 2 * z};
        double a = 2 * Math.atan2(s, w);
        return new double[]{x / s * a, y / s * a, z / s * a};
    }

    static double[] mul(double[] a, double[] b) {
        return new double[]{
                a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
                a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
                a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
                a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]};
    }

    static void mulInPlace(double[] a, double[] b) {
        double[] r = mul(a, b);
        System.arraycopy(r, 0, a, 0, 4);
    }

    static double[] conj(double[] q) {
        return new double[]{q[0], -q[1], -q[2], -q[3]};
    }

    static void normalize(double[] q) {
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        for (int i = 0; i < 4; i++) q[i] /= n;
    }

    static void slerp(double[] a, double[] b, double t, double[] out) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        double s = 1;
        if (dot < 0) {
            dot = -dot;
            s = -1;
        }
        double wa, wb;
        if (dot > 0.9995) {
            wa = 1 - t;
            wb = t;
        } else {
            double th = Math.acos(dot), sn = Math.sin(th);
            wa = Math.sin((1 - t) * th) / sn;
            wb = Math.sin(t * th) / sn;
        }
        for (int i = 0; i < 4; i++) out[i] = wa * a[i] + wb * s * b[i];
        normalize(out);
    }

    /** Row-major 3x3 rotation matrix. */
    static double[] toMat(double[] q) {
        double w = q[0], x = q[1], y = q[2], z = q[3];
        return new double[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)};
    }
}
