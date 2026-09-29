package ru.moshackathon.heatnetwork.geo;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.moshackathon.heatnetwork.model.NewSegment;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Solution;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportGeometryNormalizerTest {
    @Test
    void exportedRoundTripKeepsSubMillimeterCoordinateError() {
        MetricProjector projector = new MetricProjector();
        GeometryMapper mapper = new GeometryMapper(projector);
        GeometryFactory geometryFactory = new GeometryFactory();
        Coordinate source = projector.toMetric(new Coordinate(30.3619591, 60.076681449999995));
        Coordinate end = new Coordinate(source.x + 10.0, source.y + 5.0);
        Solution solution = new Solution("variant");
        solution.setFullConnectivityStatus("PARTIAL_ENTRY_BLOCKED_WITH_WITNESS");
        solution.addSegment(new NewSegment("segment", "variant", "a", "b",
                geometryFactory.createLineString(new Coordinate[]{source, end}),
                5.0, 80, Math.hypot(10.0, 5.0), "base", 100.0));

        Solution normalized = new ExportGeometryNormalizer(mapper).normalize(solution,
                new ProblemData(Collections.emptyList(), Collections.emptyList()));

        Coordinate actual = normalized.getSegments().get(0).getMetricGeometry().getCoordinateN(0);
        assertTrue(source.distance(actual) < 0.001);
        assertEquals(solution.getSegments().get(0).getFlowTph(),
                normalized.getSegments().get(0).getFlowTph());
        assertEquals(solution.getCalculatedCost(), normalized.getCalculatedCost());
        assertEquals("PARTIAL_ENTRY_BLOCKED_WITH_WITNESS",
                normalized.getFullConnectivityStatus());
    }

    @Test
    void moscowConnectionPointRoundTripsWithoutChangingNearestFace() {
        MetricProjector projector = new MetricProjector();
        Coordinate source = new Coordinate(37.61541085, 55.7792578);

        Coordinate roundTripped = projector.toLonLat(projector.toMetric(source));

        assertEquals(source.x, roundTripped.x, 1e-10);
        assertEquals(source.y, roundTripped.y, 1e-10);
    }

    @Test
    void fullConnectivityOutcomeSurvivesRoundTripWithoutReusingCertificate() {
        MetricProjector projector = new MetricProjector();
        GeometryMapper mapper = new GeometryMapper(projector);
        for (String status : new String[]{"FULL", "PARTIAL_ENTRY_BLOCKED_WITH_WITNESS",
                "PARTIAL_ENTRY_CANDIDATES_EXHAUSTED", "PARTIAL_ENTRY_BUDGET_EXHAUSTED"}) {
            Solution source = new Solution("v");
            source.setFullConnectivityStatus(status);
            source.setOutcome(0, true);

            Solution normalized = new ExportGeometryNormalizer(mapper).normalize(source,
                    new ProblemData(Collections.emptyList(), Collections.emptyList()));

            assertEquals(status, normalized.getFullConnectivityStatus());
            org.junit.jupiter.api.Assertions.assertFalse(normalized.isCertified());
        }
    }
}
