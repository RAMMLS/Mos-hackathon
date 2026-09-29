package ru.moshackathon.heatnetwork.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.ProblemData;
import ru.moshackathon.heatnetwork.model.Solution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class R1SeedSafetyTest {
    @Test
    void zeroImprovementBudgetReturnsAnIndependentSeedSnapshot() {
        R1ConcretePolicy policy = availablePolicy();
        R1Solver solver = new R1Solver(mock(R1PilotPolicy.class), policy);
        Solution seed = new Solution("seed");

        Solution result = solver.solveConcrete(problemWithTarget("cp-1"), seed,
                environment(Collections.emptyList(), () -> {
                    fail("action must not run after the deadline");
                    return new Solution("unexpected");
                }), "R1", 4, () -> false);

        assertEquals("seed", result.getVariantId());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(item -> item.contains("deadline_reached=true")));
        assertFalse(seed.getDiagnostics().stream().anyMatch(item -> item.startsWith("algorithm=")));
    }

    @Test
    void emptyLegalMaskReturnsSeedWithoutCallingPolicy() {
        R1ConcretePolicy policy = availablePolicy();
        R1Solver solver = new R1Solver(mock(R1PilotPolicy.class), policy);
        R1Solver.Action illegal = new R1Solver.Action(
                "illegal", "MERGE", new double[8], false, null);

        Solution result = solver.solveConcrete(problemWithTarget("cp-1"), new Solution("seed"),
                environment(Collections.singletonList(illegal), () -> new Solution("unexpected")),
                "R1", 1);

        assertEquals("seed", result.getVariantId());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(item -> item.contains("NO_LEGAL_ACTIONS")));
        verify(policy, never()).choose(any(), any(), any());
    }

    @Test
    void actionExceptionRollsBackToSeed() {
        R1ConcretePolicy policy = availablePolicy();
        when(policy.choose(any(), any(), any())).thenReturn(0);
        R1Solver solver = new R1Solver(mock(R1PilotPolicy.class), policy);
        R1Solver.Action action = new R1Solver.Action(
                "explode", "MERGE", new double[8], true, null);

        Solution result = solver.solveConcrete(problemWithTarget("cp-1"), new Solution("seed"),
                new R1Solver.Environment() {
                    @Override
                    public List<R1Solver.Action> actions(int step) {
                        return Collections.singletonList(action);
                    }

                    @Override
                    public Solution apply(R1Solver.Action ignored) {
                        throw new IllegalStateException("synthetic failure");
                    }
                }, "R1", 1);

        assertEquals("seed", result.getVariantId());
        assertTrue(result.getDiagnostics().stream().anyMatch(item -> item.contains("failures=1")));
    }

    @Test
    void candidateCannotLoseAnAlreadyConnectedTarget() {
        R1ConcretePolicy policy = availablePolicy();
        when(policy.choose(any(), any(), any())).thenReturn(0);
        R1Solver solver = new R1Solver(mock(R1PilotPolicy.class), policy);
        R1Solver.Action action = new R1Solver.Action(
                "lose-target", "MERGE", new double[8], true, null);
        ProblemData data = problemWithTarget("cp-1");

        Solution result = solver.solveConcrete(data, new Solution("seed"),
                new R1Solver.Environment() {
                    @Override
                    public List<R1Solver.Action> actions(int step) {
                        return Collections.singletonList(action);
                    }

                    @Override
                    public Solution apply(R1Solver.Action ignored) {
                        Solution candidate = new Solution("candidate");
                        candidate.addUnconnected("cp-1", 10.0, "synthetic loss");
                        return candidate;
                    }
                }, "R1", 1);

        assertEquals("seed", result.getVariantId());
        assertTrue(result.getUnconnectedConnectionPointIds().isEmpty());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(item -> item.contains("CONNECTED_SET_LOSS")));
    }

    private R1ConcretePolicy availablePolicy() {
        R1ConcretePolicy policy = mock(R1ConcretePolicy.class);
        when(policy.isAvailable()).thenReturn(true);
        when(policy.getWeightsHash()).thenReturn("test-model");
        return policy;
    }

    private R1Solver.Environment environment(List<R1Solver.Action> actions,
                                             java.util.function.Supplier<Solution> apply) {
        return new R1Solver.Environment() {
            @Override
            public List<R1Solver.Action> actions(int step) {
                return actions;
            }

            @Override
            public Solution apply(R1Solver.Action ignored) {
                return apply.get();
            }
        };
    }

    private ProblemData problemWithTarget(String id) {
        GeometryFactory factory = new GeometryFactory();
        Point point = factory.createPoint(new org.locationtech.jts.geom.Coordinate(0, 0));
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", id);
        properties.put("object_type", "oks_connection_point");
        properties.put("flow_tph", 1.0);
        InputFeature target = new InputFeature(
                id, "oks_connection_point", properties, point, point);
        return new ProblemData(Collections.singletonList(target), Collections.emptyList());
    }
}
