package ru.moshackathon.heatnetwork.solver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.moshackathon.heatnetwork.geo.GeoJsonReader;
import ru.moshackathon.heatnetwork.geo.GeometryMapper;
import ru.moshackathon.heatnetwork.geo.MetricProjector;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Solution;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BaselineSolverTopologyIntegrationTest {
    @Test
    @Timeout(120)
    void parallelBranchesAreRebuiltAsOneCertifiedTree() throws Exception {
        MetricProjector projector = new MetricProjector();
        GeoJsonReader reader = new GeoJsonReader(new GeometryMapper(projector));
        Path input = Path.of("data/rl_large/scenes/fifty_fifty/058_spb_parnas_+0_+1_bcd054e3.geojson");
        ProblemData data;
        try (InputStream stream = Files.newInputStream(input)) {
            data = reader.read(stream);
        }
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            BaselineSolver solver = new BaselineSolver(
                    new TieInFinder(projector), new RoutePlanner(), new DiameterCatalog(),
                    mock(R1Solver.class), mock(R2Solver.class), executor);

            Solution solution = solver.solve(data, AlgorithmId.B3, 60_000,
                    null, RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);

            assertEquals(4, data.getConnectionPoints().size()
                    - solution.getUnconnectedConnectionPointIds().size());
            assertTrue(solver.isCertifiedAfterExport(data, solution, null,
                    RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1));
        } finally {
            executor.shutdownNow();
        }
    }
}
