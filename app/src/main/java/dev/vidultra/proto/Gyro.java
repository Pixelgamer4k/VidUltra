package dev.vidultra.proto;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * High-rate gyro integrated into the camera's orientation over time, in the camera frame
 * (X = raw image right, Y = raw image down, Z = into the scene).
 *
 * Orientation is stored as camera-to-world unit quaternions [w, x, y, z] in a ring buffer,
 * timestamped on the sensor clock (CLOCK_BOOTTIME, the same clock as Camera2 REALTIME).
 */
final class Gyro implements SensorEventListener, OrientationSource {
    private static final String TAG = "VIDULTRA";
    private static final int CAP = 4096;          // ~10 s at 400 Hz

    private final SensorManager sm;
    private final HandlerThread thread = new HandlerThread("gyro");
    // device-axis -> camera-axis mapping (row-major 3x3, entries -1/0/1)
    private final double[] map;

    private final long[] ts = new long[CAP];
    private final double[][] q = new double[CAP][4];
    private int head = -1, count;
    private long lastT;
    private final double[] lastW = new double[3];
    private volatile double rate;      // smoothed angular speed, rad/s

    /** Camera angular speed (rad/s), smoothed over ~0.1 s. */
    double rate() {
        return rate;
    }
    private final double[] cur = {1, 0, 0, 0};
    private int samples;
    private long rateStart;
    // While recording: raw camera-frame rates for offline analysis (t_ns, wx, wy, wz rad/s).
    private java.io.Writer log;

    synchronized void setLog(java.io.Writer w) {
        if (log != null) {
            try {
                log.close();
            } catch (java.io.IOException ignored) {
            }
        }
        log = w;
    }

    /**
     * @param sensorOrientation Camera2 SENSOR_ORIENTATION
     * @param front             LENS_FACING_FRONT: the camera looks along +z instead of -z
     */
    Gyro(Context ctx, int sensorOrientation, boolean front) {
        sm = ctx.getSystemService(SensorManager.class);
        // Raw-image axes vs device axes (device: x right, y up, z out of the screen in natural
        // orientation). A back camera looks along -z; the raw image is rotated by the sensor
        // orientation relative to the device's natural orientation.
        map = cameraMap(sensorOrientation, front);

    }

    /**
     * Rows: the raw image's x, y and optical axes in device coordinates. A back camera looks
     * along -z, rotated by its sensor orientation. A front camera is a back camera of
     * orientation (360 - o) turned 180 degrees about the device's y axis: device x and z flip
     * sign (front 270 -> camera x = -y, camera y = +x, camera z = +z; right-handed).
     */
    static double[] cameraMap(int sensorOrientation, boolean front) {
        int o = front ? (360 - sensorOrientation) % 360 : sensorOrientation;
        double[] m;
        switch (o) {
            case 0: m = new double[]{1, 0, 0, 0, -1, 0, 0, 0, -1}; break;
            case 180: m = new double[]{-1, 0, 0, 0, 1, 0, 0, 0, -1}; break;
            case 270: m = new double[]{0, 1, 0, 1, 0, 0, 0, 0, -1}; break;
            default: m = new double[]{0, -1, 0, -1, 0, 0, 0, 0, -1}; break;   // 90
        }
        if (front) {
            for (int r = 0; r < 3; r++) {
                m[r * 3] = -m[r * 3];
                m[r * 3 + 2] = -m[r * 3 + 2];
            }
        }
        return m;
    }

    void start() {
        thread.start();
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (s == null) {
            Log.e(TAG, "no gyroscope");
            return;
        }
        // 0 us = fastest the sensor allows (needs HIGH_SAMPLING_RATE_SENSORS above 200 Hz)
        sm.registerListener(this, s, 0, new Handler(thread.getLooper()));
        Log.i(TAG, "gyro: " + s.getName() + " minDelay=" + s.getMinDelay() + "us");
    }

    void stop() {
        sm.unregisterListener(this);
        thread.quitSafely();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        double wx = map[0] * e.values[0] + map[1] * e.values[1] + map[2] * e.values[2];
        double wy = map[3] * e.values[0] + map[4] * e.values[1] + map[5] * e.values[2];
        double wz = map[6] * e.values[0] + map[7] * e.values[1] + map[8] * e.values[2];
        synchronized (this) {
            if (lastT != 0) {
                double dt = (e.timestamp - lastT) * 1e-9;
                if (dt > 0 && dt < 0.05) {
                    // body-frame rate, trapezoidal: C(t+dt) = C(t) * exp(mean(w) dt).
                    // First-order (one sample per interval) drifts under multi-axis wobble.
                    double ax = 0.5 * (wx + lastW[0]), ay = 0.5 * (wy + lastW[1]), az = 0.5 * (wz + lastW[2]);
                    Quat.mulInPlace(cur, Quat.fromRotVec(ax * dt, ay * dt, az * dt));
                    Quat.normalize(cur);
                }
            }
            if (lastT != 0) {
                double dt = (e.timestamp - lastT) * 1e-9;
                double a = Math.min(1, dt / 0.1);
                rate += (Math.sqrt(wx * wx + wy * wy + wz * wz) - rate) * a;
            }
            lastT = e.timestamp;
            lastW[0] = wx;
            lastW[1] = wy;
            lastW[2] = wz;
            head = (head + 1) % CAP;
            ts[head] = e.timestamp;
            System.arraycopy(cur, 0, q[head], 0, 4);
            if (count < CAP) count++;
            if (log != null) {
                try {
                    log.write(e.timestamp + "," + (float) wx + "," + (float) wy + "," + (float) wz + "\n");
                } catch (java.io.IOException ex) {
                    log = null;
                }
            }
        }
        if (++samples == 1) rateStart = e.timestamp;
        if (e.timestamp - rateStart > 5_000_000_000L) {
            Log.i(TAG, String.format(java.util.Locale.US, "gyro rate %.0f Hz",
                    samples / ((e.timestamp - rateStart) * 1e-9)));
            samples = 0;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** Orientation at the newest gyro sample, or null before the first one. */
    public synchronized double[] latestOrientation() {
        double[] q = new double[4];
        return orientation(latest(), q) ? q : null;
    }

    /** Newest gyro timestamp, or 0. */
    @Override
    public synchronized long latest() {
        return head < 0 ? 0 : ts[head];
    }

    /** Camera-to-world orientation at time t (slerp between samples; clamps at the ends). */
    @Override
    public synchronized boolean orientation(long t, double[] out) {
        if (count < 2) return false;
        int oldest = (head - count + 1 + CAP) % CAP;
        if (t <= ts[oldest]) {
            System.arraycopy(q[oldest], 0, out, 0, 4);
            return true;
        }
        if (t >= ts[head]) {
            System.arraycopy(q[head], 0, out, 0, 4);
            return true;
        }
        // binary search over the ring (logical index 0..count-1)
        int lo = 0, hi = count - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ts[(oldest + mid) % CAP] <= t) lo = mid; else hi = mid;
        }
        int a = (oldest + lo) % CAP, b = (oldest + hi) % CAP;
        double f = (double) (t - ts[a]) / (ts[b] - ts[a]);
        Quat.slerp(q[a], q[b], f, out);
        return true;
    }
}
