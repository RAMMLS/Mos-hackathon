package ru.moshackathon.heatnetwork.service;

import org.springframework.stereotype.Service;
import ru.moshackathon.heatnetwork.solver.BaselineSolver;
import ru.moshackathon.heatnetwork.solver.AlgorithmId;
import ru.moshackathon.heatnetwork.solver.RoutePlanner;
import ru.moshackathon.heatnetwork.solver.NoCertifiedSolutionException;
import ru.moshackathon.heatnetwork.geo.GeoJsonReader;
import ru.moshackathon.heatnetwork.geo.GeoJsonWriter;
import ru.moshackathon.heatnetwork.geo.ExportGeometryNormalizer;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Solution;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Service
public class TraceService {
    private final GeoJsonReader reader;
    private final BaselineSolver solver;
    private final GeoJsonWriter writer;
    private final ExportGeometryNormalizer exportGeometryNormalizer;

    public TraceService(GeoJsonReader reader, BaselineSolver solver, GeoJsonWriter writer,
                        ExportGeometryNormalizer exportGeometryNormalizer) {
        this.reader = reader;
        this.solver = solver;
        this.writer = writer;
        this.exportGeometryNormalizer = exportGeometryNormalizer;
    }

    public String trace(InputStream input) throws IOException {
        return trace(input, AlgorithmId.PORTFOLIO);
    }

    public String trace(InputStream input, AlgorithmId algorithm) throws IOException {
        return trace(input, algorithm, 0);
    }

    public String trace(InputStream input, AlgorithmId algorithm, long budgetMs) throws IOException {
        return trace(input, algorithm, budgetMs, null,
                RoutePlanner.RuleSet.CLARIFIED_EXTERIOR_BOUNDARY_V1);
    }

    public String trace(InputStream input, AlgorithmId algorithm, long budgetMs,
                        RoutePlanner.EntryStrategy entryStrategy,
                        RoutePlanner.RuleSet ruleSet) throws IOException {
        return writer.write(solve(input, algorithm, budgetMs, entryStrategy, ruleSet));
    }

    public Solution solve(InputStream input, AlgorithmId algorithm, long budgetMs,
                          RoutePlanner.EntryStrategy entryStrategy,
                          RoutePlanner.RuleSet ruleSet) throws IOException {
        return solve(input, algorithm, budgetMs, entryStrategy, ruleSet, false);
    }

    public Solution solve(InputStream input, AlgorithmId algorithm, long budgetMs,
                          RoutePlanner.EntryStrategy entryStrategy,
                          RoutePlanner.RuleSet ruleSet,
                          boolean diagnosticFallback) throws IOException {
        ProblemData data = reader.read(input);
        try {
            Solution solution = solver.solve(data, algorithm, budgetMs, entryStrategy, ruleSet);
            Solution exported = exportGeometryNormalizer.normalize(solution, data);
            if (!solver.isCertifiedAfterExport(data, exported, entryStrategy, ruleSet)) {
                throw new NoCertifiedSolutionException("POST_EXPORT",
                        "serialized coordinates failed geometry certification", exported);
            }
            exported.addDiagnostic("certification_status=CERTIFIED_AFTER_EXPORT");
            exported.setOutcome(data.getConnectionPoints().size(), true);
            return exported;
        } catch (NoCertifiedSolutionException exception) {
            if (!diagnosticFallback || exception.getDiagnosticFallback() == null) {
                throw exception;
            }
            Solution fallback = exception.getDiagnosticFallback();
            fallback.addDiagnostic("certification_status=DIAGNOSTIC_FALLBACK");
            fallback.addDiagnostic("certification_source=" + exception.getSource());
            fallback.addDiagnostic("certification_failure=" + exception.getMessage());
            fallback.setOutcome(data.getConnectionPoints().size(), false);
            return fallback;
        }
    }

    public void write(Solution solution, OutputStream output) throws IOException {
        writer.write(solution, output);
    }
}
