package ru.moshackathon.heatnetwork.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/visualization")
public class VisualizationController {
    private static final MediaType GEO_JSON = MediaType.valueOf("application/geo+json");

    private final ObjectMapper objectMapper;
    private final String dataRoot;

    public VisualizationController(
            ObjectMapper objectMapper,
            @Value("${visualization.data-root:${VISUALIZATION_DATA_ROOT:data/real_geojson_places}}") String dataRoot
    ) {
        this.objectMapper = objectMapper;
        this.dataRoot = dataRoot;
    }

    @GetMapping(value = "/datasets", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> datasets() throws IOException {
        Path index = root().resolve("index.json").normalize();
        if (!Files.isRegularFile(index)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "GeoJSON index not found at " + index
            );
        }
        return ResponseEntity.ok(objectMapper.readTree(index.toFile()));
    }

    @GetMapping(value = "/datasets/{category}/{fileName:.+}", produces = "application/geo+json")
    public ResponseEntity<String> dataset(
            @PathVariable String category,
            @PathVariable String fileName
    ) throws IOException {
        if (!category.matches("[a-z_]+") || !fileName.matches("[A-Za-z0-9_+\\-.]+\\.geojson")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dataset path");
        }

        Path root = root();
        Path target = root.resolve(category).resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset path escapes data root");
        }
        if (!Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found: " + fileName);
        }

        return ResponseEntity.ok()
                .contentType(GEO_JSON)
                .body(Files.readString(target, StandardCharsets.UTF_8));
    }

    private Path root() {
        return Paths.get(dataRoot).toAbsolutePath().normalize();
    }
}
