package ru.moshackathon.heatnetwork.geo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.*;

import java.io.IOException;
import java.io.OutputStream;

@Component
public class GeoJsonWriter {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeometryMapper geometryMapper;

    public GeoJsonWriter(GeometryMapper geometryMapper) {
        this.geometryMapper = geometryMapper;
    }

    public String write(Solution solution) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        ArrayNode features = root.putArray("features");

        for (NewSegment segment : solution.getSegments()) {
            features.add(lineFeature(segment));
        }
        for (ChamberOutput chamber : solution.getChambers()) {
            features.add(chamberFeature(chamber));
        }
        for (TechnicalNodeOutput technicalNode : solution.getTechnicalNodes()) {
            features.add(technicalNodeFeature(technicalNode));
        }
        features.add(summaryFeature(solution));
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    public void write(Solution solution, OutputStream output) throws IOException {
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(output)) {
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            generator.writeStartObject();
            generator.writeStringField("type", "FeatureCollection");
            generator.writeArrayFieldStart("features");
            for (NewSegment segment : solution.getSegments()) {
                generator.writeTree(lineFeature(segment));
            }
            for (ChamberOutput chamber : solution.getChambers()) {
                generator.writeTree(chamberFeature(chamber));
            }
            for (TechnicalNodeOutput technicalNode : solution.getTechnicalNodes()) {
                generator.writeTree(technicalNodeFeature(technicalNode));
            }
            generator.writeTree(summaryFeature(solution));
            generator.writeEndArray();
            generator.writeEndObject();
        }
    }

    private ObjectNode lineFeature(NewSegment segment) {
        ObjectNode feature = baseFeature();
        feature.set("geometry", lineGeometry(segment.getMetricGeometry()));
        ObjectNode props = feature.putObject("properties");
        props.put("id", segment.getId());
        props.put("object_type", "heat_network");
        props.put("variant_id", segment.getVariantId());
        props.put("start_node_id", segment.getStartNodeId());
        props.put("end_node_id", segment.getEndNodeId());
        props.put("flow_tph", round(segment.getFlowTph()));
        props.put("diameter", segment.getDiameter());
        props.put("length", round(segment.getLengthMeters()));
        props.put("laying_method", segment.getLayingMethod());
        props.putNull("depth_start");
        props.putNull("depth_end");
        props.put("cost", round(segment.getCost()));
        return feature;
    }

    private ObjectNode chamberFeature(ChamberOutput chamber) {
        ObjectNode feature = baseFeature();
        feature.set("geometry", pointGeometry(chamber.getMetricPoint()));
        ObjectNode props = feature.putObject("properties");
        props.put("id", chamber.getId());
        props.put("object_type", "heat_chamber");
        props.put("variant_id", chamber.getVariantId());
        props.put("diameter", chamber.getDiameter());
        props.put("cost", round(chamber.getCost()));
        return feature;
    }

    private ObjectNode technicalNodeFeature(TechnicalNodeOutput technicalNode) {
        ObjectNode feature = baseFeature();
        feature.set("geometry", pointGeometry(technicalNode.getMetricPoint()));
        ObjectNode props = feature.putObject("properties");
        props.put("id", technicalNode.getId());
        props.put("object_type", "technical_node");
        props.put("variant_id", technicalNode.getVariantId());
        return feature;
    }

    private ObjectNode summaryFeature(Solution solution) {
        ObjectNode feature = baseFeature();
        feature.putNull("geometry");
        ObjectNode props = feature.putObject("properties");
        props.put("id", solution.getVariantId() + "_summary");
        props.put("object_type", "variant_summary");
        props.put("variant_id", solution.getVariantId());
        props.put("rank", 1);
        props.put("construction_cost", round(solution.getConstructionCost()));
        props.put("chamber_construction_cost", round(solution.getChamberConstructionCost()));
        props.put("existing_chamber_tie_in_count", solution.getExistingChamberTieInCount());
        props.put("existing_chamber_tie_in_cost", round(solution.getExistingChamberTieInCost()));
        props.put("unconnected_penalty", round(solution.getUnconnectedPenalty()));
        props.put("calculated_cost", round(solution.getCalculatedCost()));
        props.put("new_network_length", round(solution.getNewNetworkLength()));
        props.put("length", round(solution.getLength()));
        props.put("score", round(solution.getScore()));
        props.put("solution_status", solution.getSolutionStatus());
        props.put("certified", solution.isCertified());
        props.put("complete", solution.isComplete());
        props.put("connected_oks_count", solution.getConnectedConnectionPointCount());
        props.put("total_oks_count", solution.getTotalConnectionPointCount());
        if (Double.isFinite(solution.getCoveragePercent())) {
            props.put("coverage_percent", round(solution.getCoveragePercent()));
        } else {
            props.putNull("coverage_percent");
        }
        ArrayNode ids = props.putArray("unconnected_oks_ids");
        for (String id : solution.getUnconnectedConnectionPointIds()) {
            ids.add(id);
        }
        ArrayNode diagnostics = props.putArray("diagnostics");
        for (String diagnostic : solution.getDiagnostics()) {
            diagnostics.add(diagnostic);
        }
        return feature;
    }

    private ObjectNode baseFeature() {
        ObjectNode feature = objectMapper.createObjectNode();
        feature.put("type", "Feature");
        return feature;
    }

    private ObjectNode lineGeometry(LineString metricLine) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "LineString");
        ArrayNode coords = geometry.putArray("coordinates");
        for (Coordinate metric : metricLine.getCoordinates()) {
            Coordinate lonLat = geometryMapper.toLonLat(metric);
            coords.add(coord(lonLat));
        }
        return geometry;
    }

    private ObjectNode pointGeometry(Point point) {
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "Point");
        geometry.set("coordinates", coord(geometryMapper.toLonLat(point.getCoordinate())));
        return geometry;
    }

    private ArrayNode coord(Coordinate lonLat) {
        ArrayNode node = objectMapper.createArrayNode();
        node.add(roundCoordinate(lonLat.x));
        node.add(roundCoordinate(lonLat.y));
        return node;
    }

    private double roundCoordinate(double value) {
        return Math.round(value * 1_000_000_000.0) / 1_000_000_000.0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
