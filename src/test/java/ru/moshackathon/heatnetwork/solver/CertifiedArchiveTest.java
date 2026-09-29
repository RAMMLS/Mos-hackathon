package ru.moshackathon.heatnetwork.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import ru.moshackathon.heatnetwork.model.NewSegment;
import ru.moshackathon.heatnetwork.model.Solution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertifiedArchiveTest {
    @Test
    void rejectedCandidateCannotReplaceVerifiedSeed() {
        Solution seed = solution("seed", 10.0);
        Solution cheaperButInvalid = solution("invalid", 1.0);
        CertifiedArchive archive = new CertifiedArchive(
                candidate -> !"invalid".equals(candidate.getVariantId()));

        assertTrue(archive.offer("B2-U", seed));
        assertFalse(archive.offer("broken", cheaperButInvalid));

        Solution best = archive.bestSnapshot();
        assertEquals("seed", best.getVariantId());
        assertEquals("B2-U", archive.getBestLabel());
        assertNotSame(seed, best);
    }

    @Test
    void completenessWinsBeforeScore() {
        Solution complete = solution("complete", 100.0);
        Solution partial = solution("partial", 1.0);
        partial.addUnconnected("oks-1", 0.0, "test");
        CertifiedArchive archive = new CertifiedArchive(candidate -> true);

        archive.offer("complete", complete);
        archive.offer("partial", partial);

        assertEquals("complete", archive.getBestLabel());
    }

    @Test
    void returnedSnapshotCannotMutateStoredBest() {
        CertifiedArchive archive = new CertifiedArchive(candidate -> true);
        archive.offer("seed", solution("seed", 10.0));

        Solution first = archive.bestSnapshot();
        first.addUnconnected("late-mutation", 0.0, "test");

        assertTrue(archive.bestSnapshot().getUnconnectedConnectionPointIds().isEmpty());
    }

    @Test
    void countsEquivalentGeometryAsOneConstruction() {
        CertifiedArchive archive = new CertifiedArchive(candidate -> true);
        archive.offer("first", networkSolution("first", "segment-a", false));
        archive.offer("same-reversed", networkSolution("second", "different-id", true));

        assertEquals(2, archive.getAcceptedLabels().size());
        assertEquals(1, archive.getUniqueConstructionCount());
        assertEquals(1, archive.getDuplicateConstructionCount());
    }

    @Test
    void distinguishesDifferentConstructedGeometry() {
        CertifiedArchive archive = new CertifiedArchive(candidate -> true);
        archive.offer("first", networkSolution("first", "segment-a", false));
        Solution shifted = networkSolution("shifted", "segment-b", false);
        GeometryFactory geometryFactory = new GeometryFactory();
        shifted.addSegment(new NewSegment("extra", "shifted", "c", "d",
                geometryFactory.createLineString(new Coordinate[]{
                        new Coordinate(50.0, 50.0), new Coordinate(60.0, 50.0)
                }), 5.0, 80, 10.0, "channel", 10.0));
        archive.offer("shifted", shifted);

        assertEquals(2, archive.getUniqueConstructionCount());
        assertEquals(0, archive.getDuplicateConstructionCount());
    }

    @Test
    void millimeterFingerprintDoesNotCollapseUsefulNearbyRoutes() {
        CertifiedArchive archive = new CertifiedArchive(candidate -> true);
        archive.offer("first", networkSolution("first", "segment-a", false));
        Solution shifted = networkSolutionAtY("shifted", 0.02);
        archive.offer("shifted", shifted);

        assertEquals(2, archive.getUniqueConstructionCount());
        assertEquals(0, archive.getDuplicateConstructionCount());
    }

    private Solution solution(String id, double cost) {
        Solution solution = new Solution(id);
        solution.addExistingChamberTieIn(cost);
        return solution;
    }

    private Solution networkSolution(String variantId, String segmentId, boolean reverse) {
        GeometryFactory geometryFactory = new GeometryFactory();
        Coordinate[] coordinates = reverse
                ? new Coordinate[]{new Coordinate(10.0, 0.0), new Coordinate(0.0, 0.0)}
                : new Coordinate[]{new Coordinate(0.0, 0.0), new Coordinate(10.0, 0.0)};
        Solution solution = new Solution(variantId);
        solution.addSegment(new NewSegment(segmentId, variantId, "a", "b",
                geometryFactory.createLineString(coordinates),
                5.0, 80, 10.0, "channel", 10.0));
        return solution;
    }

    private Solution networkSolutionAtY(String variantId, double y) {
        GeometryFactory geometryFactory = new GeometryFactory();
        Solution solution = new Solution(variantId);
        solution.addSegment(new NewSegment("segment", variantId, "a", "b",
                geometryFactory.createLineString(new Coordinate[]{
                        new Coordinate(0.0, y), new Coordinate(10.0, y)
                }), 5.0, 80, 10.0, "channel", 10.0));
        return solution;
    }
}
