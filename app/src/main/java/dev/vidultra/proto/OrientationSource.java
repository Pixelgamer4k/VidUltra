package dev.vidultra.proto;

/** Camera-to-world orientation over time; implemented by {@link Gyro} and by the simulator. */
interface OrientationSource {
    /** Orientation [w, x, y, z] at time t (ns); false if there is no data yet. */
    boolean orientation(long t, double[] out);

    /** Newest timestamp with data (ns). */
    long latest();
}
