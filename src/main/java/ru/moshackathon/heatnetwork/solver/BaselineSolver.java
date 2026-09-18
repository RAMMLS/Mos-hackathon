package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class BaselineSolver {
    private static final String VARIANT_ID = "variant_1";
    private static final double TIE_IN_COST = 5_000_000.0;
    private static final int EXISTING_CANDIDATE_LIMIT = 5;
    private static final int PLANNED_PARENT_LIMIT = 3;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final TieInFinder tieInFinder;
    private final RoutePlanner routePlanner;
    private final DiameterCatalog diameterCatalog;

    public BaselineSolver(TieInFinder tieInFinder, RoutePlanner routePlanner, DiameterCatalog diameterCatalog) {
        this.tieInFinder = tieInFinder;
        this.routePlanner = routePlanner;
        this.diameterCatalog = diameterCatalog;
    }

    public Solution solve(ProblemData data) {
        Solution solution = new Solution(VARIANT_ID);
        data.getDiagnostics().forEach(solution::addDiagnostic);
        solution.addDiagnostic("tree mode: greedy shared new-network branches, no existing-network reconstruction");

        List<InputFeature> connectionPoints = new ArrayList<>(data.getConnectionPoints());
        connectionPoints.sort(Comparator.comparingDouble((InputFeature f) -> f.getDouble("flow_tph", 0)).reversed()
                .thenComparing(InputFeature::getId));
        List<TreeEdge> edges = new ArrayList<>();
        List<InputFeature> connected = new ArrayList<>();
        for (InputFeature connection : connectionPoints) {
            connectTree(connection, data, solution, connected, edges);
        }
        emitTree(edges, solution);
        return solution;
    }

    private void connectTree(InputFeature connection, ProblemData data, Solution solution,
                             List<InputFeature> connected, List<TreeEdge> edges) {
        double flow = connection.getDouble("flow_tph", Double.NaN);
        if (Double.isNaN(flow) || flow <= 0) {
            solution.addUnconnected(connection.getId(), penalty(0), "missing or non-positive flow_tph");
            return;
        }

        Coordinate start = connection.getMetricGeometry().getCoordinate();
        TreeEdge best = null;
        List<TieInCandidate> existingCandidates = tieInFinder.find(connection, data);
        for (int i = 0; i < Math.min(EXISTING_CANDIDATE_LIMIT, existingCandidates.size()); i++) {
            TieInCandidate candidate = existingCandidates.get(i);
            Optional<Route> route = routePlanner.plan(start, candidate.getMetricPoint(), data);
            if (!route.isPresent()) {
                continue;
            }
            TreeEdge edge = TreeEdge.toExisting(connection, candidate, route.get());
            if (best == null || edge.initialScore() < best.initialScore()) {
                best = edge;
            }
            break;
        }
        List<InputFeature> plannedParents = new ArrayList<>(connected);
        plannedParents.sort(Comparator.comparingDouble(parent ->
                parent.getMetricGeometry().getCoordinate().distance(start)));
        for (int i = 0; i < Math.min(PLANNED_PARENT_LIMIT, plannedParents.size()); i++) {
            InputFeature parent = plannedParents.get(i);
            Coordinate parentPoint = parent.getMetricGeometry().getCoordinate();
            if (best != null && parentPoint.distance(start) >= best.initialScore()) {
                continue;
            }
            Optional<Route> route = routePlanner.plan(start, parentPoint, data);
            if (!route.isPresent()) {
                continue;
            }
            TreeEdge edge = TreeEdge.toConnection(connection, parent, route.get());
            if (best == null || edge.initialScore() < best.initialScore()) {
                best = edge;
            }
        }

        if (best == null) {
            solution.addUnconnected(connection.getId(), penalty(flow), "no route to existing or planned network found");
            return;
        }
        edges.add(best);
        connected.add(connection);
    }

    private void emitTree(List<TreeEdge> edges, Solution solution) {
        Map<String, List<TreeEdge>> childrenByParent = new HashMap<>();
        for (TreeEdge edge : edges) {
            childrenByParent.computeIfAbsent(edge.parentNodeId, ignored -> new ArrayList<>()).add(edge);
        }
        edges.stream()
                .sorted(Comparator.comparingDouble((TreeEdge edge) -> edge.child.getDouble("flow_tph", 0)).reversed()
                        .thenComparing(edge -> edge.child.getId()))
                .forEach(edge -> emitEdge(edge, aggregateFlow(edge, childrenByParent), solution));
    }

    private double aggregateFlow(TreeEdge edge, Map<String, List<TreeEdge>> childrenByParent) {
        double flow = edge.child.getDouble("flow_tph", 0);
        for (TreeEdge childEdge : childrenByParent.getOrDefault(edge.childNodeId, new ArrayList<>())) {
            flow += aggregateFlow(childEdge, childrenByParent);
        }
        return flow;
    }

    private void emitEdge(TreeEdge edge, double flow, Solution solution) {
        DiameterCatalog.Row diameter = diameterCatalog.select(flow, edge.route.getLengthMeters());
        double constructionCost = edge.route.getLengthMeters()
                    * diameter.getNewConstructionRubPerMeter()
                    * edge.route.getSpecialCoefficient();
        solution.addSegment(new NewSegment(
                "hn_" + edge.child.getId(),
                VARIANT_ID,
                edge.childNodeId,
                edge.parentNodeId,
                edge.route.getMetricGeometry(),
                flow,
                diameter.getDiameter(),
                edge.route.getLengthMeters(),
                edge.route.getLayingMethod(),
                constructionCost));
        if (edge.candidate == null) {
            return;
        }
        String tieInId = edge.parentNodeId;
        Point tiePoint = geometryFactory.createPoint(edge.candidate.getMetricPoint());
        solution.addTieIn(new TieInOutput(
                tieInId,
                VARIANT_ID,
                tiePoint,
                edge.candidate,
                diameter.getDiameter(),
                TIE_IN_COST));
        if (edge.candidate.getHostType() == TieInCandidate.HostType.EXISTING_PIPE) {
            solution.addChamber(new ChamberOutput(
                    "ch_" + edge.child.getId(),
                    VARIANT_ID,
                    tiePoint,
                    diameter.getDiameter(),
                    diameterCatalog.chamberCost(diameter.getDiameter())));
        }
    }

    private double penalty(double flowTph) {
        return 100_000_000.0 + 500_000.0 * Math.max(0, flowTph);
    }

    private static class TreeEdge {
        private final InputFeature child;
        private final String childNodeId;
        private final String parentNodeId;
        private final Route route;
        private final TieInCandidate candidate;

        private TreeEdge(InputFeature child, String parentNodeId, Route route, TieInCandidate candidate) {
            this.child = child;
            this.childNodeId = "cp_" + child.getId();
            this.parentNodeId = parentNodeId;
            this.route = route;
            this.candidate = candidate;
        }

        static TreeEdge toExisting(InputFeature child, TieInCandidate candidate, Route route) {
            return new TreeEdge(child, "tie_" + child.getId(), route, candidate);
        }

        static TreeEdge toConnection(InputFeature child, InputFeature parent, Route route) {
            return new TreeEdge(child, "cp_" + parent.getId(), route, null);
        }

        double initialScore() {
            return route.getLengthMeters() * route.getSpecialCoefficient() + (candidate == null ? 0.0 : 80.0);
        }
    }
}
