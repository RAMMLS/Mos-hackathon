package ru.moshackathon.heatnetwork.solver;

import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.Solution;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

@Component
public class R2Solver {
    public static final String[] ACTIONS = {
            "RANDOM_20+GREEDY", "GEOGRAPHIC_25+GREEDY", "HIGH_FLOW_25+REGRET",
            "BACKBONE_40+JUNCTION", "SUBTREE_25+REGRET", "STOP"
    };

    private final R2Policy policy;

    public R2Solver(R2Policy policy) {
        this.policy = policy;
    }

    public Solution solve(Solution initial, Function<String, Solution> evaluator) {
        return solve(initial, evaluator, () -> true);
    }

    public Solution solve(Solution initial, Function<String, Solution> evaluator,
                          BooleanSupplier canContinue) {
        double[] values = policy.initialValues();
        boolean[] used = new boolean[ACTIONS.length];
        Solution seed = initial.snapshot();
        Solution current = seed.snapshot();
        Solution best = seed.snapshot();
        double normalizer = Math.max(1.0, Math.abs(initial.getScore()));
        List<String> sequence = new ArrayList<>();
        int improvements = 0;
        int failures = 0;
        boolean deadlineReached = false;

        for (int step = 0; step < ACTIONS.length; step++) {
            if (!canContinue.getAsBoolean()) {
                deadlineReached = true;
                break;
            }
            int action = select(values, used);
            used[action] = true;
            String name = ACTIONS[action];
            sequence.add(name);
            if ("STOP".equals(name)) {
                break;
            }
            Solution candidate;
            try {
                candidate = evaluator.apply(name);
            } catch (RuntimeException exception) {
                failures++;
                sequence.add(name + "[FAILED:" + exception.getClass().getSimpleName() + "]");
                current = best.snapshot();
                continue;
            }
            if (!preservesConnectedTargets(current, candidate)) {
                failures++;
                sequence.add(name + "[FAILED:CONNECTED_SET_LOSS]");
                current = best.snapshot();
                continue;
            }
            candidate = candidate.snapshot();
            double reward = Math.max(0.0, best.getScore() - candidate.getScore()) / normalizer;
            values[action] += 0.35 * (reward - values[action]);
            current = candidate;
            if (isBetter(candidate, best)) {
                best = candidate;
                improvements++;
            }
        }
        best.addDiagnostic("algorithm=R2");
        best.addDiagnostic("R2 model_hash=" + policy.getWeightsHash());
        best.addDiagnostic("R2 operator_sequence=" + String.join(",", sequence));
        best.addDiagnostic("R2 improvements=" + improvements + ", failures=" + failures
                + ", final_current_score=" + current.getScore()
                + ", deadline_reached=" + deadlineReached);
        best.addDiagnostic("R2 returns best archive after learned-prior plus online value updates");
        best.addDiagnostic("R2 seed_returned="
                + SolutionConstructionFingerprint.of(best).equals(
                SolutionConstructionFingerprint.of(seed)));
        return best.snapshot();
    }

    private boolean preservesConnectedTargets(Solution before, Solution candidate) {
        if (candidate == null) {
            return false;
        }
        return before.getUnconnectedConnectionPointIds().containsAll(
                candidate.getUnconnectedConnectionPointIds());
    }

    private int select(double[] values, boolean[] used) {
        int selected = -1;
        double best = -Double.MAX_VALUE;
        for (int index = 0; index < values.length; index++) {
            if (!used[index] && (selected < 0 || values[index] > best)) {
                selected = index;
                best = values[index];
            }
        }
        if (selected < 0) {
            throw new IllegalStateException("NO_R2_ACTION");
        }
        return selected;
    }

    private boolean isBetter(Solution candidate, Solution incumbent) {
        int candidateMissing = candidate.getUnconnectedConnectionPointIds().size();
        int incumbentMissing = incumbent.getUnconnectedConnectionPointIds().size();
        if (candidateMissing != incumbentMissing) {
            return candidateMissing < incumbentMissing;
        }
        return candidate.getScore() + 0.000001 < incumbent.getScore();
    }
}
