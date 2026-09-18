package ru.moshackathon.heatnetwork.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.ProblemData;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Component
public class GeoJsonReader {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeometryMapper geometryMapper;

    public GeoJsonReader(GeometryMapper geometryMapper) {
        this.geometryMapper = geometryMapper;
    }

    public ProblemData read(InputStream input) throws IOException {
        JsonNode root = objectMapper.readTree(input);
        if (!"FeatureCollection".equals(root.path("type").asText())) {
            throw new IllegalArgumentException("Input must be GeoJSON FeatureCollection");
        }
        List<InputFeature> features = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode featureNode : root.path("features")) {
            InputFeature feature = readFeature(featureNode);
            if (!ids.add(feature.getId())) {
                diagnostics.add("duplicate id: " + feature.getId());
            }
            if (!feature.getMetricGeometry().isValid()) {
                diagnostics.add("invalid geometry: " + feature.getId());
            }
            features.add(feature);
        }
        validateCurrentContract(features, diagnostics);
        if (diagnostics.stream().anyMatch(s -> s.startsWith("invalid geometry") || s.startsWith("missing"))) {
            throw new IllegalArgumentException(String.join("; ", diagnostics));
        }
        return new ProblemData(features, diagnostics);
    }

    private InputFeature readFeature(JsonNode featureNode) {
        JsonNode propertiesNode = featureNode.path("properties");
        Map<String, Object> properties = objectMapper.convertValue(propertiesNode, LinkedHashMap.class);
        Object rawId = properties.get("id");
        String id = rawId == null ? UUID.randomUUID().toString() : String.valueOf(rawId);
        String objectType = String.valueOf(properties.getOrDefault("object_type", ""));
        Geometry lonLat = geometryMapper.read(featureNode.path("geometry"), false);
        Geometry metric = geometryMapper.read(featureNode.path("geometry"), true);
        return new InputFeature(id, objectType, properties, lonLat, metric);
    }

    private void validateCurrentContract(List<InputFeature> features, List<String> diagnostics) {
        for (InputFeature feature : features) {
            if (feature.getObjectType() == null || feature.getObjectType().isEmpty() || "null".equals(feature.getObjectType())) {
                diagnostics.add("missing object_type for feature " + feature.getId());
            }
            if ("oks_connection_point".equals(feature.getObjectType())
                    && !feature.getProperties().containsKey("flow_tph")) {
                diagnostics.add("missing flow_tph for connection point " + feature.getId());
            }
            if ("heat_network".equals(feature.getObjectType())
                    && !feature.getProperties().containsKey("diameter")) {
                diagnostics.add("missing diameter for heat network " + feature.getId());
            }
            if ("restriction".equals(feature.getObjectType())
                    && !feature.getProperties().containsKey("restriction_type")) {
                diagnostics.add("missing restriction_type for restriction " + feature.getId());
            }
        }
    }
}
