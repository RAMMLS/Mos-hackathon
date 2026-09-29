package ru.moshackathon.heatnetwork.solver;

import org.junit.jupiter.api.Test;
import ru.moshackathon.heatnetwork.model.Solution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class R2SolverTest {
    @Test
    void failedOperatorCannotDiscardInitialArchive() {
        R2Policy policy = new R2Policy();
        R2Solver solver = new R2Solver(policy);
        Solution seed = new Solution("verified-seed");

        Solution result = solver.solve(seed, ignored -> {
            throw new IllegalStateException("synthetic failure");
        });

        assertEquals("verified-seed", result.getVariantId());
        assertTrue(result.getDiagnostics().stream().anyMatch(item -> item.contains("failures=5")));
    }

    @Test
    void exhaustedBudgetReturnsInitialArchiveWithoutEvaluatingOperators() {
        R2Solver solver = new R2Solver(new R2Policy());
        Solution seed = new Solution("deadline-seed");

        Solution result = solver.solve(seed, ignored -> {
            fail("operator must not run after the cooperative deadline");
            return null;
        }, () -> false);

        assertEquals("deadline-seed", result.getVariantId());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(item -> item.contains("deadline_reached=true")));
    }

    @Test
    void operatorCannotLosePreviouslyConnectedTarget() {
        R2Solver solver = new R2Solver(new R2Policy());
        Solution seed = new Solution("seed");

        Solution result = solver.solve(seed, ignored -> {
            Solution candidate = new Solution("candidate");
            candidate.addUnconnected("cp-1", 10.0, "synthetic loss");
            return candidate;
        });

        assertEquals("seed", result.getVariantId());
        assertTrue(result.getUnconnectedConnectionPointIds().isEmpty());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(item -> item.contains("CONNECTED_SET_LOSS")));
    }
}
