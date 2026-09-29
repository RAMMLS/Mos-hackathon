package ru.moshackathon.heatnetwork.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntryApproachPlannerTest {
    private final GeometryFactory factory = new GeometryFactory();
    private final EntryApproachPlanner planner = new EntryApproachPlanner();

    @Test
    void convexOwnerDoesNotRuleOutOrdinaryPortal() {
        Polygon owner = factory.createPolygon(ring(
                0, 0, 20, 0, 20, 20, 0, 20, 0, 0));

        EntryApproachPlanner.EntryRayAnalysis analysis = planner.analyzeNearestRay(
                new Coordinate(10, 10), owner, 100);

        assertFalse(analysis.isOrdinaryPortalRuledOut());
        assertTrue(analysis.describe("owner", 100).contains("first_reentry_m=none"));
    }

    @Test
    void holeCreatesBoundedGapThatCannotFitRequiredClearance() {
        LinearRing shell = ring(0, 0, 20, 0, 20, 20, 0, 20, 0, 0);
        LinearRing hole = ring(8, 8, 12, 8, 12, 12, 8, 12, 8, 8);
        Polygon owner = factory.createPolygon(shell, new LinearRing[]{hole});

        EntryApproachPlanner.EntryRayAnalysis analysis = planner.analyzeNearestRay(
                new Coordinate(7, 10), owner, 100);

        assertTrue(analysis.isOrdinaryPortalRuledOut());
        assertTrue(analysis.describe("owner", 100).contains("first_gap_m=4.0"));
        assertTrue(analysis.describe("owner", 100)
                .contains("reason=FIXED_NEAREST_RAY_CLEARANCE_IMPOSSIBLE"));
    }

    @Test
    void concaveReentryCannotBeSkippedByLongerProbe() {
        Polygon owner = factory.createPolygon(ring(
                0, 0, 20, 0, 20, 20, 12, 20, 12, 8,
                8, 8, 8, 20, 0, 20, 0, 0));

        EntryApproachPlanner.EntryRayAnalysis analysis = planner.analyzeNearestRay(
                new Coordinate(7, 7), owner, 100);

        assertTrue(analysis.isOrdinaryPortalRuledOut());
        assertTrue(analysis.describe("owner", 100).contains("first_reentry_m="));
    }

    @Test
    void clearanceIncludesOksSetbackAndHalfPairWidth() {
        assertEquals(5.255, EntryApproachPlanner.requiredCenterlineClearance(100), 1e-9);
        assertEquals(5.300, EntryApproachPlanner.requiredCenterlineClearance(125), 1e-9);
        assertEquals(7.835, EntryApproachPlanner.requiredCenterlineClearance(500), 1e-9);
    }

    @Test
    void checksEveryDistinctEqualNearestBoundaryPoint() {
        LinearRing shell = ring(0, 0, 20, 0, 20, 20, 0, 20, 0, 0);
        LinearRing hole = ring(10, 8, 14, 8, 14, 12, 10, 12, 10, 8);
        Polygon owner = factory.createPolygon(shell, new LinearRing[]{hole});

        List<EntryApproachPlanner.EntryRayAnalysis> analyses = planner.analyzeNearestRays(
                new Coordinate(5, 10), owner, 100);

        assertEquals(2, analyses.size());
        assertEquals(1, analyses.stream()
                .filter(EntryApproachPlanner.EntryRayAnalysis::isOrdinaryPortalRuledOut)
                .count());
        assertTrue(analyses.get(0).describe("owner", 100, 1, analyses.size())
                .contains("nearest_ray=1/2"));
    }

    private LinearRing ring(double... xy) {
        Coordinate[] coordinates = new Coordinate[xy.length / 2];
        for (int i = 0; i < xy.length; i += 2) {
            coordinates[i / 2] = new Coordinate(xy[i], xy[i + 1]);
        }
        return factory.createLinearRing(coordinates);
    }
}
