package ru.moshackathon.heatnetwork.service;

import org.springframework.stereotype.Service;
import ru.moshackathon.heatnetwork.solver.BaselineSolver;
import ru.moshackathon.heatnetwork.geo.GeoJsonReader;
import ru.moshackathon.heatnetwork.geo.GeoJsonWriter;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Solution;

import java.io.IOException;
import java.io.InputStream;

@Service
public class TraceService {
    private final GeoJsonReader reader;
    private final BaselineSolver solver;
    private final GeoJsonWriter writer;

    public TraceService(GeoJsonReader reader, BaselineSolver solver, GeoJsonWriter writer) {
        this.reader = reader;
        this.solver = solver;
        this.writer = writer;
    }

    public String trace(InputStream input) throws IOException {
        ProblemData data = reader.read(input);
        Solution solution = solver.solve(data);
        return writer.write(solution);
    }
}
