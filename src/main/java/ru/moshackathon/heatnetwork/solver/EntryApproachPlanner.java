package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class EntryApproachPlanner {
    private static final double TOLERANCE_METERS = 0.001;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    EntryRayAnalysis analyzeNearestRay(Coordinate endpoint, Geometry owner,
                                       int requiredDiameter) {
        Coordinate boundary = new Coordinate(DistanceOp.nearestPoints(
                owner.getBoundary(), geometryFactory.createPoint(endpoint))[0]);
        double boundaryDistance = endpoint.distance(boundary);
        double requiredClearance = requiredCenterlineClearance(requiredDiameter);
        if (boundaryDistance < TOLERANCE_METERS) {
            return EntryRayAnalysis.degenerate(boundary, requiredClearance);
        }

        double ux = (boundary.x - endpoint.x) / boundaryDistance;
        double uy = (boundary.y - endpoint.y) / boundaryDistance;
        double horizon = rayHorizon(endpoint, owner.getEnvelopeInternal());
        Coordinate rayEnd = new Coordinate(endpoint.x + ux * horizon, endpoint.y + uy * horizon);
        LineString ray = geometryFactory.createLineString(new Coordinate[]{endpoint, rayEnd});
        List<Interval> material = materialIntervals(ray.intersection(owner), endpoint, ux, uy);

        Interval first = material.stream()
                .filter(interval -> interval.start <= TOLERANCE_METERS
                        && interval.end >= -TOLERANCE_METERS)
                .findFirst()
                .orElse(null);
        if (first == null) {
            return EntryRayAnalysis.invalid(boundary, boundaryDistance, requiredClearance,
                    "TARGET_NOT_IN_FIRST_MATERIAL_INTERVAL");
        }
        double firstExit = first.end;
        Double firstReentry = material.stream()
                .filter(interval -> interval.start > firstExit + TOLERANCE_METERS)
                .map(interval -> interval.start)
                .findFirst()
                .orElse(null);
        Double gap = firstReentry == null ? null : firstReentry - firstExit;
        Double clearanceUpperBound = gap == null ? null : gap / 2.0;
        boolean nearestMatchesExit = Math.abs(firstExit - boundaryDistance) <= 0.01;
        boolean ordinaryPortalRuledOut = nearestMatchesExit
                && clearanceUpperBound != null
                && clearanceUpperBound + TOLERANCE_METERS < requiredClearance;
        String reason = ordinaryPortalRuledOut
                ? "FIXED_NEAREST_RAY_CLEARANCE_IMPOSSIBLE"
                : "FIXED_NEAREST_RAY_NOT_RULED_OUT";
        return new EntryRayAnalysis(boundary, boundaryDistance, requiredClearance,
                firstExit, firstReentry, gap, clearanceUpperBound,
                nearestMatchesExit, ordinaryPortalRuledOut, reason);
    }

    static double requiredCenterlineClearance(int diameter) {
        return ClearanceRules.requiredCenterlineClearance("oks", diameter, 0);
    }

    private double rayHorizon(Coordinate endpoint, Envelope envelope) {
        Coordinate[] corners = {
                new Coordinate(envelope.getMinX(), envelope.getMinY()),
                new Coordinate(envelope.getMinX(), envelope.getMaxY()),
                new Coordinate(envelope.getMaxX(), envelope.getMinY()),
                new Coordinate(envelope.getMaxX(), envelope.getMaxY())
        };
        double farthest = 0.0;
        for (Coordinate corner : corners) {
            farthest = Math.max(farthest, endpoint.distance(corner));
        }
        return farthest + 20.0;
    }

    private List<Interval> materialIntervals(Geometry geometry, Coordinate origin,
                                             double ux, double uy) {
        List<Interval> raw = new ArrayList<>();
        collectIntervals(geometry, origin, ux, uy, raw);
        raw.sort(Comparator.comparingDouble(interval -> interval.start));
        List<Interval> merged = new ArrayList<>();
        for (Interval interval : raw) {
            if (interval.end - interval.start <= TOLERANCE_METERS) {
                continue;
            }
            if (merged.isEmpty()
                    || interval.start > merged.get(merged.size() - 1).end + TOLERANCE_METERS) {
                merged.add(interval);
            } else {
                Interval previous = merged.remove(merged.size() - 1);
                merged.add(new Interval(previous.start, Math.max(previous.end, interval.end)));
            }
        }
        return merged;
    }

    private void collectIntervals(Geometry geometry, Coordinate origin,
                                  double ux, double uy, List<Interval> result) {
        if (geometry instanceof LineString) {
            Coordinate[] coordinates = geometry.getCoordinates();
            if (coordinates.length < 2) {
                return;
            }
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (Coordinate coordinate : coordinates) {
                double projection = (coordinate.x - origin.x) * ux + (coordinate.y - origin.y) * uy;
                min = Math.min(min, projection);
                max = Math.max(max, projection);
            }
            result.add(new Interval(Math.max(0.0, min), Math.max(0.0, max)));
            return;
        }
        if (geometry.getDimension() != 1 || geometry.getNumGeometries() == 1
                && geometry.getGeometryN(0) == geometry) {
            return;
        }
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            collectIntervals(geometry.getGeometryN(i), origin, ux, uy, result);
        }
    }

    private static final class Interval {
        private final double start;
        private final double end;

        private Interval(double start, double end) {
            this.start = start;
            this.end = end;
        }
    }

    static final class EntryRayAnalysis {
        private final Coordinate boundary;
        private final double boundaryDistance;
        private final double requiredClearance;
        private final Double firstExit;
        private final Double firstReentry;
        private final Double firstGap;
        private final Double clearanceUpperBound;
        private final boolean nearestMatchesExit;
        private final boolean ordinaryPortalRuledOut;
        private final String reason;

        private EntryRayAnalysis(Coordinate boundary, double boundaryDistance,
                                 double requiredClearance, Double firstExit,
                                 Double firstReentry, Double firstGap,
                                 Double clearanceUpperBound, boolean nearestMatchesExit,
                                 boolean ordinaryPortalRuledOut, String reason) {
            this.boundary = new Coordinate(boundary);
            this.boundaryDistance = boundaryDistance;
            this.requiredClearance = requiredClearance;
            this.firstExit = firstExit;
            this.firstReentry = firstReentry;
            this.firstGap = firstGap;
            this.clearanceUpperBound = clearanceUpperBound;
            this.nearestMatchesExit = nearestMatchesExit;
            this.ordinaryPortalRuledOut = ordinaryPortalRuledOut;
            this.reason = reason;
        }

        private static EntryRayAnalysis degenerate(Coordinate boundary, double clearance) {
            return invalid(boundary, 0.0, clearance, "TARGET_ON_BOUNDARY");
        }

        private static EntryRayAnalysis invalid(Coordinate boundary, double distance,
                                                double clearance, String reason) {
            return new EntryRayAnalysis(boundary, distance, clearance,
                    null, null, null, null, false, false, reason);
        }

        boolean isOrdinaryPortalRuledOut() {
            return ordinaryPortalRuledOut;
        }

        Coordinate getBoundary() {
            return new Coordinate(boundary);
        }

        double getRequiredClearance() {
            return requiredClearance;
        }

        String describe(String ownerId, int diameter) {
            return "owner=" + ownerId
                    + ", diameter=" + diameter
                    + ", boundary_distance_m=" + round(boundaryDistance)
                    + ", first_exit_m=" + round(firstExit)
                    + ", first_reentry_m=" + round(firstReentry)
                    + ", first_gap_m=" + round(firstGap)
                    + ", clearance_upper_bound_m=" + round(clearanceUpperBound)
                    + ", required_centerline_clearance_m=" + round(requiredClearance)
                    + ", nearest_matches_exit=" + nearestMatchesExit
                    + ", reason=" + reason
                    + ", evidence_scope=fixed_nearest_boundary_straight_ray";
        }

        private String round(Double value) {
            return value == null ? "none" : String.valueOf(Math.round(value * 1000.0) / 1000.0);
        }
    }
}
