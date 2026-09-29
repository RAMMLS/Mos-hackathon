package ru.moshackathon.heatnetwork.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.ProblemData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EntryFeasibilityInspectorTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final EntryFeasibilityInspector inspector =
            new EntryFeasibilityInspector(new DiameterCatalog());

    @Test
    void reportsVerifiedBlockWhenEveryNearestRayHasTooLittleClearance() {
        InputFeature target = target();

        EntryFeasibilityInspector.Finding finding = inspector.inspect(
                target, problem(target, false), RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1, false);

        assertEquals(EntryFeasibilityInspector.Status.ENTRY_BLOCKED_WITH_WITNESS,
                finding.getStatus());
        assertFalse(finding.getWitnesses().isEmpty());
    }

    @Test
    void existingNetworkInFirstFreeIntervalPreventsFalseBlockedClaim() {
        InputFeature target = target();

        EntryFeasibilityInspector.Finding finding = inspector.inspect(
                target, problem(target, true), RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1, false);

        assertEquals(EntryFeasibilityInspector.Status.ENTRY_CANDIDATES_EXHAUSTED,
                finding.getStatus());
    }

    @Test
    void parsesPublicFirstFullAlgorithmName() {
        assertEquals(AlgorithmId.FIRST_FULL, AlgorithmId.parse("FIRST-FULL"));
        assertEquals(AlgorithmId.FIRST_FULL, AlgorithmId.parse("first_full"));
    }

    private ProblemData problem(InputFeature target, boolean withRoot) {
        List<InputFeature> features = new ArrayList<>();
        features.add(target);
        Polygon owner = factory.createPolygon(
                ring(0, 0, 20, 0, 20, 20, 0, 20, 0, 0),
                new LinearRing[]{ring(8, 8, 12, 8, 12, 12, 8, 12, 8, 8)});
        features.add(feature("owner", "restriction",
                Map.of("restriction_type", "oks"), owner));
        if (withRoot) {
            Geometry network = factory.createLineString(new Coordinate[]{
                    new Coordinate(9, 9), new Coordinate(9, 11)});
            features.add(feature("network", "heat_network", Map.of("diameter", 100), network));
        }
        return new ProblemData(features, List.of());
    }

    private InputFeature target() {
        return feature("target", "oks_connection_point", Map.of("flow_tph", 10.0),
                factory.createPoint(new Coordinate(7, 10)));
    }

    private InputFeature feature(String id, String type, Map<String, Object> properties,
                                 Geometry geometry) {
        return new InputFeature(id, type, new LinkedHashMap<>(properties), geometry, geometry);
    }

    private LinearRing ring(double... xy) {
        Coordinate[] coordinates = new Coordinate[xy.length / 2];
        for (int index = 0; index < xy.length; index += 2) {
            coordinates[index / 2] = new Coordinate(xy[index], xy[index + 1]);
        }
        return factory.createLinearRing(coordinates);
    }
}
