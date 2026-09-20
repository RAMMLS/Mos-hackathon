package ru.moshackathon.heatnetwork.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@RestController
@RequestMapping("/api/dataset")
public class DatasetController {
    private static final MediaType GEO_JSON = MediaType.valueOf("application/geo+json");

    private final ObjectMapper objectMapper;
    private final Path dataRoot;

    public DatasetController(
            ObjectMapper objectMapper,
            @Value("${heat-network.dataset-root:data}") String datasetRoot) {
        this.objectMapper = objectMapper;
        this.dataRoot = Paths.get(datasetRoot).toAbsolutePath().normalize();
    }

    @GetMapping("/catalog")
    public JsonNode catalog() {
        return readJson(dataRoot.resolve("rl_large/catalog.json"));
    }

    @GetMapping("/scenes/{sceneId:.+}")
    public ResponseEntity<JsonNode> scene(@PathVariable String sceneId) {
        JsonNode catalog = catalog();
        Map<String, String> paths = new HashMap<>();
        Iterator<JsonNode> records = catalog.path("records").elements();
        while (records.hasNext()) {
            JsonNode record = records.next();
            paths.put(record.path("scene_id").asText(), record.path("input").asText());
        }
        String input = paths.get(sceneId);
        if (input == null) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown dataset scene: " + sceneId);
        }
        Path relative = Paths.get(input.replace('\\', '/'));
        if (relative.getNameCount() > 0 && "data".equals(relative.getName(0).toString())) {
            relative = relative.subpath(1, relative.getNameCount());
        }
        Path scenePath = dataRoot.resolve(relative).normalize();
        if (!scenePath.startsWith(dataRoot)) {
            throw new ResponseStatusException(NOT_FOUND, "Invalid dataset scene path");
        }
        return ResponseEntity.ok().contentType(GEO_JSON).body(readJson(scenePath));
    }

    private JsonNode readJson(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Dataset file is not available: " + path);
        }
        try {
            return objectMapper.readTree(path.toFile());
        } catch (IOException error) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Cannot read dataset file: " + path, error);
        }
    }
}
