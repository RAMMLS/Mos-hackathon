package ru.moshackathon.heatnetwork.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.moshackathon.heatnetwork.model.Solution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoJsonWriterCoverageTest {
    @Test
    void writesExplicitPartialCoverageIntoVariantSummary() throws Exception {
        Solution solution = new Solution("variant");
        solution.addUnconnected("cp-2", 100.0, "test");
        solution.setOutcome(4, true);

        String geoJson = new GeoJsonWriter(
                new GeometryMapper(new MetricProjector())).write(solution);
        JsonNode properties = new ObjectMapper().readTree(geoJson)
                .path("features").get(0).path("properties");

        assertEquals("PARTIAL", properties.path("solution_status").asText());
        assertTrue(properties.path("certified").asBoolean());
        assertFalse(properties.path("complete").asBoolean());
        assertEquals(3, properties.path("connected_oks_count").asInt());
        assertEquals(4, properties.path("total_oks_count").asInt());
        assertEquals(75.0, properties.path("coverage_percent").asDouble());
    }
}
