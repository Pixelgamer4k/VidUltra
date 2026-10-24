package dev.vidultra.proto;

/**
 * A damped spring, the motion model iOS uses for its interface animations: the value is pulled
 * toward a target with a natural period (response) and damping ratio, so it starts quickly,
 * eases in, and (when underdamped) overshoots a touch before settling. Driven by the owner's
 * frame callback: {@link #step(double)} each frame, until {@link #settled()}.
 */
final class Spring {
    double value, target, velocity;
    private final double omega, zeta;

    /**
     * @param response seconds for one period (iOS default ~0.55; snappy buttons ~0.3)
     * @param damping  1 = critically damped (no overshoot), 0.7-0.85 = a gentle bounce
     */
    Spring(double response, double damping, double initial) {
        omega = 2 * Math.PI / response;
        zeta = damping;
        value = target = initial;
    }

    Spring to(double t) {
        target = t;
        return this;
    }

    /** Jump without animating. */
    void snap(double v) {
        value = target = v;
        velocity = 0;
    }

    /** Advances dt seconds (sub-stepped for stability). @return true while still moving */
    boolean step(double dt) {
        dt = Math.min(dt, 0.05);
        int n = (int) Math.ceil(dt / 0.004);
        double h = dt / n;
        for (int i = 0; i < n; i++) {
            double a = -omega * omega * (value - target) - 2 * zeta * omega * velocity;
            velocity += a * h;
            value += velocity * h;
        }
        if (settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < 1e-3 && Math.abs(velocity) < 1e-2;
    }

    float f() {
        return (float) value;
    }
}
