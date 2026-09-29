package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.ProblemData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Produces conservative local-entry diagnostics without replacing route search. */
final class EntryFeasibilityInspector {
    enum Status {
        ENTRY_FOUND,
        ENTRY_CANDIDATES_EXHAUSTED,
        ENTRY_BUDGET_EXHAUSTED,
        ENTRY_BLOCKED_WITH_WITNESS
    }

    static final class Finding {
        private final String connectionId;
        private final Status status;
        private final List<String> witnesses;

        Finding(String connectionId, Status status, List<String> witnesses) {
            this.connectionId = connectionId;
            this.status = status;
            this.witnesses = Collections.unmodifiableList(new ArrayList<>(witnesses));
        }

        String getConnectionId() {
            return connectionId;
        }

        Status getStatus() {
            return status;
        }

        List<String> getWitnesses() {
            return witnesses;
        }
    }

    private static final double ROOT_TOLERANCE_METERS = 0.05;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final EntryApproachPlanner entryPlanner = new EntryApproachPlanner();
    private final DiameterCatalog diameterCatalog;

    EntryFeasibilityInspector(DiameterCatalog diameterCatalog) {
        this.diameterCatalog = diameterCatalog;
    }

    Finding inspect(InputFeature connection, ProblemData data,
                    RoutePlanner.RuleSet ruleSet, boolean budgetExhausted) {
        if (ruleSet != RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1) {
            return unresolved(connection.getId(), budgetExhausted);
        }
        Coordinate endpoint = connection.getMetricGeometry().getCoordinate();
        Point point = geometryFactory.createPoint(endpoint);
        List<InputFeature> containing = data.getRestrictions().stream()
                .filter(feature -> "oks".equals(String.valueOf(
                        feature.getProperties().get("restriction_type"))))
                .filter(feature -> feature.getMetricGeometry().covers(point))
                .collect(Collectors.toList());
        if (containing.isEmpty()) {
            return unresolved(connection.getId(), budgetExhausted);
        }

        Geometry owner = containing.get(0).getMetricGeometry();
        for (int index = 1; index < containing.size(); index++) {
            try {
                owner = owner.union(containing.get(index).getMetricGeometry());
            } catch (RuntimeException exception) {
                owner = owner.buffer(0).union(containing.get(index).getMetricGeometry().buffer(0));
            }
        }
        int diameter;
        try {
            diameter = diameterCatalog.select(
                    Math.max(0.001, connection.getDouble("flow_tph", 0.001)), 0).getDiameter();
        } catch (IllegalArgumentException exception) {
            return unresolved(connection.getId(), budgetExhausted);
        }
        List<EntryApproachPlanner.EntryRayAnalysis> rays =
                entryPlanner.analyzeNearestRays(endpoint, owner, diameter);
        String owners = containing.stream().map(InputFeature::getId).sorted()
                .collect(Collectors.joining(","));
        List<String> witnesses = new ArrayList<>();
        boolean everyRayBlocked = !rays.isEmpty();
        boolean existingRootInFreeInterval = false;
        for (int index = 0; index < rays.size(); index++) {
            EntryApproachPlanner.EntryRayAnalysis ray = rays.get(index);
            witnesses.add(ray.describe(owners, diameter, index + 1, rays.size()));
            everyRayBlocked &= ray.isOrdinaryPortalRuledOut();
            LineString freeInterval = ray.getFirstFreeInterval(geometryFactory);
            if (freeInterval != null && hasExistingRoot(freeInterval, data)) {
                existingRootInFreeInterval = true;
            }
        }
        if (everyRayBlocked && !existingRootInFreeInterval) {
            return new Finding(connection.getId(), Status.ENTRY_BLOCKED_WITH_WITNESS, witnesses);
        }
        return unresolved(connection.getId(), budgetExhausted);
    }

    private Finding unresolved(String id, boolean budgetExhausted) {
        return new Finding(id, budgetExhausted
                ? Status.ENTRY_BUDGET_EXHAUSTED
                : Status.ENTRY_CANDIDATES_EXHAUSTED, Collections.emptyList());
    }

    private boolean hasExistingRoot(LineString freeInterval, ProblemData data) {
        return data.getHeatNetworks().stream().anyMatch(feature ->
                feature.getMetricGeometry().distance(freeInterval) <= ROOT_TOLERANCE_METERS)
                || data.getHeatChambers().stream().anyMatch(feature ->
                feature.getMetricGeometry().distance(freeInterval) <= ROOT_TOLERANCE_METERS);
    }
}
