package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.geom.*;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Route;

import java.util.*;

@Component
public class RoutePlanner {
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public Optional<Route> plan(Coordinate start, Coordinate end, ProblemData data) {
        List<String> notes = new ArrayList<>();
        LineString direct = line(start, end);
        SpecialInfo special = specialInfo(direct, data);
        if (!crossesForbidden(direct, data, start, end)) {
            return Optional.of(new Route(direct, direct.getLength(), special.method, special.coefficient, notes));
        }

        Optional<Route> dogleg = dogleg(start, end, data);
        dogleg.ifPresent(route -> notes.add("direct route crossed forbidden restriction, used dogleg fallback"));
        if (dogleg.isPresent()) {
            return dogleg;
        }
        return astar(start, end, data);
    }

    private Optional<Route> dogleg(Coordinate start, Coordinate end, ProblemData data) {
        Geometry blocker = firstForbiddenIntersection(line(start, end), data);
        if (blocker == null) {
            return Optional.empty();
        }
        Envelope env = blocker.getEnvelopeInternal();
        double margin = 12.0;
        List<Coordinate> pivots = Arrays.asList(
                new Coordinate(env.getMinX() - margin, env.getMinY() - margin),
                new Coordinate(env.getMinX() - margin, env.getMaxY() + margin),
                new Coordinate(env.getMaxX() + margin, env.getMinY() - margin),
                new Coordinate(env.getMaxX() + margin, env.getMaxY() + margin)
        );
        Route best = null;
        for (Coordinate pivot : pivots) {
            LineString routeLine = line(start, pivot, end);
            if (crossesForbidden(routeLine, data, start, end)) {
                continue;
            }
            SpecialInfo special = specialInfo(routeLine, data);
            Route route = new Route(routeLine, routeLine.getLength(), special.method, special.coefficient,
                    Collections.singletonList("dogleg route around forbidden restriction"));
            if (best == null || route.getLengthMeters() < best.getLengthMeters()) {
                best = route;
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean crossesForbidden(LineString line, ProblemData data, Coordinate start, Coordinate end) {
        return firstForbiddenIntersection(line, data, start, end) != null;
    }

    private Geometry firstForbiddenIntersection(LineString line, ProblemData data) {
        return firstForbiddenIntersection(line, data, line.getCoordinateN(0), line.getCoordinateN(line.getNumPoints() - 1));
    }

    private Geometry firstForbiddenIntersection(LineString line, ProblemData data, Coordinate start, Coordinate end) {
        Point startPoint = geometryFactory.createPoint(start);
        Point endPoint = geometryFactory.createPoint(end);
        for (InputFeature restriction : data.getRestrictions()) {
            Geometry geometry = restriction.getMetricGeometry();
            if (isForbidden(restriction)
                    && !geometry.covers(startPoint)
                    && !geometry.covers(endPoint)
                    && line.intersects(geometry)) {
                return restriction.getMetricGeometry();
            }
        }
        return null;
    }

    private boolean isForbidden(InputFeature restriction) {
        String type = String.valueOf(restriction.getProperties().get("restriction_type"));
        return "oks".equals(type) || "water".equals(type) || "park".equals(type)
                || "social_area".equals(type) || "prohibited_site".equals(type);
    }

    private SpecialInfo specialInfo(LineString line, ProblemData data) {
        double coefficient = 1.0;
        for (InputFeature restriction : data.getRestrictions()) {
            String type = String.valueOf(restriction.getProperties().get("restriction_type"));
            if (line.intersects(restriction.getMetricGeometry())) {
                coefficient = Math.max(coefficient, coefficient(type));
            }
        }
        return new SpecialInfo(coefficient > 1.0 ? "special" : "base", coefficient);
    }

    private double coefficient(String restrictionType) {
        if ("road".equals(restrictionType)) {
            return 1.60;
        }
        if ("tram_tracks".equals(restrictionType) || "railway".equals(restrictionType)) {
            return 1.75;
        }
        if ("gas_pipeline".equals(restrictionType)) {
            return 1.25;
        }
        if ("power_cable".equals(restrictionType)) {
            return 1.15;
        }
        if ("heat_network".equals(restrictionType)) {
            return 1.05;
        }
        return 1.0;
    }

    private LineString line(Coordinate... coordinates) {
        return geometryFactory.createLineString(coordinates);
    }

    private Optional<Route> astar(Coordinate start, Coordinate end, ProblemData data) {
        double[] steps = {8.0, 5.0};
        for (double step : steps) {
            Optional<Route> result = astar(start, end, data, step);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private Optional<Route> astar(Coordinate start, Coordinate end, ProblemData data, double step) {
        Envelope envelope = new Envelope(start, end);
        envelope.expandBy(140.0);
        int minX = (int) Math.floor(envelope.getMinX() / step);
        int maxX = (int) Math.ceil(envelope.getMaxX() / step);
        int minY = (int) Math.floor(envelope.getMinY() / step);
        int maxY = (int) Math.ceil(envelope.getMaxY() / step);
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) > 160_000L) {
            return Optional.empty();
        }

        GridNode startNode = new GridNode(
                clamp((int) Math.round(start.x / step), minX, maxX),
                clamp((int) Math.round(start.y / step), minY, maxY));
        GridNode endNode = new GridNode(
                clamp((int) Math.round(end.x / step), minX, maxX),
                clamp((int) Math.round(end.y / step), minY, maxY));
        PriorityQueue<GridState> open = new PriorityQueue<>(Comparator.comparingDouble(s -> s.priority));
        Map<GridNode, Double> cost = new HashMap<>();
        Map<GridNode, GridNode> previous = new HashMap<>();
        cost.put(startNode, 0.0);
        open.add(new GridState(startNode, heuristic(startNode, endNode, step)));

        int expanded = 0;
        while (!open.isEmpty() && expanded < 120_000) {
            GridNode current = open.poll().node;
            expanded++;
            if (current.equals(endNode)) {
                LineString routeLine = routeFromGrid(previous, current, startNode, start, end, step);
                SpecialInfo special = specialInfo(routeLine, data);
                return Optional.of(new Route(routeLine, routeLine.getLength(), special.method, special.coefficient,
                        Collections.singletonList("A* grid route step " + step + " m")));
            }
            for (GridNode next : current.neighbors()) {
                if (next.x < minX || next.x > maxX || next.y < minY || next.y > maxY) {
                    continue;
                }
                Coordinate a = toCoordinate(current, step);
                Coordinate b = toCoordinate(next, step);
                LineString edge = line(a, b);
                if (crossesForbidden(edge, data, start, end)) {
                    continue;
                }
                double move = a.distance(b);
                move *= specialInfo(edge, data).coefficient;
                double nextCost = cost.get(current) + move;
                if (nextCost < cost.getOrDefault(next, Double.MAX_VALUE)) {
                    cost.put(next, nextCost);
                    previous.put(next, current);
                    open.add(new GridState(next, nextCost + heuristic(next, endNode, step)));
                }
            }
        }
        return Optional.empty();
    }

    private LineString routeFromGrid(Map<GridNode, GridNode> previous, GridNode current, GridNode startNode,
                                     Coordinate exactStart, Coordinate exactEnd, double step) {
        List<Coordinate> coordinates = new ArrayList<>();
        coordinates.add(exactEnd);
        GridNode node = current;
        while (!node.equals(startNode)) {
            coordinates.add(toCoordinate(node, step));
            node = previous.get(node);
            if (node == null) {
                break;
            }
        }
        coordinates.add(exactStart);
        Collections.reverse(coordinates);
        return line(simplify(coordinates).toArray(new Coordinate[0]));
    }

    private List<Coordinate> simplify(List<Coordinate> coordinates) {
        if (coordinates.size() <= 2) {
            return coordinates;
        }
        List<Coordinate> result = new ArrayList<>();
        result.add(coordinates.get(0));
        for (int i = 1; i < coordinates.size() - 1; i++) {
            Coordinate a = result.get(result.size() - 1);
            Coordinate b = coordinates.get(i);
            Coordinate c = coordinates.get(i + 1);
            double cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
            if (Math.abs(cross) > 0.001) {
                result.add(b);
            }
        }
        result.add(coordinates.get(coordinates.size() - 1));
        return result;
    }

    private double heuristic(GridNode a, GridNode b, double step) {
        return Math.hypot(a.x - b.x, a.y - b.y) * step;
    }

    private Coordinate toCoordinate(GridNode node, double step) {
        return new Coordinate(node.x * step, node.y * step);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class SpecialInfo {
        private final String method;
        private final double coefficient;

        SpecialInfo(String method, double coefficient) {
            this.method = method;
            this.coefficient = coefficient;
        }
    }

    private static class GridState {
        private final GridNode node;
        private final double priority;

        GridState(GridNode node, double priority) {
            this.node = node;
            this.priority = priority;
        }
    }

    private static class GridNode {
        private final int x;
        private final int y;

        GridNode(int x, int y) {
            this.x = x;
            this.y = y;
        }

        List<GridNode> neighbors() {
            List<GridNode> result = new ArrayList<>(8);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        result.add(new GridNode(x + dx, y + dy));
                    }
                }
            }
            return result;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof GridNode)) {
                return false;
            }
            GridNode gridNode = (GridNode) o;
            return x == gridNode.x && y == gridNode.y;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y);
        }
    }
}
