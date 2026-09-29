package ru.moshackathon.heatnetwork.service;

import org.junit.jupiter.api.Test;
import ru.moshackathon.heatnetwork.geo.ExportGeometryNormalizer;
import ru.moshackathon.heatnetwork.geo.GeoJsonReader;
import ru.moshackathon.heatnetwork.geo.GeoJsonWriter;
import ru.moshackathon.heatnetwork.geo.GeometryMapper;
import ru.moshackathon.heatnetwork.geo.MetricProjector;
import ru.moshackathon.heatnetwork.model.Solution;
import ru.moshackathon.heatnetwork.solver.AlgorithmId;
import ru.moshackathon.heatnetwork.solver.BaselineSolver;
import ru.moshackathon.heatnetwork.solver.NoCertifiedSolutionException;
import ru.moshackathon.heatnetwork.solver.RoutePlanner;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceServiceCertificationTest {
    @Test
    void certifiedEndpointDoesNotReturnDiagnosticFallbackImplicitly() throws Exception {
        GeoJsonReader reader = mock(GeoJsonReader.class);
        BaselineSolver solver = mock(BaselineSolver.class);
        Solution fallback = new Solution("diagnostic");
        when(reader.read(any())).thenReturn(new ru.moshackathon.heatnetwork.model.ProblemData(
                java.util.Collections.emptyList(), java.util.Collections.emptyList()));
        when(solver.solve(any(), any(), anyLong(), nullable(RoutePlanner.EntryStrategy.class), any()))
                .thenThrow(new NoCertifiedSolutionException("B3", "rejected", fallback));
        TraceService service = new TraceService(reader, solver, mock(GeoJsonWriter.class),
                new ExportGeometryNormalizer(new GeometryMapper(new MetricProjector())));

        assertThrows(NoCertifiedSolutionException.class, () -> service.solve(
                new ByteArrayInputStream(new byte[0]), AlgorithmId.B3, 1000,
                null, RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1, false));
    }

    @Test
    void diagnosticFallbackRequiresExplicitOptInAndIsMarked() throws Exception {
        GeoJsonReader reader = mock(GeoJsonReader.class);
        BaselineSolver solver = mock(BaselineSolver.class);
        Solution fallback = new Solution("diagnostic");
        when(reader.read(any())).thenReturn(new ru.moshackathon.heatnetwork.model.ProblemData(
                java.util.Collections.emptyList(), java.util.Collections.emptyList()));
        when(solver.solve(any(), any(), anyLong(), nullable(RoutePlanner.EntryStrategy.class), any()))
                .thenThrow(new NoCertifiedSolutionException("PORTFOLIO", "rejected", fallback));
        TraceService service = new TraceService(reader, solver, mock(GeoJsonWriter.class),
                new ExportGeometryNormalizer(new GeometryMapper(new MetricProjector())));

        Solution result = service.solve(new ByteArrayInputStream(new byte[0]),
                AlgorithmId.PORTFOLIO, 1000, null,
                RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1, true);

        assertEquals("diagnostic", result.getVariantId());
        assertTrue(result.getDiagnostics().contains("certification_status=DIAGNOSTIC_FALLBACK"));
        assertTrue(result.getDiagnostics().contains("certification_source=PORTFOLIO"));
    }
}
