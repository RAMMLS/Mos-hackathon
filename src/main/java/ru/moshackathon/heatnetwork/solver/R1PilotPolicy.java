package ru.moshackathon.heatnetwork.solver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

@Component
public class R1PilotPolicy {
    private static final String FEATURE_SCHEMA = "r1_pilot_features_v2";
    private static final String ACTION_SCHEMA = "r1_pilot_actions_v2";
    private static final String CANDIDATE_SCHEMA = "r1_pilot_candidates_v2";
    private static final int STATE_DIM = 6;
    private static final int ACTION_DIM = 8;

    private final double[][] interaction;
    private final double[] actionBias;
    private final String weightsHash;
    private final String loadError;

    public R1PilotPolicy() {
        String configured = System.getenv().getOrDefault(
                "R1_PILOT_MODEL", "models/r1_pilot_v1/model.json");
        double[][] loadedInteraction = null;
        double[] loadedActionBias = null;
        String loadedHash = null;
        String error = null;
        try {
            JsonNode root = new ObjectMapper().readTree(new File(configured));
            JsonNode manifest = root.path("manifest");
            if (!FEATURE_SCHEMA.equals(manifest.path("feature_schema_version").asText())
                    || !ACTION_SCHEMA.equals(manifest.path("action_schema_version").asText())
                    || !CANDIDATE_SCHEMA.equals(manifest.path("candidate_space_version").asText())
                    || manifest.path("state_dim").asInt() != STATE_DIM
                    || manifest.path("action_dim").asInt() != ACTION_DIM) {
                throw new IllegalArgumentException("MODEL_SCHEMA_MISMATCH");
            }
            loadedInteraction = matrix(root.path("weights").path("interaction"), STATE_DIM, ACTION_DIM);
            loadedActionBias = vector(root.path("weights").path("action_bias"), ACTION_DIM);
            loadedHash = manifest.path("weights_hash").asText();
        } catch (IOException | IllegalArgumentException exception) {
            error = exception.getMessage();
        }
        this.interaction = loadedInteraction;
        this.actionBias = loadedActionBias;
        this.weightsHash = loadedHash;
        this.loadError = error;
    }

    public boolean isAvailable() {
        return interaction != null && actionBias != null;
    }

    public String getWeightsHash() {
        return weightsHash;
    }

    public String getLoadError() {
        return loadError;
    }

    public int choose(double[] state, double[][] actionFeatures, boolean[] mask) {
        double[] logits = logits(state, actionFeatures);
        if (logits.length != mask.length) {
            throw new IllegalArgumentException("R1_PILOT_INPUT_SHAPE_MISMATCH");
        }
        int selected = -1;
        double bestLogit = -Double.MAX_VALUE;
        for (int action = 0; action < logits.length; action++) {
            if (mask[action] && (selected < 0 || logits[action] > bestLogit)) {
                selected = action;
                bestLogit = logits[action];
            }
        }
        if (selected < 0) {
            throw new IllegalArgumentException("NO_LEGAL_R1_PILOT_ACTION");
        }
        return selected;
    }

    public double[] logits(double[] state, double[][] actionFeatures) {
        if (!isAvailable()) {
            throw new AlgorithmUnavailableException("MODEL_UNAVAILABLE", loadError);
        }
        if (state.length != STATE_DIM) {
            throw new IllegalArgumentException("R1_PILOT_INPUT_SHAPE_MISMATCH");
        }
        double[] result = new double[actionFeatures.length];
        for (int action = 0; action < actionFeatures.length; action++) {
            if (actionFeatures[action].length != ACTION_DIM) {
                throw new IllegalArgumentException("R1_PILOT_ACTION_SHAPE_MISMATCH");
            }
            double logit = 0.0;
            for (int feature = 0; feature < ACTION_DIM; feature++) {
                logit += actionFeatures[action][feature] * actionBias[feature];
                for (int stateIndex = 0; stateIndex < STATE_DIM; stateIndex++) {
                    logit += state[stateIndex] * interaction[stateIndex][feature]
                            * actionFeatures[action][feature];
                }
            }
            result[action] = logit;
        }
        return result;
    }

    private static double[] vector(JsonNode node, int size) {
        if (!node.isArray() || node.size() != size) {
            throw new IllegalArgumentException("MODEL_SCHEMA_MISMATCH");
        }
        double[] result = new double[size];
        for (int i = 0; i < size; i++) {
            result[i] = node.get(i).asDouble();
        }
        return result;
    }

    private static double[][] matrix(JsonNode node, int rows, int columns) {
        if (!node.isArray() || node.size() != rows) {
            throw new IllegalArgumentException("MODEL_SCHEMA_MISMATCH");
        }
        double[][] result = new double[rows][];
        for (int i = 0; i < rows; i++) {
            result[i] = vector(node.get(i), columns);
        }
        return result;
    }
}
