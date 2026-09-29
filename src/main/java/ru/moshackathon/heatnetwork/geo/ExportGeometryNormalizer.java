package ru.moshackathon.heatnetwork.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.ChamberOutput;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.NewSegment;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Solution;
import ru.moshackathon.heatnetwork.model.TechnicalNodeOutput;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reconstructs the exact metric geometry that a consumer reads from exported GeoJSON. */
@Component
public final class ExportGeometryNormalizer {
    private static final double COORDINATE_SCALE = 1_000_000_000.0;
    private static final double EXISTING_CHAMBER_TIE_IN_COST = 5_000_000.0;

    private final GeometryMapper geometryMapper;
    private final GeometryFactory geometryFactory;

    public ExportGeometryNormalizer(GeometryMapper geometryMapper) {
        this.geometryMapper = geometryMapper;
        this.geometryFactory = geometryMapper.getGeometryFactory();
    }

    public Solution normalize(Solution source, ProblemData data) {
        Solution result = new Solution(source.getVariantId());
        for (NewSegment segment : source.getSegments()) {
            LineString geometry = normalize(segment.getMetricGeometry());
            result.addSegment(new NewSegment(segment.getId(), segment.getVariantId(),
                    segment.getStartNodeId(), segment.getEndNodeId(), geometry,
                    segment.getFlowTph(), segment.getDiameter(), segment.getLengthMeters(),
                    segment.getLayingMethod(), segment.getCost()));
        }
        for (ChamberOutput chamber : source.getChambers()) {
            result.addChamber(new ChamberOutput(chamber.getId(), chamber.getVariantId(),
                    normalize(chamber.getMetricPoint()), chamber.getDiameter(), chamber.getCost()));
        }
        for (TechnicalNodeOutput node : source.getTechnicalNodes()) {
            result.addTechnicalNode(new TechnicalNodeOutput(node.getId(), node.getVariantId(),
                    normalize(node.getMetricPoint())));
        }
        for (int index = 0; index < source.getExistingChamberTieInCount(); index++) {
            result.addExistingChamberTieIn(EXISTING_CHAMBER_TIE_IN_COST);
        }
        Map<String, InputFeature> points = data.getConnectionPoints().stream()
                .collect(Collectors.toMap(InputFeature::getId, Function.identity()));
        for (String id : source.getUnconnectedConnectionPointIds()) {
            InputFeature point = points.get(id);
            double flow = point == null ? 0.0 : point.getDouble("flow_tph", 0.0);
            result.addUnconnected(id, 100_000_000.0 + 500_000.0 * Math.max(0.0, flow),
                    "preserved through export normalization");
        }
        source.getDiagnostics().forEach(result::addDiagnostic);
        result.setFullConnectivityStatus(source.getFullConnectivityStatus());
        result.addDiagnostic("post_export_geometry_normalized=true");
        return result;
    }

    private LineString normalize(LineString line) {
        Coordinate[] coordinates = line.getCoordinates();
        for (int index = 0; index < coordinates.length; index++) {
            coordinates[index] = normalize(coordinates[index]);
        }
        return geometryFactory.createLineString(coordinates);
    }

    private Point normalize(Point point) {
        return geometryFactory.createPoint(normalize(point.getCoordinate()));
    }

    private Coordinate normalize(Coordinate metric) {
        Coordinate lonLat = geometryMapper.toLonLat(metric);
        Coordinate rounded = new Coordinate(round(lonLat.x), round(lonLat.y));
        return new MetricProjector().toMetric(rounded);
    }

    private double round(double value) {
        return Math.round(value * COORDINATE_SCALE) / COORDINATE_SCALE;
    }
}
