package ru.moshackathon.heatnetwork.solver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Exact-in-double 1D clearance computation for a ray against finite segments.
 * Geometry-independent: no JTS, raster, probe step, or fixed 160 m limit.
 * The caller MUST restrict [from, to] to the first interval outside the owner
 * material and MUST validate returned candidates against all other restrictions.
 */
final class RayClearanceIntervals {
    private static final double EPS = 1e-9;

    static final class Interval {
        final double from;
        final double to;
        Interval(double from, double to) { this.from = from; this.to = to; }
    }

    private RayClearanceIntervals() { }

    static List<Interval> free(double ox, double oy, double ux, double uy,
                               double from, double to, double clearance,
                               List<double[]> segments) {
        if (!Double.isFinite(ox) || !Double.isFinite(oy)
                || !Double.isFinite(ux) || !Double.isFinite(uy)
                || !Double.isFinite(from) || !Double.isFinite(to)
                || !Double.isFinite(clearance) || clearance < 0.0) {
            throw new IllegalArgumentException("finite ray and nonnegative clearance required");
        }
        double norm = Math.hypot(ux, uy);
        if (Math.abs(norm - 1.0) > 1e-6) {
            throw new IllegalArgumentException("ray direction must be normalized");
        }
        if (from >= to) { return Collections.emptyList(); }
        List<Interval> forbidden = new ArrayList<>();
        for (double[] s : segments) {
            if (s.length != 4) { throw new IllegalArgumentException("segment needs 4 coordinates"); }
            for (double value : s) {
                if (!Double.isFinite(value)) { throw new IllegalArgumentException("nonfinite segment"); }
            }
            addCircle(forbidden, ox, oy, ux, uy, s[0], s[1], clearance, from, to);
            addCircle(forbidden, ox, oy, ux, uy, s[2], s[3], clearance, from, to);
            double dx = s[2] - s[0];
            double dy = s[3] - s[1];
            double length = Math.hypot(dx, dy);
            if (length <= EPS) { continue; }
            double vx = dx / length;
            double vy = dy / length;
            double wx = ox - s[0];
            double wy = oy - s[1];
            // Intersection with the rectangular part of the segment's capsule.
            double[] span = {from, to};
            if (clip(span, wx * vx + wy * vy, ux * vx + uy * vy, 0.0, length)
                    && clip(span, vx * wy - vy * wx, vx * uy - vy * ux,
                            -clearance, clearance)) {
                add(forbidden, span[0], span[1], from, to);
            }
        }
        forbidden.sort(Comparator.comparingDouble(i -> i.from));
        List<Interval> free = new ArrayList<>();
        double cursor = from;
        for (Interval occupied : forbidden) {
            if (occupied.from > cursor + EPS) {
                free.add(new Interval(cursor, occupied.from));
            }
            cursor = Math.max(cursor, occupied.to);
            if (cursor >= to - EPS) { break; }
        }
        if (cursor < to - EPS) { free.add(new Interval(cursor, to)); }
        return Collections.unmodifiableList(free);
    }

    private static void addCircle(List<Interval> out, double ox, double oy,
                                   double ux, double uy, double cx, double cy,
                                   double r, double from, double to) {
        // Coordinates are local to the origin: avoid large-UTM cancellation.
        double dx = cx - ox;
        double dy = cy - oy;
        double projection = dx * ux + dy * uy;
        double perpendicular = dx * uy - dy * ux;
        double discriminant = r * r - perpendicular * perpendicular;
        if (discriminant < -EPS) { return; }
        double half = Math.sqrt(Math.max(0.0, discriminant));
        add(out, projection - half, projection + half, from, to);
    }

    private static boolean clip(double[] span, double offset, double slope,
                                 double lower, double upper) {
        if (Math.abs(slope) <= 1e-14) {
            return offset >= lower - EPS && offset <= upper + EPS;
        }
        double a = (lower - offset) / slope;
        double b = (upper - offset) / slope;
        span[0] = Math.max(span[0], Math.min(a, b));
        span[1] = Math.min(span[1], Math.max(a, b));
        return span[0] <= span[1];
    }

    private static void add(List<Interval> out, double a, double b, double from, double to) {
        a = Math.max(a, from);
        b = Math.min(b, to);
        if (a <= b) { out.add(new Interval(a, b)); }
    }
}
