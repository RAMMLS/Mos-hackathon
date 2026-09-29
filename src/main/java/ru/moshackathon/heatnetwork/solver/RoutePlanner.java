package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Route;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

@Component
public class RoutePlanner {
    private static final String CACHE_VERSION = "route-cache-v8-continuous-entry-intervals";
    private static final double MAX_TURN_ANGLE_DEGREES = 90.0;
    private static final double MAX_GENERATED_TURN_ANGLE_DEGREES = 89.5;
    private static final double TURN_ANGLE_TOLERANCE_DEGREES = 0.05;
    private static final double ENDPOINT_APPROACH_TOLERANCE_METERS = 0.20;
    private static final double PORTAL_SAFETY_MARGIN_METERS = 5.0;
    private static final double VISIBILITY_VERTEX_CLEARANCE_METERS = 1.75;
    private static final int MAX_VISIBILITY_NODES = 260;
    private static final int MAX_BOUNDARY_CANDIDATES = 32;
    private static final int MAX_ENDPOINT_PORTALS = 16;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final EntryApproachPlanner entryApproachPlanner = new EntryApproachPlanner();

    public enum RouteMode {
        GRID,
        CORRIDOR,
        LONG_RANGE
    }

    public enum RouteRole {
        GENERIC,
        EXISTING_TIE_IN,
        PLANNED_BRANCH,
        JUNCTION_BRANCH,
        EXACT_GRAPH
    }

    public enum EntryStrategy {
        DIRECT_ALLOWED,
        PORTAL_ONLY
    }

    public enum RuleSet {
        DOCUMENT_NEAREST_V1(
                "technical_appendix_lct.docx#2.2",
                "nearest full polygon boundary; one terminal straight segment; own OKS setback exception",
                false),
        EXPERIMENTAL_ANY_BOUNDARY_V1(
                "research profile; not an organizer clarification",
                "ranked points from shell and holes; one terminal straight segment",
                true);

        private final String authorityReference;
        private final String canonicalDefinition;
        private final boolean experimental;

        RuleSet(String authorityReference, String canonicalDefinition, boolean experimental) {
            this.authorityReference = authorityReference;
            this.canonicalDefinition = canonicalDefinition;
            this.experimental = experimental;
        }

        public String getAuthorityReference() {
            return authorityReference;
        }

        public String getCanonicalDefinition() {
            return canonicalDefinition;
        }

        public boolean isExperimental() {
            return experimental;
        }

        public String getProfileHash() {
            String canonical = name() + "|" + authorityReference + "|"
                    + canonicalDefinition + "|experimental=" + experimental;
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8));
                StringBuilder value = new StringBuilder();
                for (byte item : digest) {
                    value.append(String.format("%02x", item));
                }
                return value.toString();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }

    public static final class RouteRequest {
        private final int requiredDiameter;
        private final RouteRole role;

        public RouteRequest(int requiredDiameter, RouteRole role) {
            this.requiredDiameter = Math.max(0, requiredDiameter);
            this.role = Objects.requireNonNull(role, "role");
        }

        public static RouteRequest generic() {
            return new RouteRequest(0, RouteRole.GENERIC);
        }
    }

    public Optional<Route> plan(Coordinate start, Coordinate end, ProblemData data) {
        return plan(start, end, prepare(data), RouteMode.CORRIDOR);
    }

    public RoutingContext prepare(ProblemData data) {
        return prepare(data, false);
    }

    public RoutingContext prepare(ProblemData data, boolean strictEndpointIntersections) {
        return prepare(data, strictEndpointIntersections, () -> true);
    }

    public RoutingContext prepare(ProblemData data, boolean strictEndpointIntersections,
                                  BooleanSupplier canContinue) {
        return prepare(data, strictEndpointIntersections, canContinue,
                EntryStrategy.DIRECT_ALLOWED, RuleSet.DOCUMENT_NEAREST_V1);
    }

    public RoutingContext prepare(ProblemData data, boolean strictEndpointIntersections,
                                  BooleanSupplier canContinue, EntryStrategy entryStrategy) {
        return prepare(data, strictEndpointIntersections, canContinue,
                entryStrategy, RuleSet.DOCUMENT_NEAREST_V1);
    }

    public RoutingContext prepare(ProblemData data, boolean strictEndpointIntersections,
                                  BooleanSupplier canContinue, EntryStrategy entryStrategy,
                                  RuleSet ruleSet) {
        List<InputFeature> routingRestrictions = new ArrayList<>(data.getRestrictions());
        routingRestrictions.addAll(data.getHeatNetworks());
        return new RoutingContext(routingRestrictions, geometryFactory, canContinue,
                entryStrategy, ruleSet);
    }

    public Optional<Route> plan(Coordinate start, Coordinate end, RoutingContext context) {
        return plan(start, end, context, RouteMode.CORRIDOR);
    }

    public Optional<Route> plan(Coordinate start, Coordinate end, RoutingContext context, RouteMode mode) {
        return plan(start, end, context, mode, RouteRequest.generic());
    }

    public Optional<Route> plan(Coordinate start, Coordinate end, RoutingContext context,
                                RouteMode mode, RouteRequest request) {
        if (!context.canContinue()) {
            return Optional.empty();
        }
        RouteKey key = new RouteKey(start, end, mode, request);
        Optional<Route> cached = context.routeCache.get(key);
        if (cached != null) {
            context.cacheHits++;
            return cached;
        }
        context.cacheMisses++;
        Optional<Route> route = planUncached(start, end, context, mode, request);
        if (route.isPresent() || context.canContinue()) {
            context.routeCache.put(key, route);
        } else {
            context.timeoutAbortedRoutes++;
        }
        return route;
    }

    public boolean avoidsForbiddenRestrictions(LineString line, RoutingContext context) {
        return firstForbiddenIntersection(line, context, RouteRequest.generic()) == null;
    }

    public boolean avoidsForbiddenRestrictions(LineString line, RoutingContext context, int diameter) {
        return firstForbiddenIntersection(line, context,
                new RouteRequest(diameter, RouteRole.GENERIC)) == null;
    }

    public boolean hasAllowedTurns(LineString line) {
        return !hasDisallowedTurn(line, MAX_TURN_ANGLE_DEGREES);
    }

    public boolean hasAllowedTransition(LineString incoming, LineString outgoing) {
        Coordinate[] before = incoming.getCoordinates();
        Coordinate[] after = outgoing.getCoordinates();
        if (before.length < 2 || after.length < 2) {
            return false;
        }
        return turnAngle(before[before.length - 2], before[before.length - 1], after[1])
                <= MAX_TURN_ANGLE_DEGREES + TURN_ANGLE_TOLERANCE_DEGREES;
    }

    private Optional<Route> planUncached(Coordinate start, Coordinate end, RoutingContext context,
                                         RouteMode mode, RouteRequest request) {
        context.planAttempts++;
        LineString direct = line(start, end);
        // Keep an independent portal-based candidate in the exact graph. The portfolio can then
        // compare that network against builders that use the direct final OKS entry allowed by the spec.
        if (context.entryStrategy == EntryStrategy.DIRECT_ALLOWED
                && isAllowedRoute(direct, context, start, end, request)) {
            SpecialInfo special = specialInfo(direct, context);
            context.planSuccesses++;
            context.directSuccesses++;
            return Optional.of(new Route(direct, direct.getLength(), special.method,
                    special.coefficient,
                    Collections.singletonList("direct final segment through own OKS footprint")));
        }

        List<EndpointApproach> startApproaches = endpointApproaches(start, context, request);
        List<EndpointApproach> endApproaches = endpointApproaches(end, context, request);
        if (startApproaches.isEmpty() || endApproaches.isEmpty()) {
            context.missingEndpointApproaches++;
            return Optional.empty();
        }
        Route best = null;
        double bestLength = Double.POSITIVE_INFINITY;
        for (EndpointApproach startApproach : startApproaches) {
            for (EndpointApproach endApproach : endApproaches) {
                if (!context.canContinue()) {
                    return Optional.ofNullable(best);
                }
                Optional<Route> core = planCore(startApproach.portal, endApproach.portal, context, mode, request);
                if (!core.isPresent()) {
                    context.coreRouteFailures++;
                    continue;
                }
                LineString combined = combineEndpointApproaches(
                        startApproach, core.get().getMetricGeometry(), endApproach);
                if (!isAllowedRoute(combined, context, start, end, request)) {
                    context.combinedGeometryRejections++;
                    continue;
                }
                if (hasDisallowedTurn(combined)) {
                    context.combinedTurnRejections++;
                    continue;
                }
                List<String> notes = new ArrayList<>(core.get().getNotes());
                if (startApproach.required || endApproach.required) {
                    notes.add("final segment enters its own OKS footprint through a feasible boundary");
                }
                SpecialInfo special = specialInfo(combined, context);
                Route candidate = new Route(combined, combined.getLength(), special.method,
                        special.coefficient, notes);
                if (context.entryStrategy == EntryStrategy.PORTAL_ONLY
                        && request.role == RouteRole.EXACT_GRAPH) {
                    context.planSuccesses++;
                    return Optional.of(candidate);
                }
                if (candidate.getLengthMeters() + 1e-6 < bestLength
                        || (Math.abs(candidate.getLengthMeters() - bestLength) <= 1e-6
                        && (best == null || candidate.getSpecialCoefficient() < best.getSpecialCoefficient()))) {
                    best = candidate;
                    bestLength = candidate.getLengthMeters();
                }
            }
        }
        if (best != null) {
            context.planSuccesses++;
        }
        return Optional.ofNullable(best);
    }

    private Optional<Route> planCore(Coordinate start, Coordinate end, RoutingContext context, RouteMode mode,
                                     RouteRequest request) {
        List<String> notes = new ArrayList<>();
        LineString direct = line(start, end);
        if (isAllowedRoute(direct, context, start, end, request)) {
            SpecialInfo special = specialInfo(direct, context);
            return Optional.of(new Route(direct, direct.getLength(), special.method, special.coefficient, notes));
        }

        if (mode != RouteMode.GRID) {
            Optional<Route> dogleg = dogleg(start, end, context, request);
            dogleg.ifPresent(route -> notes.add("direct route crossed forbidden restriction, used dogleg fallback"));
            if (dogleg.isPresent()) {
                return dogleg;
            }
        }
        Optional<Route> grid = astar(start, end, context, mode == RouteMode.LONG_RANGE, request);
        if (grid.isPresent()) {
            return grid;
        }
        return visibilityGraph(start, end, context, request);
    }

    private Optional<Route> visibilityGraph(Coordinate start, Coordinate end, RoutingContext context,
                                            RouteRequest request) {
        Envelope search = new Envelope(start, end);
        search.expandBy(Math.max(350.0, start.distance(end) * 0.35));
        LineString direct = line(start, end);
        List<Coordinate> candidates = new ArrayList<>();
        candidates.add(new Coordinate(start));
        candidates.add(new Coordinate(end));
        Set<String> seen = new HashSet<>();
        seen.add(coordinateKey(start));
        seen.add(coordinateKey(end));

        for (RestrictionInfo restriction : context.queryRestrictions(search)) {
            if (!restriction.forbidden || !restriction.geometry.getEnvelopeInternal().intersects(search)) {
                continue;
            }
            Geometry safeBoundary;
            try {
                double vertexClearance = Math.max(VISIBILITY_VERTEX_CLEARANCE_METERS,
                        restriction.requiredClearance(request.requiredDiameter)
                                + ENDPOINT_APPROACH_TOLERANCE_METERS);
                safeBoundary = restriction.geometry.buffer(vertexClearance, 2);
            } catch (RuntimeException ignored) {
                continue;
            }
            Coordinate[] coordinates = safeBoundary.getBoundary().getCoordinates();
            int stride = Math.max(1, coordinates.length / 32);
            for (int i = 0; i < coordinates.length; i += stride) {
                Coordinate candidate = coordinates[i];
                if (!search.contains(candidate) || !isPointAllowed(candidate, context, request)) {
                    continue;
                }
                if (seen.add(coordinateKey(candidate))) {
                    candidates.add(new Coordinate(candidate));
                }
            }
        }

        if (candidates.size() <= 2) {
            return Optional.empty();
        }
        if (candidates.size() > MAX_VISIBILITY_NODES) {
            List<Coordinate> obstacleNodes = new ArrayList<>(candidates.subList(2, candidates.size()));
            obstacleNodes.sort(Comparator.comparingDouble(candidate ->
                    direct.distance(geometryFactory.createPoint(candidate))
                            + 0.15 * Math.min(candidate.distance(start), candidate.distance(end))));
            candidates = new ArrayList<>(Arrays.asList(new Coordinate(start), new Coordinate(end)));
            candidates.addAll(obstacleNodes.subList(0, MAX_VISIBILITY_NODES - 2));
        }

        int size = candidates.size();
        double[] distance = new double[size];
        int[] previous = new int[size];
        boolean[] settled = new boolean[size];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);
        distance[0] = 0.0;
        PriorityQueue<VisibilityState> open = new PriorityQueue<>(Comparator.comparingDouble(state -> state.cost));
        open.add(new VisibilityState(0, 0.0));

        while (!open.isEmpty()) {
            if (!context.canContinue()) {
                return Optional.empty();
            }
            VisibilityState state = open.poll();
            if (settled[state.node]) {
                continue;
            }
            settled[state.node] = true;
            if (state.node == 1) {
                break;
            }
            Coordinate from = candidates.get(state.node);
            for (int next = 0; next < size; next++) {
                if (next == state.node || settled[next]) {
                    continue;
                }
                Coordinate to = candidates.get(next);
                LineString edge = line(from, to);
                if (!isAllowedRoute(edge, context, start, end, request)) {
                    continue;
                }
                double nextCost = distance[state.node]
                        + from.distance(to) * specialInfo(edge, context).coefficient;
                if (nextCost + 1e-6 < distance[next]) {
                    distance[next] = nextCost;
                    previous[next] = state.node;
                    open.add(new VisibilityState(next, nextCost));
                }
            }
        }
        if (previous[1] < 0) {
            return Optional.empty();
        }
        List<Coordinate> route = new ArrayList<>();
        for (int node = 1; node >= 0; node = previous[node]) {
            route.add(candidates.get(node));
            if (node == 0) {
                break;
            }
        }
        Collections.reverse(route);
        LineString routeLine = shortcutRoute(line(route.toArray(new Coordinate[0])), context, start, end, request);
        routeLine = smoothDisallowedTurns(routeLine, context, start, end, request);
        if (!isAllowedRoute(routeLine, context, start, end, request) || hasDisallowedTurn(routeLine)) {
            return Optional.empty();
        }
        SpecialInfo special = specialInfo(routeLine, context);
        return Optional.of(new Route(routeLine, routeLine.getLength(), special.method, special.coefficient,
                Collections.singletonList("visibility graph route")));
    }

    private boolean isPointAllowed(Coordinate coordinate, RoutingContext context, RouteRequest request) {
        Point point = geometryFactory.createPoint(coordinate);
        Envelope envelope = new Envelope(coordinate);
        envelope.expandBy(ClearanceRules.MAX_REQUIRED_CLEARANCE_METERS);
        for (RestrictionInfo restriction : context.queryRestrictions(envelope)) {
            double required = restriction.requiredClearance(request.requiredDiameter);
            if (restriction.forbidden && (restriction.prepared.covers(point)
                    || restriction.geometry.distance(point) + ClearanceRules.NUMERIC_TOLERANCE_METERS < required)) {
                return false;
            }
        }
        return true;
    }

    private String coordinateKey(Coordinate coordinate) {
        return Math.round(coordinate.x * 10.0) + ":" + Math.round(coordinate.y * 10.0);
    }

    private List<EndpointApproach> endpointApproaches(Coordinate endpoint, RoutingContext context,
                                                      RouteRequest request) {
        EndpointApproachKey cacheKey = new EndpointApproachKey(endpoint, request.requiredDiameter,
                context.entryStrategy, context.ruleSet);
        List<EndpointApproach> cached = context.endpointApproachCache.get(cacheKey);
        if (cached != null) {
            context.endpointApproachCacheHits++;
            return cached;
        }
        context.endpointApproachCacheMisses++;
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
            context.endpointsOutsideOks++;
            List<EndpointApproach> result = Collections.singletonList(EndpointApproach.notRequired(endpoint));
            context.endpointApproachCache.put(cacheKey, result);
            return result;
        }
        context.endpointsInsideOks++;
        context.containingOksMatches += containing.size();

        Geometry footprint = containing.get(0).geometry;
        for (int i = 1; i < containing.size(); i++) {
            try {
                footprint = footprint.union(containing.get(i).geometry);
            } catch (RuntimeException exception) {
                footprint = footprint.buffer(0).union(containing.get(i).geometry.buffer(0));
            }
        }
        PreparedGeometry preparedFootprint = PreparedGeometryFactory.prepare(footprint);
        List<Coordinate> boundaries = new ArrayList<>();
        if (context.ruleSet == RuleSet.DOCUMENT_NEAREST_V1) {
            List<EntryApproachPlanner.EntryRayAnalysis> analyses = entryApproachPlanner.analyzeNearestRays(
                    endpoint, footprint, request.requiredDiameter);
            String ownerIds = containing.stream().map(item -> item.id)
                    .sorted().reduce((left, right) -> left + "," + right).orElse("unknown");
            for (int i = 0; i < analyses.size(); i++) {
                EntryApproachPlanner.EntryRayAnalysis analysis = analyses.get(i);
                context.entryWitnesses.add(analysis.describe(
                        ownerIds, request.requiredDiameter, i + 1, analyses.size()));
                if (!analysis.isOrdinaryPortalRuledOut()) {
                    boundaries.add(analysis.getBoundary());
                }
            }
            if (boundaries.isEmpty()) {
                context.entryIntervalRejections++;
                List<EndpointApproach> result = Collections.emptyList();
                context.endpointApproachCache.put(cacheKey, result);
                return result;
            }
        } else {
            List<Segment> exterior = new ArrayList<>();
            collectExteriorSegments(footprint, exterior);
            for (Segment segment : exterior) {
                List<Coordinate> candidates = new ArrayList<>();
                candidates.add(projectOnSegment(endpoint, segment.a, segment.b));
                for (double fraction : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
                    candidates.add(new Coordinate(
                            segment.a.x + fraction * (segment.b.x - segment.a.x),
                            segment.a.y + fraction * (segment.b.y - segment.a.y)));
                }
                for (Coordinate candidate : candidates) {
                    if (boundaries.stream().noneMatch(existing -> existing.distance(candidate) < 0.05)) {
                        boundaries.add(candidate);
                    }
                }
            }
        }
        boundaries.sort(Comparator.comparingDouble(endpoint::distance));
        if (boundaries.size() > MAX_BOUNDARY_CANDIDATES) {
            boundaries = new ArrayList<>(boundaries.subList(0, MAX_BOUNDARY_CANDIDATES));
        }
        List<EndpointApproach> approaches = new ArrayList<>();
        double requiredClearance = EntryApproachPlanner.requiredCenterlineClearance(
                request.requiredDiameter);
        for (Coordinate boundary : boundaries) {
            context.boundaryCandidates++;
            double distance = endpoint.distance(boundary);
            if (distance < 0.01) {
                continue;
            }
            List<Coordinate> portalCandidates = entryApproachPlanner.ordinaryPortalCandidates(
                    endpoint, footprint, boundary, request.requiredDiameter);
            for (Coordinate portal : portalCandidates) {
                Point portalPoint = geometryFactory.createPoint(portal);
                if (preparedFootprint.covers(portalPoint)
                        || footprint.distance(portalPoint) + 1e-6 < requiredClearance) {
                    context.portalOwnFootprintRejections++;
                    continue;
                }
                if (!isSingleExitApproach(endpoint, portal, boundary, footprint)) {
                    context.portalSingleExitRejections++;
                    continue;
                }
                if (isAllowedRoute(line(endpoint, portal), context, endpoint, portal, request)) {
                    approaches.add(EndpointApproach.required(endpoint, portal));
                    context.endpointPortalsCreated++;
                } else {
                    context.portalRestrictionRejections++;
                }
                if (approaches.size() >= MAX_ENDPOINT_PORTALS) {
                    break;
                }
            }
            if (approaches.size() >= MAX_ENDPOINT_PORTALS) {
                break;
            }
        }
        List<EndpointApproach> result = Collections.unmodifiableList(new ArrayList<>(approaches));
        context.endpointApproachCache.put(cacheKey, result);
        return result;
    }

    private boolean isSingleExitApproach(Coordinate endpoint, Coordinate portal, Coordinate boundary,
                                         Geometry restriction) {
        LineString approach = line(endpoint, portal);
        if (approach.distance(geometryFactory.createPoint(boundary)) > ENDPOINT_APPROACH_TOLERANCE_METERS) {
            return false;
        }
        Geometry intersection = approach.intersection(restriction);
        return Math.abs(intersection.getLength() - endpoint.distance(boundary))
                <= ENDPOINT_APPROACH_TOLERANCE_METERS;
    }

    private Coordinate projectOnSegment(Coordinate point, Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double length2 = dx * dx + dy * dy;
        if (length2 < 1e-9) {
            return new Coordinate(a);
        }
        double t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / length2;
        t = Math.max(0.0, Math.min(1.0, t));
        return new Coordinate(a.x + t * dx, a.y + t * dy);
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

    private Optional<Route> dogleg(Coordinate start, Coordinate end, RoutingContext context,
                                   RouteRequest request) {
        Geometry blocker = firstForbiddenIntersection(line(start, end), context, request);
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
            routeLine = smoothDisallowedTurns(routeLine, context, start, end, request);
            if (!isAllowedRoute(routeLine, context, start, end, request)) {
                continue;
            }
            if (hasDisallowedTurn(routeLine)) {
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

    private boolean crossesForbidden(LineString line, RoutingContext context, Coordinate start, Coordinate end,
                                     RouteRequest request) {
        return firstForbiddenIntersection(line, context, start, end, request) != null;
    }

    private boolean isAllowedRoute(LineString line, RoutingContext context, Coordinate start, Coordinate end,
                                   RouteRequest request) {
        return !crossesForbidden(line, context, start, end, request)
                && !hasBadSpecialCrossingAngle(line, context);
    }

    private Geometry firstForbiddenIntersection(LineString line, RoutingContext context, RouteRequest request) {
        return firstForbiddenIntersection(line, context,
                line.getCoordinateN(0), line.getCoordinateN(line.getNumPoints() - 1), request);
    }

    private Geometry firstForbiddenIntersection(LineString line, RoutingContext context,
                                                Coordinate start, Coordinate end, RouteRequest request) {
        Point startPoint = geometryFactory.createPoint(start);
        Point endPoint = geometryFactory.createPoint(end);
        Envelope searchEnvelope = new Envelope(line.getEnvelopeInternal());
        searchEnvelope.expandBy(ClearanceRules.MAX_REQUIRED_CLEARANCE_METERS);
        Coordinate[] route = line.getCoordinates();
        for (int i = 0; i < route.length - 1; i++) {
            Envelope segmentEnvelope = new Envelope(route[i], route[i + 1]);
            segmentEnvelope.expandBy(ClearanceRules.MAX_REQUIRED_CLEARANCE_METERS);
            for (SpecialSegment forbidden : context.queryClearanceSegments(segmentEnvelope)) {
                boolean allowedExistingTieInEndpoint = request.role == RouteRole.EXISTING_TIE_IN
                        && i == route.length - 2
                        && "heat_network".equals(forbidden.type)
                        && forbidden.geometry.distance(endPoint)
                        <= ENDPOINT_APPROACH_TOLERANCE_METERS;
                if (allowedExistingTieInEndpoint) {
                    continue;
                }
                double required = ClearanceRules.requiredCenterlineClearance(
                        forbidden.type, request.requiredDiameter, forbidden.existingDiameter);
                double actual = Distance.segmentToSegment(route[i], route[i + 1],
                        forbidden.segment.a, forbidden.segment.b);
                boolean verifiedSpecialCrossing = coefficient(forbidden.type) > 1.0
                        && actual <= ClearanceRules.NUMERIC_TOLERANCE_METERS;
                if (!verifiedSpecialCrossing && actual
                        + ClearanceRules.NUMERIC_TOLERANCE_METERS < required) {
                    return forbidden.geometry;
                }
            }
        }
        for (RestrictionInfo restriction : context.queryAreaRestrictions(searchEnvelope)) {
            boolean endpointRestriction = "oks".equals(restriction.type)
                    && (restriction.prepared.covers(startPoint) || restriction.prepared.covers(endPoint));
            boolean completeRoute = line.getCoordinateN(0).equals2D(start)
                    && line.getCoordinateN(line.getNumPoints() - 1).equals2D(end);
            boolean allowedEndpointTouch = endpointRestriction
                    && (!completeRoute
                    || isCompliantEndpointApproach(
                    line, restriction.geometry, startPoint, endPoint, context.ruleSet,
                    restriction.requiredClearance(request.requiredDiameter),
                    request.role == RouteRole.EXISTING_TIE_IN && line.getNumPoints() == 2));
            Geometry ordinaryPart = allowedEndpointTouch
                    ? ordinaryRouteOutsideEndpointApproaches(line, restriction.geometry, startPoint, endPoint)
                    : line;
            double required = restriction.requiredClearance(request.requiredDiameter);
            boolean verifiedSpecialCrossing = !restriction.forbidden
                    && coefficient(restriction.type) > 1.0
                    && restriction.prepared.intersects(ordinaryPart);
            if (!ordinaryPart.isEmpty() && !verifiedSpecialCrossing
                    && ((restriction.forbidden && restriction.prepared.intersects(ordinaryPart))
                    || ordinaryPart.distance(restriction.geometry)
                    + ClearanceRules.NUMERIC_TOLERANCE_METERS < required)) {
                return restriction.geometry;
            }
        }
        return null;
    }

    private Geometry ordinaryRouteOutsideEndpointApproaches(LineString line, Geometry restriction,
                                                              Point startPoint, Point endPoint) {
        Coordinate[] coordinates = line.getCoordinates();
        int from = restriction.covers(startPoint) ? 1 : 0;
        int to = restriction.covers(endPoint) ? coordinates.length - 2 : coordinates.length - 1;
        if (to <= from) {
            return geometryFactory.createLineString(new Coordinate[0]);
        }
        return line(Arrays.copyOfRange(coordinates, from, to + 1));
    }

    private boolean isCompliantEndpointApproach(LineString line, Geometry restriction,
                                                Point startPoint, Point endPoint,
                                                RuleSet ruleSet, double requiredClearance,
                                                boolean allowDirectPocketTieIn) {
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
                coordinates[0], coordinates[1], restriction, ruleSet, requiredClearance,
                allowDirectPocketTieIn)) {
            return false;
        }
        if (endInside && !isCompliantApproachSegment(
                coordinates[coordinates.length - 1], coordinates[coordinates.length - 2],
                restriction, ruleSet, requiredClearance, allowDirectPocketTieIn)) {
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
                                                Geometry restriction, RuleSet ruleSet,
                                                double requiredClearance,
                                                boolean allowDirectPocketTieIn) {
        Point endpointPoint = geometryFactory.createPoint(endpoint);
        Point outsidePoint = geometryFactory.createPoint(outside);
        if (!restriction.covers(endpointPoint) || restriction.covers(outsidePoint)) {
            return false;
        }
        if (!allowDirectPocketTieIn
                && restriction.distance(outsidePoint) + ENDPOINT_APPROACH_TOLERANCE_METERS
                < requiredClearance) {
            return false;
        }

        LineString approach = line(endpoint, outside);
        if (ruleSet == RuleSet.DOCUMENT_NEAREST_V1) {
            boolean passesEqualNearestBoundary = entryApproachPlanner
                    .nearestBoundaryPoints(endpoint, restriction).stream()
                    .anyMatch(nearestBoundary -> approach.distance(
                            geometryFactory.createPoint(nearestBoundary))
                            <= ENDPOINT_APPROACH_TOLERANCE_METERS);
            if (!passesEqualNearestBoundary) {
                return false;
            }
        }
        Geometry intersection = approach.intersection(restriction);
        return intersection.getNumGeometries() == 1
                && intersection.getLength() + ENDPOINT_APPROACH_TOLERANCE_METERS < approach.getLength();
    }

    private static boolean isForbidden(String type) {
        return "oks".equals(type) || "water".equals(type) || "railway".equals(type) || "park".equals(type)
                || "social_area".equals(type) || "prohibited_site".equals(type);
    }

    private boolean hasBadSpecialCrossingAngle(LineString line, RoutingContext context) {
        Coordinate[] route = line.getCoordinates();
        LineIntersector intersector = new RobustLineIntersector();
        for (int i = 0; i < route.length - 1; i++) {
            Coordinate routeA = route[i];
            Coordinate routeB = route[i + 1];
            Envelope routeEnvelope = new Envelope(routeA, routeB);
            for (SpecialSegment specialSegment : context.querySpecialSegments(routeEnvelope)) {
                intersector.computeIntersection(routeA, routeB,
                        specialSegment.segment.a, specialSegment.segment.b);
                if (intersector.hasIntersection()) {
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
        Coordinate[] route = line.getCoordinates();
        LineIntersector intersector = new RobustLineIntersector();
        for (int i = 0; i < route.length - 1; i++) {
            Envelope segmentEnvelope = new Envelope(route[i], route[i + 1]);
            for (SpecialSegment costSegment : context.queryCostSegments(segmentEnvelope)) {
                intersector.computeIntersection(route[i], route[i + 1],
                        costSegment.segment.a, costSegment.segment.b);
                if (intersector.hasIntersection()) {
                    coefficient = Math.max(coefficient, coefficient(costSegment.type));
                }
            }
        }
        for (RestrictionInfo restriction : context.queryAreaRestrictions(line.getEnvelopeInternal())) {
            if (restriction.prepared.intersects(line)) {
                coefficient = Math.max(coefficient, coefficient(restriction.type));
            }
        }
        return new SpecialInfo(coefficient > 1.0 ? "special" : "base", coefficient);
    }

    private static double coefficient(String restrictionType) {
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

    private Optional<Route> astar(Coordinate start, Coordinate end, RoutingContext context,
                                  boolean longRange, RouteRequest request) {
        double[][] attempts = longRange
                ? new double[][]{
                    {10.0, 1_000.0, 360_000.0, 300_000.0},
                    {6.0, 600.0, 420_000.0, 360_000.0},
                    {20.0, 6_000.0, 500_000.0, 450_000.0}
                }
                : new double[][]{
                    {8.0, 140.0, 160_000.0, 120_000.0},
                    {5.0, 160.0, 220_000.0, 180_000.0},
                    {5.0, 300.0, 320_000.0, 260_000.0}
                };
        for (double[] attempt : attempts) {
            if (!context.canContinue()) {
                return Optional.empty();
            }
            Optional<Route> result = astar(start, end, context, attempt[0], attempt[1],
                    (long) attempt[2], (int) attempt[3], request);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private Optional<Route> astar(Coordinate start, Coordinate end, RoutingContext context, double step,
                                  double padding, long maxCells, int maxExpanded, RouteRequest request) {
        Envelope envelope = new Envelope(start, end);
        envelope.expandBy(padding);
        int minX = (int) Math.floor(envelope.getMinX() / step);
        int maxX = (int) Math.ceil(envelope.getMaxX() / step);
        int minY = (int) Math.floor(envelope.getMinY() / step);
        int maxY = (int) Math.ceil(envelope.getMaxY() / step);
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) > maxCells) {
            return Optional.empty();
        }

        GridNode startNode = nearestReachableGridNode(start, start, end, context, step,
                minX, maxX, minY, maxY, request);
        GridNode endNode = nearestReachableGridNode(end, start, end, context, step,
                minX, maxX, minY, maxY, request);
        if (startNode == null || endNode == null) {
            return Optional.empty();
        }
        PriorityQueue<GridState> open = new PriorityQueue<>(Comparator.comparingDouble(s -> s.priority));
        Map<GridNode, Double> cost = new HashMap<>();
        Map<GridNode, GridNode> previous = new HashMap<>();
        cost.put(startNode, 0.0);
        open.add(new GridState(startNode, heuristic(startNode, endNode, step)));

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxExpanded) {
            if ((expanded & 255) == 0 && !context.canContinue()) {
                return Optional.empty();
            }
            GridNode current = open.poll().node;
            expanded++;
            if (current.equals(endNode)) {
                LineString gridRoute = routeFromGrid(previous, current, startNode, start, end, step);
                LineString routeLine = shortcutRoute(gridRoute, context, start, end, request);
                routeLine = smoothDisallowedTurns(routeLine, context, start, end, request);
                if (!isAllowedRoute(routeLine, context, start, end, request) || hasDisallowedTurn(routeLine)) {
                    routeLine = gridRoute;
                }
                if (!isAllowedRoute(routeLine, context, start, end, request) || hasDisallowedTurn(routeLine)) {
                    return Optional.empty();
                }
                SpecialInfo special = specialInfo(routeLine, context);
                return Optional.of(new Route(routeLine, routeLine.getLength(), special.method, special.coefficient,
                        Collections.singletonList("A* grid route step " + step + " m, padding "
                                + padding + " m")));
            }
            for (GridNode next : current.neighbors()) {
                if (next.x < minX || next.x > maxX || next.y < minY || next.y > maxY) {
                    continue;
                }
                Coordinate a = toCoordinate(current, step);
                Coordinate b = toCoordinate(next, step);
                LineString edge = line(a, b);
                if (!isAllowedRoute(edge, context, start, end, request)) {
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

    private GridNode nearestReachableGridNode(Coordinate exact, Coordinate routeStart, Coordinate routeEnd,
                                              RoutingContext context, double step,
                                              int minX, int maxX, int minY, int maxY,
                                              RouteRequest request) {
        int centerX = clamp((int) Math.round(exact.x / step), minX, maxX);
        int centerY = clamp((int) Math.round(exact.y / step), minY, maxY);
        List<GridNode> candidates = new ArrayList<>();
        for (int radius = 0; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) {
                        continue;
                    }
                    int x = centerX + dx;
                    int y = centerY + dy;
                    if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                        candidates.add(new GridNode(x, y));
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(node -> exact.distance(toCoordinate(node, step))));
        for (GridNode candidate : candidates) {
            Coordinate grid = toCoordinate(candidate, step);
            if (exact.distance(grid) < 0.01
                    || isAllowedRoute(line(exact, grid), context, routeStart, routeEnd, request)) {
                return candidate;
            }
        }
        return null;
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

    private LineString shortcutRoute(LineString routeLine, RoutingContext context,
                                     Coordinate start, Coordinate end, RouteRequest request) {
        Coordinate[] coordinates = routeLine.getCoordinates();
        if (coordinates.length <= 2) {
            return routeLine;
        }
        List<Coordinate> shortened = new ArrayList<>();
        int anchor = 0;
        shortened.add(coordinates[anchor]);
        while (anchor < coordinates.length - 1) {
            int next = anchor + 1;
            for (int candidate = coordinates.length - 1; candidate > anchor + 1; candidate--) {
                if (isAllowedRoute(line(coordinates[anchor], coordinates[candidate]), context, start, end, request)) {
                    next = candidate;
                    break;
                }
            }
            shortened.add(coordinates[next]);
            anchor = next;
        }
        return line(shortened.toArray(new Coordinate[0]));
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

    private LineString smoothDisallowedTurns(LineString routeLine, RoutingContext context,
                                      Coordinate start, Coordinate end, RouteRequest request) {
        List<Coordinate> coordinates = new ArrayList<>(Arrays.asList(routeLine.getCoordinates()));
        boolean changed = true;
        while (changed && coordinates.size() > 2) {
            changed = false;
            for (int i = 1; i < coordinates.size() - 1; i++) {
                double angle = turnAngle(coordinates.get(i - 1), coordinates.get(i), coordinates.get(i + 1));
                if (angle <= MAX_TURN_ANGLE_DEGREES + TURN_ANGLE_TOLERANCE_DEGREES) {
                    continue;
                }
                List<Coordinate> candidate = new ArrayList<>(coordinates);
                candidate.remove(i);
                LineString candidateLine = line(candidate.toArray(new Coordinate[0]));
                if (isAllowedRoute(candidateLine, context, start, end, request)) {
                    coordinates = candidate;
                    changed = true;
                    break;
                }
            }
        }
        return line(coordinates.toArray(new Coordinate[0]));
    }

    private boolean hasDisallowedTurn(LineString line) {
        return hasDisallowedTurn(line, MAX_GENERATED_TURN_ANGLE_DEGREES);
    }

    private boolean hasDisallowedTurn(LineString line, double maximumAngleDegrees) {
        Coordinate[] coordinates = line.getCoordinates();
        for (int i = 1; i < coordinates.length - 1; i++) {
            if (turnAngle(coordinates[i - 1], coordinates[i], coordinates[i + 1])
                    > maximumAngleDegrees + TURN_ANGLE_TOLERANCE_DEGREES) {
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
        private final STRtree areaRestrictionIndex = new STRtree();
        private final STRtree specialSegmentIndex = new STRtree();
        private final STRtree forbiddenSegmentIndex = new STRtree();
        private final STRtree clearanceSegmentIndex = new STRtree();
        private final STRtree costSegmentIndex = new STRtree();
        private final Map<RouteKey, Optional<Route>> routeCache = new HashMap<>();
        private final Map<EndpointApproachKey, List<EndpointApproach>> endpointApproachCache = new HashMap<>();
        private final Set<String> entryWitnesses = new LinkedHashSet<>();
        private long cacheHits;
        private long cacheMisses;
        private long timeoutAbortedRoutes;
        private long planAttempts;
        private long planSuccesses;
        private long directSuccesses;
        private long missingEndpointApproaches;
        private long coreRouteFailures;
        private long combinedGeometryRejections;
        private long combinedTurnRejections;
        private long endpointsOutsideOks;
        private long endpointsInsideOks;
        private long containingOksMatches;
        private long boundaryCandidates;
        private long endpointPortalsCreated;
        private long portalOwnFootprintRejections;
        private long portalSingleExitRejections;
        private long portalRestrictionRejections;
        private long endpointApproachCacheHits;
        private long endpointApproachCacheMisses;
        private long entryIntervalRejections;
        private final BooleanSupplier canContinue;

        private final EntryStrategy entryStrategy;
        private final RuleSet ruleSet;

        private RoutingContext(List<InputFeature> restrictions, GeometryFactory geometryFactory,
                               BooleanSupplier canContinue, EntryStrategy entryStrategy,
                               RuleSet ruleSet) {
            this.canContinue = Objects.requireNonNull(canContinue, "canContinue");
            this.entryStrategy = Objects.requireNonNull(entryStrategy, "entryStrategy");
            this.ruleSet = Objects.requireNonNull(ruleSet, "ruleSet");
            for (InputFeature restriction : restrictions) {
                Geometry geometry = restriction.getMetricGeometry();
                Object declaredType = restriction.getProperties().get("restriction_type");
                String type = declaredType == null && "heat_network".equals(restriction.getObjectType())
                        ? "heat_network" : String.valueOf(declaredType);
                int existingDiameter = restriction.getInt("diameter", 50);
                RestrictionInfo info = new RestrictionInfo(
                        restriction.getId(), geometry, type, existingDiameter,
                        isForbidden(type), geometry.getDimension() == 1,
                        PreparedGeometryFactory.prepare(geometry));
                restrictionIndex.insert(geometry.getEnvelopeInternal(), info);
                if (!info.linear) {
                    areaRestrictionIndex.insert(geometry.getEnvelopeInternal(), info);
                }
                if (info.linear) {
                    List<Segment> restrictionSegments = new ArrayList<>();
                    collectSegmentsStatic(geometry, restrictionSegments);
                    for (Segment segment : restrictionSegments) {
                        LineString segmentLine = geometryFactory.createLineString(
                                new Coordinate[]{segment.a, segment.b});
                        SpecialSegment indexed = new SpecialSegment(
                                segment, segmentLine, type, existingDiameter);
                        Envelope clearanceEnvelope = new Envelope(segmentLine.getEnvelopeInternal());
                        clearanceEnvelope.expandBy(ClearanceRules.MAX_REQUIRED_CLEARANCE_METERS);
                        clearanceSegmentIndex.insert(clearanceEnvelope, indexed);
                        if (info.forbidden) {
                            Envelope forbiddenEnvelope = new Envelope(segmentLine.getEnvelopeInternal());
                            forbiddenEnvelope.expandBy(ClearanceRules.MAX_REQUIRED_CLEARANCE_METERS);
                            forbiddenSegmentIndex.insert(forbiddenEnvelope, indexed);
                        }
                        if (coefficient(type) > 1.0) {
                            costSegmentIndex.insert(segmentLine.getEnvelopeInternal(), indexed);
                        }
                        if ("road".equals(type) || "tram_tracks".equals(type)) {
                            specialSegmentIndex.insert(segmentLine.getEnvelopeInternal(), indexed);
                        }
                    }
                }
            }
            restrictionIndex.build();
            areaRestrictionIndex.build();
            specialSegmentIndex.build();
            forbiddenSegmentIndex.build();
            clearanceSegmentIndex.build();
            costSegmentIndex.build();
        }

        @SuppressWarnings("unchecked")
        private List<RestrictionInfo> queryRestrictions(Envelope envelope) {
            return restrictionIndex.query(envelope);
        }

        @SuppressWarnings("unchecked")
        private List<RestrictionInfo> queryAreaRestrictions(Envelope envelope) {
            return areaRestrictionIndex.query(envelope);
        }

        @SuppressWarnings("unchecked")
        private List<SpecialSegment> querySpecialSegments(Envelope envelope) {
            return specialSegmentIndex.query(envelope);
        }

        @SuppressWarnings("unchecked")
        private List<SpecialSegment> queryForbiddenSegments(Envelope envelope) {
            return forbiddenSegmentIndex.query(envelope);
        }

        @SuppressWarnings("unchecked")
        private List<SpecialSegment> queryClearanceSegments(Envelope envelope) {
            return clearanceSegmentIndex.query(envelope);
        }

        @SuppressWarnings("unchecked")
        private List<SpecialSegment> queryCostSegments(Envelope envelope) {
            return costSegmentIndex.query(envelope);
        }

        public String describeCache() {
            return CACHE_VERSION + " hits=" + cacheHits
                    + ", misses=" + cacheMisses
                    + ", unique=" + routeCache.size()
                    + ", timeout_aborted=" + timeoutAbortedRoutes;
        }

        public String describeRoutingAttempts() {
            return "attempts=" + planAttempts
                    + ", successes=" + planSuccesses
                    + ", direct_successes=" + directSuccesses
                    + ", missing_endpoint_approach=" + missingEndpointApproaches
                    + ", core_failures=" + coreRouteFailures
                    + ", combined_geometry_rejections=" + combinedGeometryRejections
                    + ", combined_turn_rejections=" + combinedTurnRejections
                    + ", endpoints_inside_oks=" + endpointsInsideOks
                    + ", endpoints_outside_oks=" + endpointsOutsideOks
                    + ", containing_oks_matches=" + containingOksMatches
                    + ", boundary_candidates=" + boundaryCandidates
                    + ", endpoint_portals_created=" + endpointPortalsCreated
                    + ", portal_own_footprint_rejections=" + portalOwnFootprintRejections
                    + ", portal_single_exit_rejections=" + portalSingleExitRejections
                    + ", portal_restriction_rejections=" + portalRestrictionRejections
                    + ", entry_cache_hits=" + endpointApproachCacheHits
                    + ", entry_cache_misses=" + endpointApproachCacheMisses
                    + ", entry_interval_rejections=" + entryIntervalRejections;
        }

        public String describeEntryWitnesses() {
            return entryWitnesses.isEmpty() ? "none" : String.join(" | ", entryWitnesses);
        }

        public String describeEntryStrategy() {
            return entryStrategy.name();
        }

        public String describeRuleSet() {
            return ruleSet.name();
        }

        private boolean canContinue() {
            return canContinue.getAsBoolean();
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

    private static void collectExteriorSegments(Geometry geometry, List<Segment> result) {
        if (geometry instanceof Polygon) {
            addSegmentsStatic(((Polygon) geometry).getExteriorRing().getCoordinates(), result);
            return;
        }
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            collectExteriorSegments(geometry.getGeometryN(i), result);
        }
    }

    private static void addSegmentsStatic(Coordinate[] coordinates, List<Segment> result) {
        for (int i = 0; i < coordinates.length - 1; i++) {
            result.add(new Segment(coordinates[i], coordinates[i + 1]));
        }
    }

    private static final class RestrictionInfo {
        private final String id;
        private final Geometry geometry;
        private final String type;
        private final int existingDiameter;
        private final boolean forbidden;
        private final boolean linear;
        private final PreparedGeometry prepared;

        private RestrictionInfo(String id, Geometry geometry, String type, int existingDiameter,
                                boolean forbidden,
                                boolean linear, PreparedGeometry prepared) {
            this.id = id;
            this.geometry = geometry;
            this.type = type;
            this.existingDiameter = existingDiameter;
            this.forbidden = forbidden;
            this.linear = linear;
            this.prepared = prepared;
        }

        private double requiredClearance(int newDiameter) {
            return ClearanceRules.requiredCenterlineClearance(type, newDiameter, existingDiameter);
        }
    }

    private static final class EndpointApproachKey {
        private final long x;
        private final long y;
        private final int requiredDiameter;
        private final EntryStrategy entryStrategy;
        private final RuleSet ruleSet;

        private EndpointApproachKey(Coordinate endpoint, int requiredDiameter,
                                    EntryStrategy entryStrategy, RuleSet ruleSet) {
            this.x = Math.round(endpoint.x * 1000.0);
            this.y = Math.round(endpoint.y * 1000.0);
            this.requiredDiameter = requiredDiameter;
            this.entryStrategy = entryStrategy;
            this.ruleSet = ruleSet;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof EndpointApproachKey)) {
                return false;
            }
            EndpointApproachKey key = (EndpointApproachKey) value;
            return x == key.x && y == key.y && requiredDiameter == key.requiredDiameter
                    && entryStrategy == key.entryStrategy && ruleSet == key.ruleSet;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, requiredDiameter, entryStrategy, ruleSet);
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
        private final String type;
        private final int existingDiameter;

        private SpecialSegment(Segment segment, LineString geometry, String type,
                               int existingDiameter) {
            this.segment = segment;
            this.geometry = geometry;
            this.type = type;
            this.existingDiameter = existingDiameter;
        }
    }

    private static final class RouteKey {
        private final long startX;
        private final long startY;
        private final long endX;
        private final long endY;
        private final RouteMode mode;
        private final int requiredDiameter;
        private final RouteRole role;

        private RouteKey(Coordinate start, Coordinate end, RouteMode mode, RouteRequest request) {
            this.startX = Double.doubleToLongBits(start.x);
            this.startY = Double.doubleToLongBits(start.y);
            this.endX = Double.doubleToLongBits(end.x);
            this.endY = Double.doubleToLongBits(end.y);
            this.mode = mode;
            this.requiredDiameter = request.requiredDiameter;
            this.role = request.role;
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
                    && endX == key.endX && endY == key.endY && mode == key.mode
                    && requiredDiameter == key.requiredDiameter && role == key.role;
        }

        @Override
        public int hashCode() {
            return Objects.hash(startX, startY, endX, endY, mode, requiredDiameter, role);
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

    private static class VisibilityState {
        private final int node;
        private final double cost;

        VisibilityState(int node, double cost) {
            this.node = node;
            this.cost = cost;
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
            List<GridNode> result = new ArrayList<>(16);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        result.add(new GridNode(x + dx, y + dy));
                    }
                }
            }
            int[][] extended = {
                    {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
                    {1, 2}, {1, -2}, {-1, 2}, {-1, -2}
            };
            for (int[] delta : extended) {
                result.add(new GridNode(x + delta[0], y + delta[1]));
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
