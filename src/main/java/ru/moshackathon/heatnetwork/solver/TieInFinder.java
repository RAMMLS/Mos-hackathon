package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.geo.MetricProjector;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.TieInCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class TieInFinder {
    private final MetricProjector projector;

    public TieInFinder(MetricProjector projector) {
        this.projector = projector;
    }

    public List<TieInCandidate> find(InputFeature connectionPoint, ProblemData data) {
        Coordinate from = connectionPoint.getMetricGeometry().getCoordinate();
        List<TieInCandidate> candidates = new ArrayList<>();
        for (InputFeature chamber : data.getHeatChambers()) {
            Coordinate point = chamber.getMetricGeometry().getCoordinate();
            double distance = point.distance(from);
            candidates.add(new TieInCandidate(
                    TieInCandidate.HostType.EXISTING_CHAMBER,
                    chamber.getId(),
                    point,
                    chamber.getLonLatGeometry().getCoordinate(),
                    inferChamberDiameter(chamber, data),
                    distance));
        }
        for (InputFeature network : data.getHeatNetworks()) {
            LineString line = (LineString) network.getMetricGeometry();
            Projection projection = nearestPoint(line, from);
            TieInCandidate chamberWithinTen = nearestChamberWithin(projection.point, data, 10.0, from);
            if (chamberWithinTen != null) {
                candidates.add(chamberWithinTen);
                continue;
            }
            Coordinate lonLat = projector.toLonLat(projection.point);
            candidates.add(new TieInCandidate(
                    TieInCandidate.HostType.EXISTING_PIPE,
                    network.getId(),
                    projection.point,
                    lonLat,
                    network.getInt("diameter", 0),
                    projection.point.distance(from)));
        }
        candidates.sort(Comparator.comparingDouble(TieInCandidate::getDistanceFromConnection));
        return deduplicate(candidates);
    }

    private TieInCandidate nearestChamberWithin(Coordinate point, ProblemData data, double maxDistance, Coordinate from) {
        TieInCandidate best = null;
        for (InputFeature chamber : data.getHeatChambers()) {
            Coordinate chamberPoint = chamber.getMetricGeometry().getCoordinate();
            double distanceToProjection = chamberPoint.distance(point);
            if (distanceToProjection <= maxDistance) {
                double distanceFromConnection = chamberPoint.distance(from);
                if (best == null || distanceFromConnection < best.getDistanceFromConnection()) {
                    best = new TieInCandidate(
                            TieInCandidate.HostType.EXISTING_CHAMBER,
                            chamber.getId(),
                            chamberPoint,
                            chamber.getLonLatGeometry().getCoordinate(),
                            inferChamberDiameter(chamber, data),
                            distanceFromConnection);
                }
            }
        }
        return best;
    }

    private List<TieInCandidate> deduplicate(List<TieInCandidate> candidates) {
        List<TieInCandidate> result = new ArrayList<>();
        for (TieInCandidate candidate : candidates) {
            boolean exists = result.stream().anyMatch(existing ->
                    existing.getHostType() == candidate.getHostType()
                            && existing.getHostId().equals(candidate.getHostId()));
            if (!exists) {
                result.add(candidate);
            }
        }
        return result;
    }

    private int inferChamberDiameter(InputFeature chamber, ProblemData data) {
        int explicit = chamber.getInt("diameter", 0);
        if (explicit > 0) {
            return explicit;
        }
        Point point = (Point) chamber.getMetricGeometry();
        int max = 0;
        for (InputFeature network : data.getHeatNetworks()) {
            if (network.getMetricGeometry().distance(point) <= 1.0) {
                max = Math.max(max, network.getInt("diameter", 0));
            }
        }
        return max;
    }

    private Projection nearestPoint(LineString line, Coordinate target) {
        Coordinate[] coordinates = line.getCoordinates();
        Coordinate best = coordinates[0];
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < coordinates.length - 1; i++) {
            Coordinate candidate = projectOnSegment(target, coordinates[i], coordinates[i + 1]);
            double distance = candidate.distance(target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return new Projection(best);
    }

    private Coordinate projectOnSegment(Coordinate p, Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double length2 = dx * dx + dy * dy;
        if (length2 == 0) {
            return new Coordinate(a);
        }
        double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / length2;
        t = Math.max(0, Math.min(1, t));
        return new Coordinate(a.x + t * dx, a.y + t * dy);
    }

    private static class Projection {
        private final Coordinate point;

        Projection(Coordinate point) {
            this.point = point;
        }
    }
}
