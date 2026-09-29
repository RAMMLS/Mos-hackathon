package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.geom.Coordinate;
import ru.moshackathon.heatnetwork.model.ChamberOutput;
import ru.moshackathon.heatnetwork.model.NewSegment;
import ru.moshackathon.heatnetwork.model.Solution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Stable geometry-first identity used only for search statistics.
 * It never bypasses candidate construction or certification.
 */
final class SolutionConstructionFingerprint {
    private static final double COORDINATE_GRID_METERS = 0.001;

    private SolutionConstructionFingerprint() {
    }

    static String of(Solution solution) {
        List<String> parts = new ArrayList<>();
        for (NewSegment segment : solution.getSegments()) {
            String forward = coordinates(segment.getMetricGeometry().getCoordinates(), false);
            String reverse = coordinates(segment.getMetricGeometry().getCoordinates(), true);
            String geometry = forward.compareTo(reverse) <= 0 ? forward : reverse;
            parts.add("S|" + geometry
                    + "|f=" + quantize(segment.getFlowTph())
                    + "|d=" + segment.getDiameter()
                    + "|m=" + segment.getLayingMethod());
        }
        for (ChamberOutput chamber : solution.getChambers()) {
            Coordinate coordinate = chamber.getMetricPoint().getCoordinate();
            parts.add("C|" + point(coordinate) + "|d=" + chamber.getDiameter());
        }
        List<String> unconnected = new ArrayList<>(solution.getUnconnectedConnectionPointIds());
        Collections.sort(unconnected);
        for (String id : unconnected) {
            parts.add("U|" + id);
        }
        Collections.sort(parts);
        return sha256(String.join("\n", parts));
    }

    private static String coordinates(Coordinate[] coordinates, boolean reverse) {
        StringBuilder value = new StringBuilder();
        for (int offset = 0; offset < coordinates.length; offset++) {
            int index = reverse ? coordinates.length - 1 - offset : offset;
            if (offset > 0) {
                value.append(';');
            }
            value.append(point(coordinates[index]));
        }
        return value.toString();
    }

    private static String point(Coordinate coordinate) {
        return quantize(coordinate.x) + "," + quantize(coordinate.y);
    }

    private static String quantize(double value) {
        double rounded = Math.rint(value / COORDINATE_GRID_METERS) * COORDINATE_GRID_METERS;
        return String.format(Locale.ROOT, "%.3f", rounded);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
