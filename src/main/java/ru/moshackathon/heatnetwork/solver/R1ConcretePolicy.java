package ru.moshackathon.heatnetwork.solver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

@Component
public class R1ConcretePolicy {
    private static final int STATE_DIM = 6;
    private static final int ACTION_DIM = 8;
    private final double[][] interaction;
    private final double[] actionBias;
    private final String weightsHash;
    private final String loadError;

    public R1ConcretePolicy() {
        String path = System.getenv().getOrDefault("R1_MODEL", "models/r1_v2/model.json");
        double[][] matrix = null;
        double[] bias = null;
        String hash = null;
        String error = null;
        try {
            JsonNode root = new ObjectMapper().readTree(new File(path));
            JsonNode manifest = root.path("manifest");
            if (!"r1_concrete_features_v1".equals(manifest.path("feature_schema_version").asText())
                    || !"r1_concrete_actions_v1".equals(manifest.path("action_schema_version").asText())
                    || manifest.path("state_dim").asInt() != STATE_DIM
                    || manifest.path("action_dim").asInt() != ACTION_DIM) {
                throw new IllegalArgumentException("R1_MODEL_SCHEMA_MISMATCH");
            }
            matrix = readMatrix(root.path("weights").path("interaction"));
            bias = readVector(root.path("weights").path("action_bias"), ACTION_DIM);
            hash = manifest.path("weights_hash").asText();
        } catch (IOException | IllegalArgumentException exception) {
            error = exception.getMessage();
        }
        interaction = matrix;
        actionBias = bias;
        weightsHash = hash;
        loadError = error;
    }

    public int choose(double[] state, double[][] features, boolean[] mask) {
        if (interaction == null || actionBias == null) {
            throw new AlgorithmUnavailableException("MODEL_UNAVAILABLE", loadError);
        }
        int selected = -1;
        double best = -Double.MAX_VALUE;
        for (int action = 0; action < features.length; action++) {
            if (!mask[action]) {
                continue;
            }
            double logit = 0.0;
            for (int feature = 0; feature < ACTION_DIM; feature++) {
                logit += features[action][feature] * actionBias[feature];
                for (int stateIndex = 0; stateIndex < STATE_DIM; stateIndex++) {
                    logit += state[stateIndex] * interaction[stateIndex][feature] * features[action][feature];
                }
            }
            if (selected < 0 || logit > best) {
                selected = action;
                best = logit;
            }
        }
        if (selected < 0) {
            throw new IllegalArgumentException("NO_LEGAL_R1_ACTION");
        }
        return selected;
    }

    public String getWeightsHash() {
        return weightsHash;
    }

    public boolean isAvailable() {
        return interaction != null && actionBias != null;
    }

    public String getLoadError() {
        return loadError;
    }

    private static double[] readVector(JsonNode node, int size) {
        if (!node.isArray() || node.size() != size) {
            throw new IllegalArgumentException("R1_MODEL_SCHEMA_MISMATCH");
        }
        double[] values = new double[size];
        for (int index = 0; index < size; index++) {
            values[index] = node.get(index).asDouble();
        }
        return values;
    }

    private static double[][] readMatrix(JsonNode node) {
        if (!node.isArray() || node.size() != STATE_DIM) {
            throw new IllegalArgumentException("R1_MODEL_SCHEMA_MISMATCH");
        }
        double[][] result = new double[STATE_DIM][];
        for (int row = 0; row < STATE_DIM; row++) {
            result[row] = readVector(node.get(row), ACTION_DIM);
        }
        return result;
    }
}
