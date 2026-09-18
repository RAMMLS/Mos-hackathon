package ru.moshackathon.heatnetwork.geo;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class GeometryMapper {
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private final MetricProjector projector;

    public GeometryMapper(MetricProjector projector) {
        this.projector = projector;
    }

    public Geometry read(JsonNode geometryNode, boolean metric) {
        String type = geometryNode.path("type").asText();
        JsonNode coordinates = geometryNode.path("coordinates");
        if ("Point".equals(type)) {
            Coordinate c = coordinate(coordinates, metric);
            return geometryFactory.createPoint(c);
        }
        if ("LineString".equals(type)) {
            return geometryFactory.createLineString(coordinateArray(coordinates, metric));
        }
        if ("Polygon".equals(type)) {
            return polygon(coordinates, metric);
        }
        if ("MultiPolygon".equals(type)) {
            List<Polygon> polygons = new ArrayList<>();
            for (JsonNode polygonNode : coordinates) {
                polygons.add(polygon(polygonNode, metric));
            }
            return geometryFactory.createMultiPolygon(polygons.toArray(new Polygon[0]));
        }
        throw new IllegalArgumentException("Unsupported geometry type: " + type);
    }

    public GeometryFactory getGeometryFactory() {
        return geometryFactory;
    }

    public Coordinate toLonLat(Coordinate metric) {
        return projector.toLonLat(metric);
    }

    private Polygon polygon(JsonNode polygonNode, boolean metric) {
        LinearRing shell = geometryFactory.createLinearRing(coordinateArray(polygonNode.get(0), metric));
        LinearRing[] holes = new LinearRing[Math.max(0, polygonNode.size() - 1)];
        for (int i = 1; i < polygonNode.size(); i++) {
            holes[i - 1] = geometryFactory.createLinearRing(coordinateArray(polygonNode.get(i), metric));
        }
        return geometryFactory.createPolygon(shell, holes);
    }

    private Coordinate[] coordinateArray(JsonNode node, boolean metric) {
        Coordinate[] coordinates = new Coordinate[node.size()];
        for (int i = 0; i < node.size(); i++) {
            coordinates[i] = coordinate(node.get(i), metric);
        }
        return coordinates;
    }

    private Coordinate coordinate(JsonNode node, boolean metric) {
        Coordinate lonLat = new Coordinate(node.get(0).asDouble(), node.get(1).asDouble());
        return metric ? projector.toMetric(lonLat) : lonLat;
    }
}
