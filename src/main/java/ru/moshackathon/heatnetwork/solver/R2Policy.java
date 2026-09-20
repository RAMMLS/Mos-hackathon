package ru.moshackathon.heatnetwork.solver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

@Component
public class R2Policy {
    private static final String SCHEMA = "r2_operator_policy_v1";
    private static final int ACTION_COUNT = 6;

    private final double[] priors;
    private final String weightsHash;
    private final String loadError;

    public R2Policy() {
        String configured = System.getenv().getOrDefault("R2_MODEL", "models/r2_v1/model.json");
        double[] loaded = null;
        String hash = null;
        String error = null;
        try {
            JsonNode root = new ObjectMapper().readTree(new File(configured));
            JsonNode manifest = root.path("manifest");
            if (!SCHEMA.equals(manifest.path("schema_version").asText())
                    || manifest.path("action_count").asInt() != ACTION_COUNT) {
                throw new IllegalArgumentException("R2_MODEL_SCHEMA_MISMATCH");
            }
            JsonNode values = root.path("weights").path("operator_priors");
            if (!values.isArray() || values.size() != ACTION_COUNT) {
                throw new IllegalArgumentException("R2_MODEL_SCHEMA_MISMATCH");
            }
            loaded = new double[ACTION_COUNT];
            for (int index = 0; index < ACTION_COUNT; index++) {
                loaded[index] = values.get(index).asDouble();
            }
            hash = manifest.path("weights_hash").asText();
        } catch (IOException | IllegalArgumentException exception) {
            error = exception.getMessage();
        }
        priors = loaded;
        weightsHash = hash;
        loadError = error;
    }

    public boolean isAvailable() {
        return priors != null;
    }

    public double[] initialValues() {
        if (!isAvailable()) {
            throw new AlgorithmUnavailableException("MODEL_UNAVAILABLE", loadError);
        }
        return priors.clone();
    }

    public String getWeightsHash() {
        return weightsHash;
    }
}
