package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Route;

import java.util.*;

@Component
public class RoutePlanner {
    private static final double FORBIDDEN_CLEARANCE_METERS = 0.75;
    private static final double MIN_OKS_CLEARANCE_METERS = 5.0;
    private static final double ENDPOINT_APPROACH_TOLERANCE_METERS = 0.20;
    private static final double PORTAL_SAFETY_MARGIN_METERS = 5.0;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public enum RouteMode {
        GRID,
        CORRIDOR
    }

    public Optional<Route> plan(Coordinate start, Coordinate end, ProblemData data) {
        return plan(start, end, prepare(data), RouteMode.CORRIDOR);
    }

    public RoutingContext prepare(ProblemData data) {
        return prepare(data, false);
    }

    public RoutingContext prepare(ProblemData data, boolean strictEndpointIntersections) {
        return new RoutingContext(data.getRestrictions(), geometryFactory);
    }

    public Optional<Route> plan(Coordinate start, Coordinate end, RoutingContext context) {
        return plan(start, end, context, RouteMode.CORRIDOR);
    }

    public Optional<Route> plan(Coordinate start, Coordinate end, RoutingContext context, RouteMode mode) {
        RouteKey key = new RouteKey(start, end, mode);
        Optional<Route> cached = context.routeCache.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<Route> route = planUncached(start, end, context, mode);
        context.routeCache.put(key, route);
        return route;
    }

    public boolean avoidsForbiddenRestrictions(LineString line, RoutingContext context) {
        return firstForbiddenIntersection(line, context) == null;
    }

    private Optional<Route> planUncached(Coordinate start, Coordinate end, RoutingContext context, RouteMode mode) {
        EndpointApproach startApproach = endpointApproach(start, context);
        EndpointApproach endApproach = endpointApproach(end, context);
        if (!startApproach.valid || !endApproach.valid) {
            return Optional.empty();
        }

        Optional<Route> core = planCore(startApproach.portal, endApproach.portal, context, mode);
        if (!core.isPresent()) {
            return Optional.empty();
        }

        LineString combined = combineEndpointApproaches(startApproach, core.get().getMetricGeometry(), endApproach);
        if (!isAllowedRoute(combined, context, start, end) || hasHairpin(combined)) {
            return Optional.empty();
        }
        List<String> notes = new ArrayList<>(core.get().getNotes());
        if (startApproach.required || endApproach.required) {
            notes.add("strict OKS endpoint approach through nearest boundary");
        }
        SpecialInfo special = specialInfo(combined, context);
        return Optional.of(new Route(combined, combined.getLength(), special.method, special.coefficient, notes));
    }

    private Optional<Route> planCore(Coordinate start, Coordinate end, RoutingContext context, RouteMode mode) {
        List<String> notes = new ArrayList<>();
        LineString direct = line(start, end);
        if (isAllowedRoute(direct, context, start, end)) {
            SpecialInfo special = specialInfo(direct, context);
            return Optional.of(new Route(direct, direct.getLength(), special.method, special.coefficient, notes));
        }

        if (mode == RouteMode.CORRIDOR) {
            Optional<Route> dogleg = dogleg(start, end, context);
            dogleg.ifPresent(route -> notes.add("direct route crossed forbidden restriction, used dogleg fallback"));
            if (dogleg.isPresent()) {
                return dogleg;
            }
        }
        return astar(start, end, context);
    }

    private EndpointApproach endpointApproach(Coordinate endpoint, RoutingContext context) {
        Point point = geometryFactory.createPoint(endpoint);
        Envelope search = new Envelope(endpoint);
        search.expandBy(ENDPOINT_APPROACH_TOLERANCE_METERS);
        List<RestrictionInfo> containing = new ArrayList<>();
        for (RestrictionInfo restriction : context.queryRestrictions(search)) {
            if ("oks".equals(restriction.type) && restriction.prepared.covers(point)) {
                containing.add(restriction);
            }
        }
        if (containing.isEmpty()) {
            return EndpointApproach.notRequired(endpoint);
        }
        if (containing.size() > 1) {
            return EndpointApproach.invalid(endpoint);
        }

        RestrictionInfo restriction = containing.get(0);
        Coordinate[] nearest = DistanceOp.nearestPoints(restriction.geometry.getBoundary(), point);
        if (nearest.length == 0) {
            return EndpointApproach.invalid(endpoint);
        }
        Coordinate boundary = nearest[0];
        double dx = boundary.x - endpoint.x;
        double dy = boundary.y - endpoint.y;
        double distance = Math.hypot(dx, dy);
        if (distance < 0.01) {
            return EndpointApproach.invalid(endpoint);
        }
        dx /= distance;
        dy /= distance;

        double offset = MIN_OKS_CLEARANCE_METERS + PORTAL_SAFETY_MARGIN_METERS;
        Coordinate portal = new Coordinate(boundary.x + dx * offset, boundary.y + dy * offset);
        Point portalPoint = geometryFactory.createPoint(portal);
        int attempts = 0;
        while ((restriction.prepared.covers(portalPoint)
                || restriction.geometry.distance(portalPoint) + 1e-6 < MIN_OKS_CLEARANCE_METERS)
                && attempts++ < 20) {
            offset += MIN_OKS_CLEARANCE_METERS;
            portal = new Coordinate(boundary.x + dx * offset, boundary.y + dy * offset);
            portalPoint = geometryFactory.createPoint(portal);
        }
        if (restriction.prepared.covers(portalPoint)
                || restriction.geometry.distance(portalPoint) + 1e-6 < MIN_OKS_CLEARANCE_METERS) {
            return EndpointApproach.invalid(endpoint);
        }
        return EndpointApproach.required(endpoint, portal);
    }

    private LineString combineEndpointApproaches(EndpointApproach start, LineString core,
                                                  EndpointApproach end) {
        List<Coordinate> coordinates = new ArrayList<>();
        appendDistinct(coordinates, start.endpoint);
        appendDistinct(coordinates, start.portal);
        for (Coordinate coordinate : core.getCoordinates()) {
            appendDistinct(coordinates, coordinate);
        }
        appendDistinct(coordinates, end.portal);
        appendDistinct(coordinates, end.endpoint);
        if (coordinates.size() == 1) {
            coordinates.add(new Coordinate(coordinates.get(0)));
        }
        return line(coordinates.toArray(new Coordinate[0]));
    }

    private void appendDistinct(List<Coordinate> coordinates, Coordinate coordinate) {
        if (coordinates.isEmpty() || !coordinates.get(coordinates.size() - 1).equals2D(coordinate)) {
            coordinates.add(new Coordinate(coordinate));
        }
    }

    private Optional<Route> dogleg(Coordinate start, Coordinate end, RoutingContext context) {
        Geometry blocker = firstForbiddenIntersection(line(start, end), context);
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
        List<List<Coordinate>> waypointSets = new ArrayList<>();
        for (Coordinate pivot : pivots) {
            waypointSets.add(Collections.singletonList(pivot));
        }
        for (Coordinate first : pivots) {
            for (Coordinate second : pivots) {
                if (!first.equals2D(second)) {
                    waypointSets.add(Arrays.asList(first, second));
                }
            }
        }

        Route best = null;
        for (List<Coordinate> waypoints : waypointSets) {
            List<Coordinate> coordinates = new ArrayList<>();
            coordinates.add(start);
            coordinates.addAll(waypoints);
            coordinates.add(end);
            LineString routeLine = line(coordinates.toArray(new Coordinate[0]));
            routeLine = smoothHairpins(routeLine, context, start, end);
            if (!isAllowedRoute(routeLine, context, start, end)) {
                continue;
            }
            if (hasHairpin(routeLine)) {
                continue;
            }
            SpecialInfo special = specialInfo(routeLine, context);
            Route route = new Route(routeLine, routeLine.getLength(), special.method, special.coefficient,
                    Collections.singletonList("corridor route around forbidden restriction"));
            if (best == null || route.getLengthMeters() < best.getLengthMeters()) {
                best = route;
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean crossesForbidden(LineString line, RoutingContext context, Coordinate start, Coordinate end) {
        return firstForbiddenIntersection(line, context, start, end) != null;
    }

    private boolean isAllowedRoute(LineString line, RoutingContext context, Coordinate start, Coordinate end) {
        return !crossesForbidden(line, context, start, end)
                && !hasBadSpecialCrossingAngle(line, context);
    }

    private Geometry firstForbiddenIntersection(LineString line, RoutingContext context) {
        return firstForbiddenIntersection(line, context,
                line.getCoordinateN(0), line.getCoordinateN(line.getNumPoints() - 1));
    }

    private Geometry firstForbiddenIntersection(LineString line, RoutingContext context,
                                                Coordinate start, Coordinate end) {
        Point startPoint = geometryFactory.createPoint(start);
        Point endPoint = geometryFactory.createPoint(end);
        Envelope searchEnvelope = new Envelope(line.getEnvelopeInternal());
        searchEnvelope.expandBy(FORBIDDEN_CLEARANCE_METERS);
        for (RestrictionInfo restriction : context.queryRestrictions(searchEnvelope)) {
            boolean endpointRestriction = "oks".equals(restriction.type)
                    && (restriction.prepared.covers(startPoint) || restriction.prepared.covers(endPoint));
            boolean completeRoute = line.getCoordinateN(0).equals2D(start)
                    && line.getCoordinateN(line.getNumPoints() - 1).equals2D(end);
            boolean allowedEndpointTouch = endpointRestriction
                    && (!completeRoute
                    || isCompliantEndpointApproach(line, restriction.geometry, startPoint, endPoint));
            if (restriction.forbidden
                    && !allowedEndpointTouch
                    && (restriction.prepared.intersects(line)
                    || line.distance(restriction.geometry) < FORBIDDEN_CLEARANCE_METERS)) {
                return restriction.geometry;
            }
        }
        return null;
    }

    private boolean isCompliantEndpointApproach(LineString line, Geometry restriction,
                                                Point startPoint, Point endPoint) {
        boolean startInside = restriction.covers(startPoint);
        boolean endInside = restriction.covers(endPoint);
        if (!startInside && !endInside) {
            return false;
        }
        Coordinate[] coordinates = line.getCoordinates();
        if (coordinates.length < 2) {
            return false;
        }
        if (startInside && !isCompliantApproachSegment(
                coordinates[0], coordinates[1], restriction)) {
            return false;
        }
        if (endInside && !isCompliantApproachSegment(
                coordinates[coordinates.length - 1], coordinates[coordinates.length - 2], restriction)) {
            return false;
        }

        int first = startInside ? 1 : 0;
        int last = endInside ? coordinates.length - 2 : coordinates.length - 1;
        for (int i = first; i < last; i++) {
            LineString segment = line(coordinates[i], coordinates[i + 1]);
            if (segment.intersects(restriction)) {
                return false;
            }
        }
        return true;
    }

    private boolean isCompliantApproachSegment(Coordinate endpoint, Coordinate outside,
                                                Geometry restriction) {
        Point endpointPoint = geometryFactory.createPoint(endpoint);
        Point outsidePoint = geometryFactory.createPoint(outside);
        if (!restriction.covers(endpointPoint) || restriction.covers(outsidePoint)
                || restriction.distance(outsidePoint) + ENDPOINT_APPROACH_TOLERANCE_METERS
                < MIN_OKS_CLEARANCE_METERS) {
            return false;
        }

        Coordinate[] nearest = DistanceOp.nearestPoints(restriction.getBoundary(), endpointPoint);
        if (nearest.length == 0) {
            return false;
        }
        Coordinate boundary = nearest[0];
        LineString approach = line(endpoint, outside);
        if (approach.distance(geometryFactory.createPoint(boundary))
                > ENDPOINT_APPROACH_TOLERANCE_METERS) {
            return false;
        }
        double expectedInsideLength = endpoint.distance(boundary);
        Geometry intersection = approach.intersection(restriction);
        return Math.abs(intersection.getLength() - expectedInsideLength)
                <= ENDPOINT_APPROACH_TOLERANCE_METERS;
    }

    private static boolean isForbidden(String type) {
        return "oks".equals(type) || "water".equals(type) || "railway".equals(type) || "park".equals(type)
                || "social_area".equals(type) || "prohibited_site".equals(type);
    }

    private boolean hasBadSpecialCrossingAngle(LineString line, RoutingContext context) {
        Coordinate[] route = line.getCoordinates();
        for (int i = 0; i < route.length - 1; i++) {
            Coordinate routeA = route[i];
            Coordinate routeB = route[i + 1];
            LineString routeSegment = line(routeA, routeB);
            for (SpecialSegment specialSegment : context.querySpecialSegments(routeSegment.getEnvelopeInternal())) {
                if (routeSegment.intersects(specialSegment.geometry)) {
                    double angle = crossingAngle(routeA, routeB,
                            specialSegment.segment.a, specialSegment.segment.b);
                    if (angle < 45.0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private double crossingAngle(Coordinate a, Coordinate b, Coordinate c, Coordinate d) {
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        double vx = d.x - c.x;
        double vy = d.y - c.y;
        double lenU = Math.hypot(ux, uy);
        double lenV = Math.hypot(vx, vy);
        if (lenU < 0.05 || lenV < 0.05) {
            return 90.0;
        }
        double cos = Math.abs((ux * vx + uy * vy) / (lenU * lenV));
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.toDegrees(Math.acos(cos));
    }

    private SpecialInfo specialInfo(LineString line, RoutingContext context) {
        double coefficient = 1.0;
        for (RestrictionInfo restriction : context.queryRestrictions(line.getEnvelopeInternal())) {
            if (restriction.prepared.intersects(line)) {
                coefficient = Math.max(coefficient, coefficient(restriction.type));
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

    private Optional<Route> astar(Coordinate start, Coordinate end, RoutingContext context) {
        double[] steps = {8.0, 5.0};
        for (double step : steps) {
            Optional<Route> result = astar(start, end, context, step);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private Optional<Route> astar(Coordinate start, Coordinate end, RoutingContext context, double step) {
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
                routeLine = smoothHairpins(routeLine, context, start, end);
                if (!isAllowedRoute(routeLine, context, start, end) || hasHairpin(routeLine)) {
                    return Optional.empty();
                }
                SpecialInfo special = specialInfo(routeLine, context);
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
                if (!isAllowedRoute(edge, context, start, end)) {
                    continue;
                }
                double move = a.distance(b);
                move *= specialInfo(edge, context).coefficient;
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

    private LineString smoothHairpins(LineString routeLine, RoutingContext context,
                                      Coordinate start, Coordinate end) {
        List<Coordinate> coordinates = new ArrayList<>(Arrays.asList(routeLine.getCoordinates()));
        boolean changed = true;
        while (changed && coordinates.size() > 2) {
            changed = false;
            for (int i = 1; i < coordinates.size() - 1; i++) {
                double angle = turnAngle(coordinates.get(i - 1), coordinates.get(i), coordinates.get(i + 1));
                if (angle < 150.0) {
                    continue;
                }
                List<Coordinate> candidate = new ArrayList<>(coordinates);
                candidate.remove(i);
                LineString candidateLine = line(candidate.toArray(new Coordinate[0]));
                if (isAllowedRoute(candidateLine, context, start, end)) {
                    coordinates = candidate;
                    changed = true;
                    break;
                }
            }
        }
        return line(coordinates.toArray(new Coordinate[0]));
    }

    private boolean hasHairpin(LineString line) {
        Coordinate[] coordinates = line.getCoordinates();
        for (int i = 1; i < coordinates.length - 1; i++) {
            if (turnAngle(coordinates[i - 1], coordinates[i], coordinates[i + 1]) >= 150.0) {
                return true;
            }
        }
        return false;
    }

    private double turnAngle(Coordinate a, Coordinate b, Coordinate c) {
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        double vx = c.x - b.x;
        double vy = c.y - b.y;
        double lenU = Math.hypot(ux, uy);
        double lenV = Math.hypot(vx, vy);
        if (lenU < 0.05 || lenV < 0.05) {
            return 0.0;
        }
        double cos = (ux * vx + uy * vy) / (lenU * lenV);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return Math.toDegrees(Math.acos(cos));
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

    public static final class RoutingContext {
        private final STRtree restrictionIndex = new STRtree();
        private final STRtree specialSegmentIndex = new STRtree();
        private final Map<RouteKey, Optional<Route>> routeCache = new HashMap<>();

        private RoutingContext(List<InputFeature> restrictions, GeometryFactory geometryFactory) {
            for (InputFeature restriction : restrictions) {
                Geometry geometry = restriction.getMetricGeometry();
                String type = String.valueOf(restriction.getProperties().get("restriction_type"));
                RestrictionInfo info = new RestrictionInfo(
                        geometry, type, isForbidden(type), PreparedGeometryFactory.prepare(geometry));
                restrictionIndex.insert(geometry.getEnvelopeInternal(), info);
                if ("road".equals(type) || "tram_tracks".equals(type)) {
                    List<Segment> restrictionSegments = new ArrayList<>();
                    collectSegmentsStatic(geometry, restrictionSegments);
                    for (Segment segment : restrictionSegments) {
                        LineString segmentLine = geometryFactory.createLineString(
                                new Coordinate[]{segment.a, segment.b});
                        specialSegmentIndex.insert(segmentLine.getEnvelopeInternal(),
                                new SpecialSegment(segment, segmentLine));
                    }
                }
            }
            restrictionIndex.build();
            specialSegmentIndex.build();
        }

        @SuppressWarnings("unchecked")
        private List<RestrictionInfo> queryRestrictions(Envelope envelope) {
            return restrictionIndex.query(envelope);
        }

        @SuppressWarnings("unchecked")
        private List<SpecialSegment> querySpecialSegments(Envelope envelope) {
            return specialSegmentIndex.query(envelope);
        }
    }

    private static void collectSegmentsStatic(Geometry geometry, List<Segment> result) {
        if (geometry instanceof LineString) {
            addSegmentsStatic(((LineString) geometry).getCoordinates(), result);
            return;
        }
        if (geometry instanceof Polygon) {
            Polygon polygon = (Polygon) geometry;
            addSegmentsStatic(polygon.getExteriorRing().getCoordinates(), result);
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                addSegmentsStatic(polygon.getInteriorRingN(i).getCoordinates(), result);
            }
            return;
        }
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            collectSegmentsStatic(geometry.getGeometryN(i), result);
        }
    }

    private static void addSegmentsStatic(Coordinate[] coordinates, List<Segment> result) {
        for (int i = 0; i < coordinates.length - 1; i++) {
            result.add(new Segment(coordinates[i], coordinates[i + 1]));
        }
    }

    private static final class RestrictionInfo {
        private final Geometry geometry;
        private final String type;
        private final boolean forbidden;
        private final PreparedGeometry prepared;

        private RestrictionInfo(Geometry geometry, String type, boolean forbidden, PreparedGeometry prepared) {
            this.geometry = geometry;
            this.type = type;
            this.forbidden = forbidden;
            this.prepared = prepared;
        }
    }

    private static final class EndpointApproach {
        private final Coordinate endpoint;
        private final Coordinate portal;
        private final boolean required;
        private final boolean valid;

        private EndpointApproach(Coordinate endpoint, Coordinate portal,
                                 boolean required, boolean valid) {
            this.endpoint = new Coordinate(endpoint);
            this.portal = new Coordinate(portal);
            this.required = required;
            this.valid = valid;
        }

        private static EndpointApproach notRequired(Coordinate endpoint) {
            return new EndpointApproach(endpoint, endpoint, false, true);
        }

        private static EndpointApproach required(Coordinate endpoint, Coordinate portal) {
            return new EndpointApproach(endpoint, portal, true, true);
        }

        private static EndpointApproach invalid(Coordinate endpoint) {
            return new EndpointApproach(endpoint, endpoint, false, false);
        }
    }

    private static final class SpecialSegment {
        private final Segment segment;
        private final LineString geometry;

        private SpecialSegment(Segment segment, LineString geometry) {
            this.segment = segment;
            this.geometry = geometry;
        }
    }

    private static final class RouteKey {
        private final long startX;
        private final long startY;
        private final long endX;
        private final long endY;
        private final RouteMode mode;

        private RouteKey(Coordinate start, Coordinate end, RouteMode mode) {
            this.startX = Double.doubleToLongBits(start.x);
            this.startY = Double.doubleToLongBits(start.y);
            this.endX = Double.doubleToLongBits(end.x);
            this.endY = Double.doubleToLongBits(end.y);
            this.mode = mode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RouteKey)) {
                return false;
            }
            RouteKey key = (RouteKey) other;
            return startX == key.startX && startY == key.startY
                    && endX == key.endX && endY == key.endY && mode == key.mode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(startX, startY, endX, endY, mode);
        }
    }

    private static class Segment {
        private final Coordinate a;
        private final Coordinate b;

        Segment(Coordinate a, Coordinate b) {
            this.a = a;
            this.b = b;
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
