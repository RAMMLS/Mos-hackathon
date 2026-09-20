package ru.moshackathon.heatnetwork.solver;

import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Solution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class R1Solver {
    public static final String[] ACTION_NAMES = {
            "B2-U", "B2-Q", "B2-C", "B2-C-J", "B3", "STOP"
    };
    private static final int HORIZON = ACTION_NAMES.length;

    private final R1PilotPolicy policy;
    private final R1ConcretePolicy concretePolicy;

    public R1Solver(R1PilotPolicy policy, R1ConcretePolicy concretePolicy) {
        this.policy = policy;
        this.concretePolicy = concretePolicy;
    }

    public Solution solveConcrete(ProblemData data, Solution initial, Environment environment,
                                  String algorithmLabel, int horizon) {
        if (!concretePolicy.isAvailable()) {
            throw new AlgorithmUnavailableException("MODEL_UNAVAILABLE", concretePolicy.getLoadError());
        }
        Solution current = initial;
        Solution best = initial;
        List<String> sequence = new ArrayList<>();
        int evaluated = 0;
        int improvements = 0;
        int failures = 0;
        double totalFlow = data.getConnectionPoints().stream()
                .mapToDouble(point -> point.getDouble("flow_tph", 0)).sum();
        double normalizer = Math.max(1.0, Math.abs(initial.getScore()));
        double rewardSum = 0.0;

        for (int step = 0; step < horizon; step++) {
            List<Action> actions = environment.actions(step);
            if (actions.isEmpty()) {
                break;
            }
            double[] state = state(data, totalFlow, current, best, step, horizon);
            double[][] features = actions.stream().map(Action::getFeatures).toArray(double[][]::new);
            boolean[] mask = new boolean[actions.size()];
            for (int index = 0; index < actions.size(); index++) {
                mask[index] = actions.get(index).isLegal();
            }
            int selected = concretePolicy.choose(state, features, mask);
            Action action = actions.get(selected);
            sequence.add(action.getActionId());
            if (action.isStop()) {
                break;
            }
            double bestBefore = best.getScore();
            try {
                current = environment.apply(action);
                evaluated++;
                if (isBetter(current, best)) {
                    best = current;
                    improvements++;
                }
                rewardSum += Math.max(0.0, bestBefore - best.getScore()) / normalizer;
            } catch (RuntimeException exception) {
                failures++;
                environment.rollback(action);
                current = best;
            }
        }
        best.addDiagnostic("algorithm=" + algorithmLabel);
        best.addDiagnostic(algorithmLabel + " model_hash=" + concretePolicy.getWeightsHash());
        best.addDiagnostic(algorithmLabel + " concrete_action_sequence=" + String.join(" | ", sequence));
        best.addDiagnostic(algorithmLabel + " concrete episode evaluated=" + evaluated
                + ", improvements=" + improvements + ", failures=" + failures
                + ", cumulative_archive_reward=" + roundToSixDecimals(rewardSum));
        return best;
    }

    public Solution solve(ProblemData data, Solution initial,
                          Function<String, Solution> proposalEvaluator, String algorithmLabel) {
        if (!policy.isAvailable()) {
            throw new AlgorithmUnavailableException("MODEL_UNAVAILABLE", policy.getLoadError());
        }
        Solution current = initial;
        Solution best = initial;
        boolean[] mask = {true, true, true, true, true, true};
        List<String> sequence = new ArrayList<>();
        double[] initialLogits = null;
        double[] initialState = null;
        int policyCalls = 0;
        int evaluatedProposals = 0;
        int failedProposals = 0;
        int improvingProposals = 0;
        double cumulativeReward = 0.0;
        double totalFlow = data.getConnectionPoints().stream()
                .mapToDouble(point -> point.getDouble("flow_tph", 0)).sum();

        for (int step = 0; step < HORIZON; step++) {
            double[] state = state(data, totalFlow, current, best, step);
            double[][] actionFeatures = actionFeatures(data.getConnectionPoints().size(), totalFlow);
            if (step == 0) {
                initialState = state.clone();
                initialLogits = policy.logits(state, actionFeatures);
            }
            int action = policy.choose(state, actionFeatures, mask);
            policyCalls++;
            String actionName = ACTION_NAMES[action];
            sequence.add(actionName);
            if ("STOP".equals(actionName)) {
                break;
            }
            mask[action] = false;
            double bestBefore = best.getScore();
            try {
                current = proposalEvaluator.apply(actionName);
                evaluatedProposals++;
                if (isBetter(current, best)) {
                    best = current;
                    improvingProposals++;
                }
                cumulativeReward += Math.max(0.0, bestBefore - best.getScore())
                        / Math.max(1.0, Math.abs(initial.getScore()));
            } catch (RuntimeException exception) {
                failedProposals++;
                current = best;
            }
        }

        best.addDiagnostic("algorithm=" + algorithmLabel);
        best.addDiagnostic(algorithmLabel + " model_hash=" + policy.getWeightsHash());
        best.addDiagnostic(algorithmLabel + " action_sequence=" + String.join(",", sequence));
        best.addDiagnostic(algorithmLabel + " initial_state=" + join(initialState));
        best.addDiagnostic(algorithmLabel + " initial_logits=" + join(initialLogits));
        best.addDiagnostic(algorithmLabel + " episode policy_calls=" + policyCalls
                + ", evaluated_proposals=" + evaluatedProposals
                + ", improving_proposals=" + improvingProposals
                + ", failed_proposals=" + failedProposals
                + ", cumulative_archive_reward=" + roundToSixDecimals(cumulativeReward));
        best.addDiagnostic(algorithmLabel
                + " returns the best independently checkable proposal from its bounded RL episode");
        return best;
    }

    private double[] state(ProblemData data, double totalFlow, Solution current, Solution best, int step) {
        return state(data, totalFlow, current, best, step, HORIZON);
    }

    private double[] state(ProblemData data, double totalFlow, Solution current, Solution best,
                           int step, int horizon) {
        return new double[]{
                Math.min(data.getConnectionPoints().size() / 24.0, 2.0),
                Math.min(totalFlow / 1000.0, 2.0),
                roundToCents(current.getScore()) / 50.0,
                roundToCents(best.getScore()) / 50.0,
                step / (double) horizon,
                (horizon - step) / (double) horizon,
        };
    }

    private double[][] actionFeatures(int oksCount, double totalFlow) {
        double[][] result = new double[ACTION_NAMES.length][ACTION_NAMES.length + 2];
        double normalizedOks = Math.min(oksCount / 24.0, 2.0);
        double normalizedFlow = Math.min(totalFlow / 1000.0, 2.0);
        for (int action = 0; action < ACTION_NAMES.length - 1; action++) {
            result[action][action] = 1.0;
            result[action][ACTION_NAMES.length] = normalizedOks;
            result[action][ACTION_NAMES.length + 1] = normalizedFlow;
        }
        result[ACTION_NAMES.length - 1][ACTION_NAMES.length - 1] = 1.0;
        return result;
    }

    private boolean isBetter(Solution candidate, Solution incumbent) {
        int candidateUnconnected = candidate.getUnconnectedConnectionPointIds().size();
        int incumbentUnconnected = incumbent.getUnconnectedConnectionPointIds().size();
        if (candidateUnconnected != incumbentUnconnected) {
            return candidateUnconnected < incumbentUnconnected;
        }
        if (Math.abs(candidate.getScore() - incumbent.getScore()) > 0.000001) {
            return candidate.getScore() < incumbent.getScore();
        }
        return candidate.getCalculatedCost() < incumbent.getCalculatedCost();
    }

    private double roundToCents(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double roundToSixDecimals(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private String join(double[] values) {
        return Arrays.stream(values).mapToObj(Double::toString).collect(Collectors.joining(","));
    }

    public interface Environment {
        List<Action> actions(int step);

        Solution apply(Action action);

        default void rollback(Action action) {
        }
    }

    public static final class Action {
        private final String actionId;
        private final String actionType;
        private final double[] features;
        private final boolean legal;
        private final Object payload;

        public Action(String actionId, String actionType, double[] features, boolean legal, Object payload) {
            this.actionId = actionId;
            this.actionType = actionType;
            this.features = features.clone();
            this.legal = legal;
            this.payload = payload;
        }

        public String getActionId() {
            return actionId;
        }

        public String getActionType() {
            return actionType;
        }

        public double[] getFeatures() {
            return features.clone();
        }

        public boolean isLegal() {
            return legal;
        }

        public boolean isStop() {
            return "STOP".equals(actionType);
        }

        public Object getPayload() {
            return payload;
        }
    }
}
