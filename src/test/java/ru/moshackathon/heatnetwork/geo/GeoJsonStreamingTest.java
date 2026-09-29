package ru.moshackathon.heatnetwork.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Solution;
import ru.moshackathon.heatnetwork.model.TechnicalNodeOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoJsonStreamingTest {
    private final GeometryMapper geometryMapper = new GeometryMapper(new MetricProjector());

    @Test
    void readerStreamsFeaturesEvenWhenTypeComesAfterArray() throws IOException {
        String geoJson = "{\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\","
                + "\"coordinates\":[37.6,55.7]},\"properties\":{\"id\":\"cp-1\","
                + "\"object_type\":\"oks_connection_point\",\"flow_tph\":1}}],"
                + "\"type\":\"FeatureCollection\"}";

        ProblemData data = new GeoJsonReader(geometryMapper).read(
                new ByteArrayInputStream(geoJson.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, data.getConnectionPoints().size());
        assertEquals("cp-1", data.getConnectionPoints().get(0).getId());
    }

    @Test
    void writerStreamsValidGeoJsonWithoutClosingServletOutput() throws IOException {
        GeoJsonWriter writer = new GeoJsonWriter(geometryMapper);
        TrackingOutputStream output = new TrackingOutputStream();

        writer.write(new Solution("variant_stream"), output);

        assertFalse(output.closed);
        JsonNode root = new ObjectMapper().readTree(output.toByteArray());
        assertEquals("FeatureCollection", root.path("type").asText());
        assertEquals("variant_stream_summary",
                root.path("features").get(0).path("properties").path("id").asText());
    }

    @Test
    void writerPreservesSubCentimeterEndpointPrecision() throws IOException {
        double longitude = 30.3619591;
        double latitude = 60.076681449999995;
        Solution solution = new Solution("variant_precision");
        solution.addTechnicalNode(new TechnicalNodeOutput("node", "variant_precision",
                new GeometryFactory().createPoint(new MetricProjector().toMetric(
                        new org.locationtech.jts.geom.Coordinate(longitude, latitude)))));

        JsonNode root = new ObjectMapper().readTree(new GeoJsonWriter(geometryMapper).write(solution));
        JsonNode coordinates = root.path("features").get(0).path("geometry").path("coordinates");

        assertTrue(Math.abs(coordinates.get(0).asDouble() - longitude) < 1e-8);
        assertTrue(Math.abs(coordinates.get(1).asDouble() - latitude) < 1e-8);
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
