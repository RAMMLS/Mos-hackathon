package ru.moshackathon.heatnetwork.geo;

import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;

@Component
public class MetricProjector {
    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257223563;
    private static final double K0 = 0.9996;
    private static final double E2 = F * (2.0 - F);
    private static final double EP2 = E2 / (1.0 - E2);
    private static final double LON0 = Math.toRadians(39.0);

    public Coordinate toMetric(Coordinate lonLat) {
        double lat = Math.toRadians(lonLat.y);
        double lon = Math.toRadians(lonLat.x);
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double tanLat = Math.tan(lat);
        double n = A / Math.sqrt(1.0 - E2 * sinLat * sinLat);
        double t = tanLat * tanLat;
        double c = EP2 * cosLat * cosLat;
        double a = cosLat * (lon - LON0);
        double m = meridionalArc(lat);

        double easting = K0 * n * (a + (1 - t + c) * Math.pow(a, 3) / 6.0
                + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * Math.pow(a, 5) / 120.0) + 500000.0;
        double northing = K0 * (m + n * tanLat * (a * a / 2.0
                + (5 - t + 9 * c + 4 * c * c) * Math.pow(a, 4) / 24.0
                + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * Math.pow(a, 6) / 720.0));
        return new Coordinate(easting, northing);
    }

    public Coordinate toLonLat(Coordinate metric) {
        double x = metric.x - 500000.0;
        double y = metric.y;
        double m = y / K0;
        double mu = m / (A * (1.0 - E2 / 4.0 - 3.0 * E2 * E2 / 64.0 - 5.0 * Math.pow(E2, 3) / 256.0));
        double e1 = (1.0 - Math.sqrt(1.0 - E2)) / (1.0 + Math.sqrt(1.0 - E2));
        double fp = mu
                + (3.0 * e1 / 2.0 - 27.0 * Math.pow(e1, 3) / 32.0) * Math.sin(2.0 * mu)
                + (21.0 * e1 * e1 / 16.0 - 55.0 * Math.pow(e1, 4) / 32.0) * Math.sin(4.0 * mu)
                + (151.0 * Math.pow(e1, 3) / 96.0) * Math.sin(6.0 * mu)
                + (1097.0 * Math.pow(e1, 4) / 512.0) * Math.sin(8.0 * mu);

        double sinFp = Math.sin(fp);
        double cosFp = Math.cos(fp);
        double tanFp = Math.tan(fp);
        double c1 = EP2 * cosFp * cosFp;
        double t1 = tanFp * tanFp;
        double n1 = A / Math.sqrt(1.0 - E2 * sinFp * sinFp);
        double r1 = A * (1.0 - E2) / Math.pow(1.0 - E2 * sinFp * sinFp, 1.5);
        double d = x / (n1 * K0);

        double lat = fp - (n1 * tanFp / r1) * (d * d / 2.0
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * Math.pow(d, 4) / 24.0
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1)
                * Math.pow(d, 6) / 720.0);
        double lon = LON0 + (d - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6.0
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1)
                * Math.pow(d, 5) / 120.0) / cosFp;
        Coordinate lonLat = new Coordinate(Math.toDegrees(lon), Math.toDegrees(lat));
        return refineInverse(metric, lonLat);
    }

    private Coordinate refineInverse(Coordinate targetMetric, Coordinate initialLonLat) {
        double lon = initialLonLat.x;
        double lat = initialLonLat.y;
        double eps = 1e-6;
        for (int i = 0; i < 8; i++) {
            Coordinate projected = toMetric(new Coordinate(lon, lat));
            double dx = targetMetric.x - projected.x;
            double dy = targetMetric.y - projected.y;
            // A millimetre is normally harmless, but it can move a connection point across
            // the bisector between two equally-near OKS faces after GeoJSON export.
            if (Math.hypot(dx, dy) < 1e-7) {
                break;
            }

            Coordinate projectedLon = toMetric(new Coordinate(lon + eps, lat));
            Coordinate projectedLat = toMetric(new Coordinate(lon, lat + eps));
            double ax = (projectedLon.x - projected.x) / eps;
            double ay = (projectedLon.y - projected.y) / eps;
            double bx = (projectedLat.x - projected.x) / eps;
            double by = (projectedLat.y - projected.y) / eps;
            double det = ax * by - ay * bx;
            if (Math.abs(det) < 1e-9) {
                break;
            }
            lon += (dx * by - dy * bx) / det;
            lat += (ax * dy - ay * dx) / det;
        }
        return new Coordinate(lon, lat);
    }

    private double meridionalArc(double lat) {
        return A * ((1.0 - E2 / 4.0 - 3.0 * E2 * E2 / 64.0 - 5.0 * Math.pow(E2, 3) / 256.0) * lat
                - (3.0 * E2 / 8.0 + 3.0 * E2 * E2 / 32.0 + 45.0 * Math.pow(E2, 3) / 1024.0) * Math.sin(2.0 * lat)
                + (15.0 * E2 * E2 / 256.0 + 45.0 * Math.pow(E2, 3) / 1024.0) * Math.sin(4.0 * lat)
                - (35.0 * Math.pow(E2, 3) / 3072.0) * Math.sin(6.0 * lat));
    }
}
