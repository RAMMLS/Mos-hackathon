package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.*;

import java.util.*;

@Component
public class BaselineSolver {
    private static final String VARIANT_ID = "variant_1";
    private static final double EXISTING_CHAMBER_TIE_IN_COST = 5_000_000.0;
    private static final int EXISTING_CANDIDATE_LIMIT = 5;
    private static final int PLANNED_PARENT_LIMIT = 3;
    private static final int JUNCTION_EDGE_LIMIT = 5;
    private static final double JUNCTION_ENDPOINT_MARGIN_METERS = 2.0;
    private static final int MAX_CHAMBER_DEGREE = 4;
    private static final int X0_MAX_CONNECTION_POINTS = 6;
    private static final int X2_MAX_JUNCTION_CANDIDATES = 28;
    private static final int X2_MAX_ROOT_CANDIDATES = 12;
    private static final String FORCED_EXISTING_PARENT = "__EXISTING__";
    private static final double SCORE_COST_WEIGHT = 0.7;
    private static final double SCORE_COST_SCALE = 25_000_000.0;
    private static final double SCORE_LENGTH_WEIGHT = 0.3;
    private static final double SCORE_LENGTH_SCALE = 100.0;

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final TieInFinder tieInFinder;
    private final RoutePlanner routePlanner;
    private final DiameterCatalog diameterCatalog;
    private final R1Solver r1Solver;
    private final R2Solver r2Solver;

    public BaselineSolver(TieInFinder tieInFinder, RoutePlanner routePlanner, DiameterCatalog diameterCatalog,
                          R1Solver r1Solver, R2Solver r2Solver) {
        this.tieInFinder = tieInFinder;
        this.routePlanner = routePlanner;
        this.diameterCatalog = diameterCatalog;
        this.r1Solver = r1Solver;
        this.r2Solver = r2Solver;
    }

    public Solution solve(ProblemData data) {
        return solve(data, AlgorithmId.PORTFOLIO);
    }

    public Solution solve(ProblemData data, AlgorithmId algorithm) {
        if (!algorithm.isRunnable()) {
            throw new AlgorithmUnavailableException("NOT_TRAINED",
                    algorithm.getExternalName() + " requires a trained model artifact; no model is registered");
        }
        if ((algorithm == AlgorithmId.X0 || algorithm == AlgorithmId.X1 || algorithm == AlgorithmId.X2)
                && data.getConnectionPoints().size() > X0_MAX_CONNECTION_POINTS) {
            throw new AlgorithmUnavailableException("NOT_APPLICABLE",
                    algorithm.getExternalName() + " is limited to " + X0_MAX_CONNECTION_POINTS
                            + " connection points; got "
                            + data.getConnectionPoints().size());
        }

        // Every candidate must satisfy the same endpoint-obstacle rules as the independent checker.
        // Shared-parent algorithms used to relax this and could cut through a concave/multipart OKS
        // footprint on the way to a connection point located inside that footprint.
        RoutePlanner.RoutingContext context = routePlanner.prepare(data, true);
        switch (algorithm) {
            case B0_GRID:
                return solveOrdered(data, context, flowDescending(), false,
                        RoutePlanner.RouteMode.GRID, algorithm.getExternalName());
            case B0_CORRIDOR:
                return solveOrdered(data, context, flowDescending(), false,
                        RoutePlanner.RouteMode.CORRIDOR, algorithm.getExternalName());
            case B1:
                return solveRegret(data, context, algorithm.getExternalName());
            case B2_U:
            case B2_Q:
            case B2_C:
                return solveMedoid(data, context, algorithm);
            case B2_C_J:
                return solveMedoidWithJunctions(data, context);
            case B3: {
                RoutePlanner.RoutingContext relaxedContext = routePlanner.prepare(data, false);
                return solveDestroyRepair(data, relaxedContext, context);
            }
            case R1_PILOT:
                return solveR1(data, context, "R1-PILOT");
            case R1:
                return solveR1Concrete(data, context);
            case R2:
                return solveR2(data, context);
            case X0:
                return solveSmallExactControl(data, context);
            case X1: {
                RoutePlanner.RoutingContext relaxedContext = routePlanner.prepare(data, false);
                Solution initial = solveDestroyRepair(data, relaxedContext, context);
                return solveExactParentTree(data, initial, relaxedContext, context);
            }
            case X2: {
                RoutePlanner.RoutingContext relaxedContext = routePlanner.prepare(data, false);
                Solution initial = solveDestroyRepair(data, relaxedContext, context);
                Solution exactTree = solveExactParentTree(data, initial, relaxedContext, context);
                return solveSteinerGraph(data, context, exactTree);
            }
            case PORTFOLIO:
            default:
                return solveProductionPortfolio(data, context);
        }
    }

    private Solution solveR1(ProblemData data, RoutePlanner.RoutingContext context, String algorithmLabel) {
        RoutePlanner.RoutingContext seedContext = routePlanner.prepare(data, true);
        Solution initial = solveRegret(data, seedContext, algorithmLabel + "/INITIAL_B1");
        return r1Solver.solve(data, initial, actionName -> {
            switch (actionName) {
                case "B2-U":
                    return solveMedoid(data, context, AlgorithmId.B2_U);
                case "B2-Q":
                    return solveMedoid(data, context, AlgorithmId.B2_Q);
                case "B2-C":
                    return solveMedoid(data, context, AlgorithmId.B2_C);
                case "B2-C-J":
                    return solveMedoidWithJunctions(data, context);
                case "B3":
                    return solveDestroyRepair(data);
                default:
                    throw new IllegalStateException("unknown R1 action " + actionName);
            }
        }, algorithmLabel);
    }

    private Solution solveR1Concrete(ProblemData data, RoutePlanner.RoutingContext context) {
        List<InputFeature> initialOrder = new ArrayList<>(data.getConnectionPoints());
        initialOrder.sort(flowDescending());
        ConcretePlan initialPlan = new ConcretePlan(initialOrder, Collections.emptyMap(), false,
                "INITIAL_FLOW_DESC");
        TreeBuild initialBuild = evaluateConcretePlan(data, context, initialPlan, "R1/INITIAL");

        class ConcreteEnvironment implements R1Solver.Environment {
            private ConcretePlan currentPlan = initialPlan;
            private Solution currentSolution = initialBuild.solution;
            private final Set<String> attempted = new HashSet<>();

            @Override
            public List<R1Solver.Action> actions(int step) {
                List<R1Solver.Action> actions = concreteActions(data, currentPlan, attempted, step,
                        currentSolution.getUnconnectedConnectionPointIds());
                actions.add(stopAction(step));
                return actions;
            }

            @Override
            public Solution apply(R1Solver.Action action) {
                ConcretePlan next = (ConcretePlan) action.getPayload();
                attempted.add(next.label);
                TreeBuild build = evaluateConcretePlan(data, context, next,
                        "R1/" + action.getActionType());
                if (!build.solution.getUnconnectedConnectionPointIds().isEmpty()) {
                    throw new IllegalStateException("R1_ACTION_INCOMPLETE");
                }
                currentPlan = next;
                currentSolution = build.solution;
                build.solution.addDiagnostic("R1 applied concrete action=" + action.getActionId());
                return build.solution;
            }
        }

        return r1Solver.solveConcrete(data, initialBuild.solution, new ConcreteEnvironment(), "R1", 32);
    }

    private Solution solveR2(ProblemData data, RoutePlanner.RoutingContext context) {
        Solution initial = solveDestroyRepair(data);
        return solveR2(data, context, initial);
    }

    private Solution solveR2(ProblemData data, RoutePlanner.RoutingContext context, Solution initial) {
        initial.addDiagnostic("R2 initial archive=B3 guarded exact/heuristic search");
        return r2Solver.solve(initial, action -> "SUBTREE_25+REGRET".equals(action)
                ? initial
                : solveR2Operator(data, context, action));
    }

    private Solution solveR2Operator(ProblemData data, RoutePlanner.RoutingContext context, String action) {
        List<InputFeature> order = new ArrayList<>(data.getConnectionPoints());
        switch (action) {
            case "RANDOM_20+GREEDY":
                order.sort(flowDescending());
                Collections.shuffle(order.subList(0, Math.max(2, order.size() / 5)), new Random(11));
                return solveInOrder(data, context, order, true, RoutePlanner.RouteMode.CORRIDOR,
                        "R2/RANDOM_20+GREEDY");
            case "GEOGRAPHIC_25+GREEDY":
                order.sort(Comparator.comparingDouble(item -> item.getMetricGeometry().getCoordinate().x));
                return solveInOrder(data, context, order, true, RoutePlanner.RouteMode.CORRIDOR,
                        "R2/GEOGRAPHIC_25+GREEDY");
            case "HIGH_FLOW_25+REGRET":
                return solveRegret(data, context, "R2/HIGH_FLOW_25+REGRET");
            case "BACKBONE_40+JUNCTION":
                return solveMedoidWithJunctions(data, context);
            case "SUBTREE_25+REGRET":
                return solveDestroyRepair(data);
            default:
                throw new IllegalArgumentException("unknown R2 operator " + action);
        }
    }

    private TreeBuild evaluateConcretePlan(ProblemData data, RoutePlanner.RoutingContext context,
                                           ConcretePlan plan, String label) {
        return buildInOrder(data, context, plan.order, true, RoutePlanner.RouteMode.CORRIDOR,
                label, plan.allowJunctions, plan.forcedParents);
    }

    private List<R1Solver.Action> concreteActions(ProblemData data, ConcretePlan current,
                                                   Set<String> attempted, int step,
                                                   List<String> unconnectedIds) {
        List<R1Solver.Action> result = new ArrayList<>();
        List<InputFeature> points = data.getConnectionPoints();
        for (String unconnectedId : unconnectedIds) {
            InputFeature connection = points.stream().filter(item -> item.getId().equals(unconnectedId))
                    .findFirst().orElse(null);
            if (connection == null) {
                continue;
            }
            List<InputFeature> order = new ArrayList<>(current.order);
            order.remove(connection);
            order.add(0, connection);
            Map<String, String> forced = new LinkedHashMap<>(current.forcedParents);
            forced.remove(connection.getId());
            String id = "CONNECT:oks=" + connection.getId() + ":route=CORRIDOR";
            addConcreteAction(result, attempted, id, "CONNECT", connection, null,
                    new ConcretePlan(order, forced, false, id), step);
        }
        for (AlgorithmId seed : List.of(AlgorithmId.B2_U, AlgorithmId.B2_Q, AlgorithmId.B2_C)) {
            InputFeature medoid = weightedMedoid(points, seed);
            List<List<InputFeature>> orders = List.of(
                    medoidOrder(points, medoid, false),
                    medoidOrder(points, medoid, true),
                    angularOrder(points, medoid));
            String[] modes = {"NEAR", "FAR", "ANGULAR"};
            for (int index = 0; index < orders.size(); index++) {
                String id = "BUILD_BACKBONE:" + seed.getExternalName() + ":" + modes[index];
                addConcreteAction(result, attempted, id, "BUILD_BACKBONE",
                        orders.get(index).get(0), null,
                        new ConcretePlan(orders.get(index), Collections.emptyMap(), false, id), step);
                if (seed == AlgorithmId.B2_C) {
                    String junctionId = "BUILD_BACKBONE:B2-C-J:" + modes[index];
                    addConcreteAction(result, attempted, junctionId, "BUILD_BACKBONE",
                            orders.get(index).get(0), null,
                            new ConcretePlan(orders.get(index), Collections.emptyMap(), true, junctionId), step);
                }
            }
        }

        List<InputFeature> orderedChildren = new ArrayList<>(points);
        orderedChildren.sort(Comparator.comparing(InputFeature::getId));
        int attachQuota = 0;
        int mergeQuota = 0;
        for (InputFeature child : orderedChildren) {
            List<InputFeature> parents = new ArrayList<>(points);
            parents.remove(child);
            parents.sort(Comparator.comparingDouble(parent -> parent.getMetricGeometry().getCoordinate()
                    .distance(child.getMetricGeometry().getCoordinate())));
            for (int index = 0; index < Math.min(3, parents.size()); index++) {
                InputFeature parent = parents.get(index);
                Map<String, String> forced = new LinkedHashMap<>(current.forcedParents);
                String type = forced.containsKey(child.getId()) ? "REATTACH" : "ATTACH";
                forced.put(child.getId(), parent.getId());
                List<InputFeature> order = parentBeforeChild(current.order, parent, child);
                String id = type + ":oks=" + child.getId() + ":target_node=" + parent.getId()
                        + ":route=CORRIDOR";
                if (attachQuota < 8) {
                    addConcreteAction(result, attempted, id, type, child, parent,
                            new ConcretePlan(order, forced, false, id), step);
                    attachQuota++;
                }

                String mergeId = "MERGE:group=" + child.getId() + "+" + parent.getId()
                        + ":junction=PROJECTED_EDGE";
                if (mergeQuota < 8) {
                    addConcreteAction(result, attempted, mergeId, "MERGE", child, parent,
                            new ConcretePlan(order, current.forcedParents, true, mergeId), step);
                    mergeQuota++;
                }
            }
            if (attachQuota >= 8 && mergeQuota >= 8) {
                break;
            }
        }
        return result.stream().limit(127).collect(java.util.stream.Collectors.toList());
    }

    private void addConcreteAction(List<R1Solver.Action> actions, Set<String> attempted,
                                   String id, String type, InputFeature child, InputFeature parent,
                                   ConcretePlan payload, int step) {
        String stateId = "s" + step + ":" + id;
        if (attempted.contains(id)) {
            return;
        }
        double flow = child == null ? 0.0 : child.getDouble("flow_tph", 0.0);
        double distance = child == null || parent == null ? 0.0
                : child.getMetricGeometry().getCoordinate().distance(parent.getMetricGeometry().getCoordinate());
        actions.add(new R1Solver.Action(stateId, type,
                actionFeatures(type, flow, distance), true, payload));
    }

    private R1Solver.Action stopAction(int step) {
        return new R1Solver.Action("s" + step + ":STOP", "STOP",
                actionFeatures("STOP", 0, 0), true, null);
    }

    private double[] actionFeatures(String type, double flow, double distance) {
        String[] types = {"CONNECT", "ATTACH", "MERGE", "BUILD_BACKBONE", "REATTACH", "STOP"};
        double[] features = new double[8];
        for (int index = 0; index < types.length; index++) {
            if (types[index].equals(type)) {
                features[index] = 1.0;
            }
        }
        features[6] = Math.min(flow / 1000.0, 2.0);
        features[7] = Math.min(distance / 1000.0, 2.0);
        return features;
    }

    private List<InputFeature> parentBeforeChild(List<InputFeature> source,
                                                  InputFeature parent, InputFeature child) {
        List<InputFeature> result = new ArrayList<>(source);
        result.remove(parent);
        result.remove(child);
        result.add(0, parent);
        result.add(1, child);
        return result;
    }

    private Solution solveProductionPortfolio(ProblemData data, RoutePlanner.RoutingContext context) {
        RoutePlanner.RoutingContext relaxedContext = routePlanner.prepare(data, false);
        Solution initial = solveDestroyRepair(data, relaxedContext, context);
        Solution best = solveR2(data, context, initial);
        if (data.getConnectionPoints().size() <= X0_MAX_CONNECTION_POINTS) {
            best = solveExactParentTree(data, best, relaxedContext, context);
            best = solveSteinerGraph(data, context, best);
        }
        best.addDiagnostic("PORTFOLIO selected guarded B3 + learned R2 + exact X1 + Steiner X2 cascade");
        best.addDiagnostic("PORTFOLIO exact parent-tree oracle="
                + (data.getConnectionPoints().size() <= X0_MAX_CONNECTION_POINTS));
        return best;
    }

    private Solution solveOrdered(ProblemData data, RoutePlanner.RoutingContext context,
                                  Comparator<InputFeature> comparator, boolean allowSharedParents,
                                  RoutePlanner.RouteMode routeMode, String algorithmLabel) {
        List<InputFeature> ordered = new ArrayList<>(data.getConnectionPoints());
        ordered.sort(comparator);
        return solveInOrder(data, context, ordered, allowSharedParents, routeMode, algorithmLabel);
    }

    private Solution solveInOrder(ProblemData data, RoutePlanner.RoutingContext context,
                                  List<InputFeature> ordered, boolean allowSharedParents,
                                  RoutePlanner.RouteMode routeMode, String algorithmLabel) {
        return solveInOrder(data, context, ordered, allowSharedParents, routeMode, algorithmLabel, false);
    }

    private Solution solveInOrder(ProblemData data, RoutePlanner.RoutingContext context,
                                  List<InputFeature> ordered, boolean allowSharedParents,
                                  RoutePlanner.RouteMode routeMode, String algorithmLabel,
                                  boolean allowJunctions) {
        return buildInOrder(data, context, ordered, allowSharedParents, routeMode,
                algorithmLabel, allowJunctions).solution;
    }

    private TreeBuild buildInOrder(ProblemData data, RoutePlanner.RoutingContext context,
                                   List<InputFeature> ordered, boolean allowSharedParents,
                                   RoutePlanner.RouteMode routeMode, String algorithmLabel,
                                   boolean allowJunctions) {
        return buildInOrder(data, context, ordered, allowSharedParents, routeMode,
                algorithmLabel, allowJunctions, Collections.emptyMap());
    }

    private TreeBuild buildInOrder(ProblemData data, RoutePlanner.RoutingContext context,
                                   List<InputFeature> ordered, boolean allowSharedParents,
                                   RoutePlanner.RouteMode routeMode, String algorithmLabel,
                                   boolean allowJunctions, Map<String, String> forcedParents) {
        Solution solution = newSolution(data, algorithmLabel);
        List<TreeEdge> edges = new ArrayList<>();
        List<InputFeature> connected = new ArrayList<>();
        JunctionStats junctionStats = new JunctionStats();
        for (InputFeature connection : ordered) {
            connectGreedy(connection, data, context, solution, connected, edges,
                    allowSharedParents, routeMode, allowJunctions, junctionStats, forcedParents);
        }
        emitTree(edges, solution);
        if (allowJunctions) {
            solution.addDiagnostic(junctionStats.describe());
        }
        return new TreeBuild(new ArrayList<>(ordered), edges, solution);
    }

    private Solution solveRegret(ProblemData data, RoutePlanner.RoutingContext context, String algorithmLabel) {
        Solution solution = newSolution(data, algorithmLabel);
        solution.addDiagnostic("B1-lite: regret-2 insertion into existing chambers and built connection nodes");
        List<InputFeature> remaining = new ArrayList<>(data.getConnectionPoints());
        List<InputFeature> connected = new ArrayList<>();
        List<TreeEdge> edges = new ArrayList<>();
        while (!remaining.isEmpty()) {
            InsertionChoice choice = chooseNextInsertion(remaining, data, context, connected, edges);
            remaining.remove(choice.connection);
            if (choice.edge == null) {
                double flow = choice.connection.getDouble("flow_tph", 0);
                solution.addUnconnected(choice.connection.getId(), penalty(flow), choice.failureReason);
            } else {
                edges.add(choice.edge);
                connected.add(choice.connection);
            }
        }
        emitTree(edges, solution);
        return solution;
    }

    private Solution solveMedoid(ProblemData data, RoutePlanner.RoutingContext context, AlgorithmId algorithm) {
        List<InputFeature> points = new ArrayList<>(data.getConnectionPoints());
        InputFeature medoid = weightedMedoid(points, algorithm);
        if (medoid == null) {
            return newSolution(data, algorithm.getExternalName());
        }

        List<List<InputFeature>> orders = List.of(
                medoidOrder(points, medoid, false),
                medoidOrder(points, medoid, true),
                angularOrder(points, medoid));
        String[] names = {"near_first", "far_first", "angular"};
        Solution best = null;
        String selected = null;
        for (int i = 0; i < orders.size(); i++) {
            Solution candidate = solveInOrder(data, context, orders.get(i), true,
                    RoutePlanner.RouteMode.CORRIDOR, algorithm.getExternalName() + "/" + names[i]);
            if (isBetter(candidate, best)) {
                best = candidate;
                selected = names[i];
            }
        }
        best.addDiagnostic(algorithm.getExternalName() + " medoid=" + medoid.getId()
                + ", selected backbone seed=" + selected);
        best.addDiagnostic("B2-lite: medoid/order seeds implemented; virtual junctions inside pipes are not generated");
        return best;
    }

    private Solution solveMedoidWithJunctions(ProblemData data, RoutePlanner.RoutingContext context) {
        Solution frozenControl = solveMedoid(data, context, AlgorithmId.B2_C);
        List<InputFeature> points = new ArrayList<>(data.getConnectionPoints());
        InputFeature medoid = weightedMedoid(points, AlgorithmId.B2_C);
        if (medoid == null) {
            return frozenControl;
        }
        List<List<InputFeature>> orders = List.of(
                medoidOrder(points, medoid, false),
                medoidOrder(points, medoid, true),
                angularOrder(points, medoid));
        String[] names = {"near_first", "far_first", "angular"};
        Solution bestJunction = null;
        String selected = null;
        for (int i = 0; i < orders.size(); i++) {
            Solution candidate = solveInOrder(data, context, orders.get(i), true,
                    RoutePlanner.RouteMode.CORRIDOR, "B2-C-J/" + names[i], true);
            if (isBetter(candidate, bestJunction)) {
                bestJunction = candidate;
                selected = names[i];
            }
        }
        if (isBetter(bestJunction, frozenControl)) {
            bestJunction.addDiagnostic("B2-C-J selected junction-enabled order=" + selected);
            bestJunction.addDiagnostic("B2-C-J improved over frozen B2-C control");
            return bestJunction;
        }
        frozenControl.addDiagnostic("algorithm=B2-C-J");
        frozenControl.addDiagnostic("B2-C-J kept frozen B2-C control; junction candidates did not improve it");
        return frozenControl;
    }

    private Solution solveDestroyRepair(ProblemData data) {
        RoutePlanner.RoutingContext searchContext = routePlanner.prepare(data, false);
        RoutePlanner.RoutingContext strictContext = routePlanner.prepare(data, true);
        return solveDestroyRepair(data, searchContext, strictContext);
    }

    private Solution solveDestroyRepair(ProblemData data,
                                        RoutePlanner.RoutingContext searchContext,
                                        RoutePlanner.RoutingContext strictContext) {
        List<List<InputFeature>> archive = new ArrayList<>();
        for (OrderingStrategy strategy : standardStrategies()) {
            List<InputFeature> order = new ArrayList<>(data.getConnectionPoints());
            order.sort(strategy.comparator);
            archive.add(order);
        }
        for (AlgorithmId seed : List.of(AlgorithmId.B2_U, AlgorithmId.B2_Q, AlgorithmId.B2_C)) {
            InputFeature medoid = weightedMedoid(data.getConnectionPoints(), seed);
            archive.add(medoidOrder(data.getConnectionPoints(), medoid, false));
            archive.add(medoidOrder(data.getConnectionPoints(), medoid, true));
            archive.add(angularOrder(data.getConnectionPoints(), medoid));
        }

        List<InputFeature> base = new ArrayList<>(data.getConnectionPoints());
        base.sort(flowDescending());
        for (long seed : new long[]{11, 23, 37, 53, 71}) {
            List<InputFeature> destroyed = new ArrayList<>(base);
            Collections.shuffle(destroyed, new Random(seed));
            archive.add(destroyed);
        }
        for (int start = 0; start < Math.min(base.size(), 10); start++) {
            List<InputFeature> repaired = new ArrayList<>(base);
            int end = Math.min(repaired.size(), start + Math.max(2, repaired.size() / 4));
            Collections.reverse(repaired.subList(start, end));
            archive.add(repaired);
        }
        boolean exactOrderSearch = base.size() <= X0_MAX_CONNECTION_POINTS;
        if (exactOrderSearch) {
            List<List<InputFeature>> permutations = new ArrayList<>();
            permute(new ArrayList<>(base), 0, permutations);
            archive.addAll(permutations);
        }

        Solution best = solveRegret(data, strictContext, "B3/strict_regret_fallback");
        String selected = "strict_regret_fallback";
        int rejectedUnsafe = 0;
        Solution relaxedRegret = solveRegret(data, searchContext, "B3/regret_repair");
        if (isGeometrySafe(relaxedRegret, strictContext)) {
            if (isBetter(relaxedRegret, best)) {
                best = relaxedRegret;
                selected = "regret_repair";
            }
        } else {
            rejectedUnsafe++;
        }
        int index = 0;
        for (List<InputFeature> order : archive) {
            Solution candidate = solveInOrder(data, searchContext, order, true,
                    RoutePlanner.RouteMode.CORRIDOR, "B3/destroy_repair_" + index);
            if (isGeometrySafe(candidate, strictContext)) {
                if (isBetter(candidate, best)) {
                    best = candidate;
                    selected = "destroy_repair_" + index;
                }
            } else {
                rejectedUnsafe++;
                Solution repaired = solveInOrder(data, strictContext, order, true,
                        RoutePlanner.RouteMode.CORRIDOR, "B3/strict_repair_" + index);
                if (isBetter(repaired, best)) {
                    best = repaired;
                    selected = "strict_repair_" + index;
                }
            }
            index++;
        }
        best.addDiagnostic("B3-lite selected=" + selected + " from " + (archive.size() + 1)
                + " deterministic destroy/repair candidates");
        best.addDiagnostic("B3 exact order search=" + exactOrderSearch
                + " (enabled for at most " + X0_MAX_CONNECTION_POINTS + " connection points)");
        best.addDiagnostic("B3 geometry guard rejected=" + rejectedUnsafe
                + " unsafe relaxed candidates; strict fallback remained available");
        best.addDiagnostic("adaptive operator weights and edge-level subtree removal remain research extensions");
        return best;
    }

    private boolean isGeometrySafe(Solution solution, RoutePlanner.RoutingContext strictContext) {
        return solution.getSegments().stream()
                .allMatch(segment -> routePlanner.avoidsForbiddenRestrictions(
                        segment.getMetricGeometry(), strictContext));
    }

    private Solution solveSmallExactControl(ProblemData data, RoutePlanner.RoutingContext context) {
        List<InputFeature> points = new ArrayList<>(data.getConnectionPoints());
        List<List<InputFeature>> permutations = new ArrayList<>();
        permute(points, 0, permutations);
        Solution best = null;
        int bestIndex = -1;
        for (int i = 0; i < permutations.size(); i++) {
            Solution candidate = solveInOrder(data, context, permutations.get(i), true,
                    RoutePlanner.RouteMode.CORRIDOR, "X0/permutation_" + i);
            if (isBetter(candidate, best)) {
                best = candidate;
                bestIndex = i;
            }
        }
        best.addDiagnostic("X0 limited exact control selected permutation=" + bestIndex
                + " from " + permutations.size());
        best.addDiagnostic("exactness is limited to all insertion orders over the current route shortlist");
        return best;
    }

    private Solution solveExactParentTree(ProblemData data) {
        RoutePlanner.RoutingContext relaxedContext = routePlanner.prepare(data, false);
        RoutePlanner.RoutingContext strictContext = routePlanner.prepare(data, true);
        Solution initial = solveDestroyRepair(data, relaxedContext, strictContext);
        return solveExactParentTree(data, initial, relaxedContext, strictContext);
    }

    private Solution solveExactParentTree(ProblemData data, Solution initial) {
        RoutePlanner.RoutingContext relaxedContext = routePlanner.prepare(data, false);
        RoutePlanner.RoutingContext strictContext = routePlanner.prepare(data, true);
        return solveExactParentTree(data, initial, relaxedContext, strictContext);
    }

    private Solution solveExactParentTree(ProblemData data, Solution initial,
                                          RoutePlanner.RoutingContext relaxedContext,
                                          RoutePlanner.RoutingContext strictContext) {
        if (data.getConnectionPoints().isEmpty()) {
            initial.addDiagnostic("X1 exact rooted parent-tree oracle skipped: no connection points");
            return initial;
        }
        ExactParentTreeSearch search = new ExactParentTreeSearch(
                data, initial, relaxedContext, strictContext);
        search.run();
        return search.best;
    }

    private Solution solveSteinerGraph(ProblemData data, RoutePlanner.RoutingContext context, Solution initial) {
        if (data.getConnectionPoints().isEmpty()) {
            initial.addDiagnostic("X2 Steiner graph skipped: no connection points");
            return initial;
        }
        SteinerGraphSearch search = new SteinerGraphSearch(data, context, initial);
        search.run();
        return search.best;
    }

    private void permute(List<InputFeature> values, int index, List<List<InputFeature>> output) {
        if (index == values.size()) {
            output.add(new ArrayList<>(values));
            return;
        }
        for (int i = index; i < values.size(); i++) {
            Collections.swap(values, index, i);
            permute(values, index + 1, output);
            Collections.swap(values, index, i);
        }
    }

    private Solution newSolution(ProblemData data, String algorithmLabel) {
        Solution solution = new Solution(VARIANT_ID);
        data.getDiagnostics().forEach(solution::addDiagnostic);
        solution.addDiagnostic("algorithm=" + algorithmLabel);
        solution.addDiagnostic("updated TZ: no tie_in output; connection is represented by heat_chamber");
        solution.addDiagnostic("updated TZ: economic refusal disabled; unconnected only if no route is found");
        return solution;
    }

    private List<OrderingStrategy> standardStrategies() {
        return List.of(
                new OrderingStrategy("flow_desc", flowDescending()),
                new OrderingStrategy("flow_asc", Comparator
                        .comparingDouble((InputFeature feature) -> feature.getDouble("flow_tph", 0))
                        .thenComparing(InputFeature::getId)),
                new OrderingStrategy("west_to_east", Comparator
                        .comparingDouble((InputFeature feature) -> feature.getMetricGeometry().getCoordinate().x)
                        .thenComparingDouble(feature -> feature.getMetricGeometry().getCoordinate().y)
                        .thenComparing(InputFeature::getId)),
                new OrderingStrategy("east_to_west", Comparator
                        .comparingDouble((InputFeature feature) -> feature.getMetricGeometry().getCoordinate().x).reversed()
                        .thenComparingDouble(feature -> feature.getMetricGeometry().getCoordinate().y)
                        .thenComparing(InputFeature::getId)));
    }

    private Comparator<InputFeature> flowDescending() {
        return Comparator.comparingDouble((InputFeature feature) -> feature.getDouble("flow_tph", 0)).reversed()
                .thenComparing(InputFeature::getId);
    }

    private InputFeature weightedMedoid(List<InputFeature> points, AlgorithmId algorithm) {
        InputFeature best = null;
        double bestValue = Double.MAX_VALUE;
        for (InputFeature candidate : points) {
            double value = 0.0;
            Coordinate center = candidate.getMetricGeometry().getCoordinate();
            for (InputFeature point : points) {
                value += medoidWeight(point, algorithm)
                        * center.distance(point.getMetricGeometry().getCoordinate());
            }
            if (value < bestValue || (Math.abs(value - bestValue) < 0.001
                    && (best == null || candidate.getId().compareTo(best.getId()) < 0))) {
                best = candidate;
                bestValue = value;
            }
        }
        return best;
    }

    private double medoidWeight(InputFeature point, AlgorithmId algorithm) {
        double flow = Math.max(0.001, point.getDouble("flow_tph", 0.001));
        if (algorithm == AlgorithmId.B2_Q) {
            return flow;
        }
        if (algorithm == AlgorithmId.B2_C) {
            return diameterCatalog.select(flow, 100.0).getNewConstructionRubPerMeter();
        }
        return 1.0;
    }

    private List<InputFeature> medoidOrder(List<InputFeature> input, InputFeature medoid, boolean reversed) {
        List<InputFeature> result = new ArrayList<>(input);
        Coordinate center = medoid.getMetricGeometry().getCoordinate();
        Comparator<InputFeature> comparator = Comparator
                .comparingDouble((InputFeature point) -> center.distance(point.getMetricGeometry().getCoordinate()))
                .thenComparing(InputFeature::getId);
        if (reversed) {
            comparator = comparator.reversed();
        }
        result.sort(comparator);
        result.remove(medoid);
        result.add(0, medoid);
        return result;
    }

    private List<InputFeature> angularOrder(List<InputFeature> input, InputFeature medoid) {
        List<InputFeature> result = new ArrayList<>(input);
        Coordinate center = medoid.getMetricGeometry().getCoordinate();
        result.sort(Comparator.comparingDouble((InputFeature point) -> {
                    Coordinate coordinate = point.getMetricGeometry().getCoordinate();
                    return Math.atan2(coordinate.y - center.y, coordinate.x - center.x);
                }).thenComparing(InputFeature::getId));
        result.remove(medoid);
        result.add(0, medoid);
        return result;
    }

    private boolean isBetter(Solution candidate, Solution incumbent) {
        if (incumbent == null) {
            return true;
        }
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

    private void connectGreedy(InputFeature connection, ProblemData data,
                               RoutePlanner.RoutingContext context, Solution solution,
                               List<InputFeature> connected, List<TreeEdge> edges,
                               boolean allowSharedParents, RoutePlanner.RouteMode routeMode,
                               boolean allowJunctions, JunctionStats junctionStats,
                               Map<String, String> forcedParents) {
        double flow = connection.getDouble("flow_tph", Double.NaN);
        if (Double.isNaN(flow) || flow <= 0) {
            solution.addUnconnected(connection.getId(), penalty(0), "missing or non-positive flow_tph");
            return;
        }

        Coordinate start = connection.getMetricGeometry().getCoordinate();
        TreeEdge best = null;
        double bestScore = Double.MAX_VALUE;
        String forcedParentId = forcedParents.get(connection.getId());
        boolean forceExistingParent = FORCED_EXISTING_PARENT.equals(forcedParentId);
        if (forcedParentId != null && !forceExistingParent) {
            InputFeature parent = connected.stream()
                    .filter(item -> item.getId().equals(forcedParentId))
                    .findFirst().orElse(null);
            if (parent == null || !canAcceptPlannedChild(parent, edges)) {
                solution.addUnconnected(connection.getId(), penalty(flow),
                        "forced parent is unavailable or full: " + forcedParentId);
                return;
            }
            Optional<Route> forcedRoute = routePlanner.plan(start,
                    parent.getMetricGeometry().getCoordinate(), context, routeMode);
            if (!forcedRoute.isPresent()) {
                solution.addUnconnected(connection.getId(), penalty(flow),
                        "no route to forced parent: " + forcedParentId);
                return;
            }
            edges.add(TreeEdge.toConnection(connection, parent, forcedRoute.get()));
            connected.add(connection);
            return;
        }
        List<TieInCandidate> existingCandidates = tieInFinder.find(connection, data);
        int existingLimit = allowSharedParents
                ? Math.min(EXISTING_CANDIDATE_LIMIT, existingCandidates.size())
                : existingCandidates.size();
        for (int i = 0; i < existingLimit; i++) {
            TieInCandidate candidate = existingCandidates.get(i);
            double lowerBound = candidate.getDistanceFromConnection()
                    * diameterCatalog.cheapestNewConstructionRubPerMeter()
                    + candidateTieCostLowerBound(candidate);
            if (best != null && score(lowerBound, candidate.getDistanceFromConnection()) >= bestScore) {
                break;
            }
            Optional<Route> route = routePlanner.plan(start, candidate.getMetricPoint(), context, routeMode);
            if (!route.isPresent()) {
                continue;
            }
            TreeEdge edge = TreeEdge.toExisting(connection, candidate, route.get());
            double candidateScore = score(estimateInitialCost(edge, flow, edges), edge.route.getLengthMeters());
            if (candidateScore < bestScore) {
                best = edge;
                bestScore = candidateScore;
            }
        }

        if (allowSharedParents && !forceExistingParent) {
            List<InputFeature> plannedParents = new ArrayList<>(connected);
            plannedParents.sort(Comparator.comparingDouble(parent ->
                    parent.getMetricGeometry().getCoordinate().distance(start)));
            for (int i = 0; i < Math.min(PLANNED_PARENT_LIMIT, plannedParents.size()); i++) {
                InputFeature parent = plannedParents.get(i);
                if (!canAcceptPlannedChild(parent, edges)) {
                    continue;
                }
                Coordinate parentPoint = parent.getMetricGeometry().getCoordinate();
                if (best != null && score(parentPoint.distance(start)
                        * diameterCatalog.cheapestNewConstructionRubPerMeter(), parentPoint.distance(start)) >= bestScore) {
                    continue;
                }
                Optional<Route> route = routePlanner.plan(start, parentPoint, context, routeMode);
                if (!route.isPresent()) {
                    continue;
                }
                TreeEdge edge = TreeEdge.toConnection(connection, parent, route.get());
                double candidateScore = score(estimateInitialCost(edge, flow, edges), edge.route.getLengthMeters());
                if (candidateScore < bestScore) {
                    best = edge;
                    bestScore = candidateScore;
                }
            }
        }

        if (best == null) {
            solution.addUnconnected(connection.getId(), penalty(flow), "no route to existing or planned network found");
            return;
        }
        if (allowJunctions) {
            JunctionTrial junctionTrial = bestJunctionTree(connection, context, edges, best, routeMode,
                    junctionStats);
            if (junctionTrial != null) {
                edges.clear();
                edges.addAll(junctionTrial.tree);
                connected.add(connection);
                junctionStats.accept(junctionTrial.description);
                return;
            }
        }
        edges.add(best);
        connected.add(connection);
    }

    private JunctionTrial bestJunctionTree(InputFeature connection, RoutePlanner.RoutingContext context,
                                           List<TreeEdge> edges, TreeEdge regular,
                                           RoutePlanner.RouteMode routeMode, JunctionStats stats) {
        if (edges.isEmpty()) {
            return null;
        }
        List<TreeEdge> hosts = new ArrayList<>(edges);
        Coordinate start = connection.getMetricGeometry().getCoordinate();
        Point startPoint = geometryFactory.createPoint(start);
        hosts.sort(Comparator.comparingDouble(edge -> edge.route.getMetricGeometry().distance(startPoint)));
        List<TreeEdge> regularTree = new ArrayList<>(edges);
        regularTree.add(regular);
        double regularScore = score(estimateTreeCost(regularTree), totalLength(regularTree));
        double bestScore = regularScore;
        List<TreeEdge> bestTree = null;
        String bestDescription = null;
        for (int i = 0; i < Math.min(JUNCTION_EDGE_LIMIT, hosts.size()); i++) {
            TreeEdge host = hosts.get(i);
            stats.proposed++;
            if (!"base".equals(host.route.getLayingMethod())) {
                stats.reject("SPECIAL_HOST");
                continue;
            }
            LineString hostLine = host.route.getMetricGeometry();
            LengthIndexedLine indexed = new LengthIndexedLine(hostLine);
            double index = indexed.project(start);
            if (index <= JUNCTION_ENDPOINT_MARGIN_METERS
                    || hostLine.getLength() - index <= JUNCTION_ENDPOINT_MARGIN_METERS) {
                stats.reject("NEAR_ENDPOINT");
                continue;
            }
            Coordinate junction = indexed.extractPoint(index);
            Optional<Route> branch = routePlanner.plan(start, junction, context, routeMode);
            if (!branch.isPresent() || branch.get().getLengthMeters() < 0.25) {
                stats.reject("NO_BRANCH_ROUTE");
                continue;
            }
            List<TreeEdge> trial = splitAndAttach(edges, host, connection, junction, index, branch.get());
            if (trial == null) {
                stats.reject("SPLIT_FAILED");
                continue;
            }
            stats.feasible++;
            double trialScore = score(estimateTreeCost(trial), totalLength(trial));
            if (trialScore + 0.000001 < bestScore) {
                double delta = trialScore - regularScore;
                bestScore = trialScore;
                bestTree = trial;
                bestDescription = "consumer=" + connection.getId()
                        + ", host=" + host.childNodeId + "->" + host.parentNodeId
                        + ", x=" + roundToCents(junction.x) + ", y=" + roundToCents(junction.y)
                        + ", delta_score=" + Math.round(delta * 1_000_000.0) / 1_000_000.0;
            } else {
                stats.reject("NOT_IMPROVING");
            }
        }
        return bestTree == null ? null : new JunctionTrial(bestTree, bestDescription);
    }

    private List<TreeEdge> splitAndAttach(List<TreeEdge> edges, TreeEdge host, InputFeature connection,
                                          Coordinate junction, double index, Route branch) {
        LengthIndexedLine indexed = new LengthIndexedLine(host.route.getMetricGeometry());
        LineString downstream = asLine(indexed.extractLine(0.0, index));
        LineString upstream = asLine(indexed.extractLine(index, host.route.getMetricGeometry().getLength()));
        if (downstream == null || upstream == null
                || downstream.getLength() < 0.25 || upstream.getLength() < 0.25) {
            return null;
        }
        String junctionId = "ch_junction_" + safeId(host.childNodeId) + "_" + safeId(connection.getId());
        InputFeature junctionFeature = syntheticJunction(junctionId, junction);
        List<TreeEdge> trial = new ArrayList<>(edges);
        trial.remove(host);
        trial.add(new TreeEdge(host.child, junctionId, routeSlice(host.route, downstream), null, false));
        trial.add(new TreeEdge(junctionFeature, host.parentNodeId, routeSlice(host.route, upstream),
                host.candidate, true));
        trial.add(new TreeEdge(connection, junctionId, branch, null, false));
        return trial;
    }

    private LineString asLine(org.locationtech.jts.geom.Geometry geometry) {
        return geometry instanceof LineString ? (LineString) geometry : null;
    }

    private Route routeSlice(Route source, LineString geometry) {
        return new Route(geometry, geometry.getLength(), source.getLayingMethod(),
                source.getSpecialCoefficient(), source.getNotes());
    }

    private InputFeature syntheticJunction(String id, Coordinate coordinate) {
        return syntheticJunction(id, coordinate, 0.0);
    }

    private InputFeature syntheticJunction(String id, Coordinate coordinate, double flowTph) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", id);
        properties.put("object_type", "heat_chamber");
        properties.put("flow_tph", flowTph);
        Point point = geometryFactory.createPoint(new Coordinate(coordinate));
        return new InputFeature(id, "heat_chamber", properties, point, point);
    }

    private String safeId(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private double totalLength(List<TreeEdge> edges) {
        return edges.stream().mapToDouble(edge -> edge.route.getLengthMeters()).sum();
    }

    private double roundToCents(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private InsertionChoice chooseNextInsertion(List<InputFeature> remaining, ProblemData data,
                                                RoutePlanner.RoutingContext context,
                                                List<InputFeature> connected, List<TreeEdge> edges) {
        InsertionChoice selected = null;
        for (InputFeature connection : remaining) {
            InsertionChoice candidate = bestInsertion(connection, data, context, connected, edges);
            if (candidate.edge == null && selected != null && selected.edge != null) {
                continue;
            }
            if (selected == null
                    || (candidate.edge != null && selected.edge == null)
                    || candidate.regret > selected.regret
                    || (Math.abs(candidate.regret - selected.regret) < 0.001 && candidate.bestCost < selected.bestCost)
                    || (Math.abs(candidate.regret - selected.regret) < 0.001
                    && Math.abs(candidate.bestCost - selected.bestCost) < 0.001
                    && connection.getId().compareTo(selected.connection.getId()) < 0)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private InsertionChoice bestInsertion(InputFeature connection, ProblemData data,
                                          RoutePlanner.RoutingContext context,
                                          List<InputFeature> connected, List<TreeEdge> edges) {
        double flow = connection.getDouble("flow_tph", Double.NaN);
        if (Double.isNaN(flow) || flow <= 0) {
            return InsertionChoice.failed(connection, "missing or non-positive flow_tph");
        }

        Coordinate start = connection.getMetricGeometry().getCoordinate();
        List<ScoredEdge> scored = new ArrayList<>();
        List<TieInCandidate> existingCandidates = tieInFinder.find(connection, data);
        for (int i = 0; i < Math.min(EXISTING_CANDIDATE_LIMIT, existingCandidates.size()); i++) {
            TieInCandidate candidate = existingCandidates.get(i);
            Optional<Route> route = routePlanner.plan(start, candidate.getMetricPoint(), context,
                    RoutePlanner.RouteMode.CORRIDOR);
            if (route.isPresent()) {
                TreeEdge edge = TreeEdge.toExisting(connection, candidate, route.get());
                scored.add(new ScoredEdge(edge, estimateTreeCostWith(edges, edge)));
            }
        }
        List<InputFeature> plannedParents = new ArrayList<>(connected);
        plannedParents.sort(Comparator.comparingDouble(parent ->
                parent.getMetricGeometry().getCoordinate().distance(start)));
        for (int i = 0; i < Math.min(PLANNED_PARENT_LIMIT, plannedParents.size()); i++) {
            InputFeature parent = plannedParents.get(i);
            if (!canAcceptPlannedChild(parent, edges)) {
                continue;
            }
            Optional<Route> route = routePlanner.plan(start, parent.getMetricGeometry().getCoordinate(), context,
                    RoutePlanner.RouteMode.CORRIDOR);
            if (route.isPresent()) {
                TreeEdge edge = TreeEdge.toConnection(connection, parent, route.get());
                scored.add(new ScoredEdge(edge, estimateTreeCostWith(edges, edge)));
            }
        }

        if (scored.isEmpty()) {
            return InsertionChoice.failed(connection, "no route to existing or planned network found");
        }
        scored.sort(Comparator.comparingDouble((ScoredEdge edge) -> edge.cost)
                .thenComparing(edge -> edge.edge.parentNodeId));
        double bestCost = scored.get(0).cost;
        double secondCost = scored.size() > 1 ? scored.get(1).cost : bestCost + penalty(flow);
        return new InsertionChoice(connection, scored.get(0).edge, bestCost, secondCost - bestCost, null);
    }

    private double candidateTieCostLowerBound(TieInCandidate candidate) {
        return candidate.getHostType() == TieInCandidate.HostType.EXISTING_CHAMBER
                ? EXISTING_CHAMBER_TIE_IN_COST
                : diameterCatalog.chamberCost(candidate.getExistingDiameter());
    }

    private double estimateInitialCost(TreeEdge edge, double flow, List<TreeEdge> existingEdges) {
        DiameterCatalog.Row diameter = diameterCatalog.select(flow, edge.route.getLengthMeters());
        double cost = edge.route.getLengthMeters() * diameter.getNewConstructionRubPerMeter()
                * edge.route.getSpecialCoefficient();
        if (edge.candidate != null) {
            if (edge.candidate.getHostType() == TieInCandidate.HostType.EXISTING_PIPE) {
                cost += diameterCatalog.chamberCost(Math.max(diameter.getDiameter(),
                        edge.candidate.getExistingDiameter()));
            } else {
                cost += EXISTING_CHAMBER_TIE_IN_COST;
            }
            return cost;
        }
        if (existingEdges.stream().noneMatch(existing -> existing.parentNodeId.equals(edge.parentNodeId))) {
            cost += diameterCatalog.chamberCost(diameter.getDiameter());
        }
        return cost;
    }

    private double estimateTreeCostWith(List<TreeEdge> edges, TreeEdge candidate) {
        List<TreeEdge> candidateEdges = new ArrayList<>(edges);
        candidateEdges.add(candidate);
        return estimateTreeCost(candidateEdges);
    }

    private double estimateTreeCost(List<TreeEdge> candidateEdges) {
        Map<String, List<TreeEdge>> childrenByParent = new HashMap<>();
        Map<String, TreeEdge> outgoingByChild = new HashMap<>();
        for (TreeEdge edge : candidateEdges) {
            childrenByParent.computeIfAbsent(edge.parentNodeId, ignored -> new ArrayList<>()).add(edge);
            outgoingByChild.put(edge.childNodeId, edge);
        }
        double cost = 0.0;
        for (TreeEdge edge : candidateEdges) {
            double flow = aggregateFlow(edge, childrenByParent);
            DiameterCatalog.Row diameter = diameterCatalog.select(flow, edge.route.getLengthMeters());
            cost += edge.route.getLengthMeters() * diameter.getNewConstructionRubPerMeter()
                    * edge.route.getSpecialCoefficient();
            if (edge.candidate != null) {
                cost += edge.candidate.getHostType() == TieInCandidate.HostType.EXISTING_PIPE
                        ? diameterCatalog.chamberCost(Math.max(diameter.getDiameter(), edge.candidate.getExistingDiameter()))
                        : EXISTING_CHAMBER_TIE_IN_COST;
            }
        }
        return cost + estimateBranchChamberCost(childrenByParent, outgoingByChild);
    }

    private boolean canAcceptPlannedChild(InputFeature parent, List<TreeEdge> edges) {
        long childBranches = edges.stream().filter(edge -> edge.parentNodeId.equals(parent.getId())).count();
        return childBranches + 1 < MAX_CHAMBER_DEGREE;
    }

    private boolean hasAllowedNodeDegrees(Solution solution) {
        Map<String, Integer> degreeByNode = new HashMap<>();
        for (NewSegment segment : solution.getSegments()) {
            degreeByNode.merge(segment.getStartNodeId(), 1, Integer::sum);
            degreeByNode.merge(segment.getEndNodeId(), 1, Integer::sum);
        }
        return degreeByNode.values().stream().allMatch(degree -> degree <= MAX_CHAMBER_DEGREE);
    }

    private double estimateBranchChamberCost(Map<String, List<TreeEdge>> childrenByParent,
                                             Map<String, TreeEdge> outgoingByChild) {
        double cost = 0.0;
        for (Map.Entry<String, List<TreeEdge>> entry : childrenByParent.entrySet()) {
            if (outgoingByChild.containsKey(entry.getKey())) {
                cost += diameterCatalog.chamberCost(branchChamberDiameter(
                        entry.getKey(), childrenByParent, outgoingByChild));
            }
        }
        return cost;
    }

    private void emitTree(List<TreeEdge> edges, Solution solution) {
        Map<String, List<TreeEdge>> childrenByParent = new HashMap<>();
        Map<String, TreeEdge> outgoingByChild = new HashMap<>();
        for (TreeEdge edge : edges) {
            childrenByParent.computeIfAbsent(edge.parentNodeId, ignored -> new ArrayList<>()).add(edge);
            outgoingByChild.put(edge.childNodeId, edge);
        }
        edges.stream()
                .sorted(Comparator.comparingDouble((TreeEdge edge) -> edge.child.getDouble("flow_tph", 0)).reversed()
                        .thenComparing(edge -> edge.child.getId()))
                .forEach(edge -> emitEdge(edge, aggregateFlow(edge, childrenByParent), solution));
        emitBranchChambers(childrenByParent, outgoingByChild, solution);
    }

    private double aggregateFlow(TreeEdge edge, Map<String, List<TreeEdge>> childrenByParent) {
        double flow = edge.child.getDouble("flow_tph", 0);
        for (TreeEdge childEdge : childrenByParent.getOrDefault(edge.childNodeId, Collections.emptyList())) {
            flow += aggregateFlow(childEdge, childrenByParent);
        }
        return flow;
    }

    private void emitEdge(TreeEdge edge, double flow, Solution solution) {
        DiameterCatalog.Row diameter = diameterCatalog.select(flow, edge.route.getLengthMeters());
        double constructionCost = edge.route.getLengthMeters() * diameter.getNewConstructionRubPerMeter()
                * edge.route.getSpecialCoefficient();
        solution.addSegment(new NewSegment("hn_" + edge.child.getId(), VARIANT_ID,
                edge.childNodeId, edge.parentNodeId, edge.route.getMetricGeometry(), flow,
                diameter.getDiameter(), edge.route.getLengthMeters(), edge.route.getLayingMethod(), constructionCost));
        if (edge.candidate == null) {
            return;
        }
        Point tiePoint = geometryFactory.createPoint(edge.candidate.getMetricPoint());
        if (edge.candidate.getHostType() == TieInCandidate.HostType.EXISTING_PIPE) {
            int chamberDiameter = Math.max(diameter.getDiameter(), edge.candidate.getExistingDiameter());
            solution.addChamber(new ChamberOutput(edge.parentNodeId, VARIANT_ID, tiePoint, chamberDiameter,
                    diameterCatalog.chamberCost(chamberDiameter)));
        } else {
            solution.addExistingChamberTieIn(EXISTING_CHAMBER_TIE_IN_COST);
        }
    }

    private void emitBranchChambers(Map<String, List<TreeEdge>> childrenByParent,
                                    Map<String, TreeEdge> outgoingByChild, Solution solution) {
        for (Map.Entry<String, List<TreeEdge>> entry : childrenByParent.entrySet()) {
            String nodeId = entry.getKey();
            if (!outgoingByChild.containsKey(nodeId)) {
                continue;
            }
            TreeEdge outgoing = outgoingByChild.get(nodeId);
            Point point = geometryFactory.createPoint(outgoing.child.getMetricGeometry().getCoordinate());
            int diameter = branchChamberDiameter(nodeId, childrenByParent, outgoingByChild);
            if (outgoing.syntheticJunction) {
                solution.addTechnicalNode(new TechnicalNodeOutput(nodeId, VARIANT_ID, point));
            }
            String chamberId = "ch_branch_" + nodeId;
            solution.addChamber(new ChamberOutput(chamberId, VARIANT_ID,
                    point, diameter, diameterCatalog.chamberCost(diameter)));
        }
    }

    private int branchChamberDiameter(String nodeId, Map<String, List<TreeEdge>> childrenByParent,
                                      Map<String, TreeEdge> outgoingByChild) {
        int diameter = 0;
        TreeEdge outgoing = outgoingByChild.get(nodeId);
        if (outgoing != null) {
            double flow = aggregateFlow(outgoing, childrenByParent);
            diameter = Math.max(diameter,
                    diameterCatalog.select(flow, outgoing.route.getLengthMeters()).getDiameter());
        }
        for (TreeEdge incoming : childrenByParent.getOrDefault(nodeId, Collections.emptyList())) {
            double flow = aggregateFlow(incoming, childrenByParent);
            diameter = Math.max(diameter,
                    diameterCatalog.select(flow, incoming.route.getLengthMeters()).getDiameter());
        }
        return diameter;
    }

    private double penalty(double flowTph) {
        return 100_000_000.0 + 500_000.0 * Math.max(0, flowTph);
    }

    private double score(double costRub, double lengthMeters) {
        return SCORE_COST_WEIGHT * (costRub / SCORE_COST_SCALE)
                + SCORE_LENGTH_WEIGHT * (lengthMeters / SCORE_LENGTH_SCALE);
    }

    private static class TreeEdge {
        private final InputFeature child;
        private final String childNodeId;
        private final String parentNodeId;
        private final Route route;
        private final TieInCandidate candidate;
        private final boolean syntheticJunction;

        private TreeEdge(InputFeature child, String parentNodeId, Route route, TieInCandidate candidate,
                         boolean syntheticJunction) {
            this.child = child;
            this.childNodeId = child.getId();
            this.parentNodeId = parentNodeId;
            this.route = route;
            this.candidate = candidate;
            this.syntheticJunction = syntheticJunction;
        }

        static TreeEdge toExisting(InputFeature child, TieInCandidate candidate, Route route) {
            String parentNodeId = candidate.getHostType() == TieInCandidate.HostType.EXISTING_CHAMBER
                    ? candidate.getHostId() : "ch_tie_" + child.getId();
            return new TreeEdge(child, parentNodeId, route, candidate, false);
        }

        static TreeEdge toConnection(InputFeature child, InputFeature parent, Route route) {
            return new TreeEdge(child, parent.getId(), route, null, false);
        }
    }

    private static class TreeBuild {
        private final List<InputFeature> order;
        private final List<TreeEdge> edges;
        private final Solution solution;

        private TreeBuild(List<InputFeature> order, List<TreeEdge> edges, Solution solution) {
            this.order = order;
            this.edges = new ArrayList<>(edges);
            this.solution = solution;
        }
    }

    private static class ConcretePlan {
        private final List<InputFeature> order;
        private final Map<String, String> forcedParents;
        private final boolean allowJunctions;
        private final String label;

        private ConcretePlan(List<InputFeature> order, Map<String, String> forcedParents,
                             boolean allowJunctions, String label) {
            this.order = new ArrayList<>(order);
            this.forcedParents = new LinkedHashMap<>(forcedParents);
            this.allowJunctions = allowJunctions;
            this.label = label;
        }
    }

    private final class ExactParentTreeSearch {
        private final ProblemData data;
        private final List<InputFeature> points;
        private final RoutePlanner.RoutingContext relaxedContext;
        private final RoutePlanner.RoutingContext strictContext;
        private final int rootIndex;
        private final int[] pruferSequence;
        private Solution best;
        private long enumerated;
        private long degreeRejected;
        private long relaxedRejected;
        private long strictFeasible;
        private long improvements;

        private ExactParentTreeSearch(ProblemData data, Solution initial,
                                      RoutePlanner.RoutingContext relaxedContext,
                                      RoutePlanner.RoutingContext strictContext) {
            this.data = data;
            this.points = new ArrayList<>(data.getConnectionPoints());
            this.points.sort(Comparator.comparing(InputFeature::getId));
            this.relaxedContext = relaxedContext;
            this.strictContext = strictContext;
            this.rootIndex = points.size();
            this.pruferSequence = new int[Math.max(0, points.size() - 1)];
            this.best = initial;
        }

        private void run() {
            enumeratePrufer(0);
            best.addDiagnostic("X1 exact rooted parent-tree oracle enumerated=" + enumerated
                    + ", degree_rejected=" + degreeRejected
                    + ", relaxed_rejected=" + relaxedRejected
                    + ", strict_feasible=" + strictFeasible
                    + ", improvements=" + improvements);
            best.addDiagnostic("X1 exactness is relative to forced parent trees and the current pairwise route shortlist");
        }

        private void enumeratePrufer(int position) {
            if (position == pruferSequence.length) {
                evaluateCurrentTree();
                return;
            }
            for (int node = 0; node <= rootIndex; node++) {
                pruferSequence[position] = node;
                enumeratePrufer(position + 1);
            }
        }

        private void evaluateCurrentTree() {
            enumerated++;
            int[] parent = orientFromRoot(decodePruferTree());
            if (parent == null || exceedsPointDegree(parent)) {
                degreeRejected++;
                return;
            }

            List<InputFeature> order = parentFirstOrder(parent);
            Map<String, String> forcedParents = forcedParentMap(parent);
            String label = "X1/tree_" + enumerated;
            Solution relaxed = buildInOrder(data, relaxedContext, order, true,
                    RoutePlanner.RouteMode.CORRIDOR, label + "/relaxed", false, forcedParents).solution;
            if (isSafeComplete(relaxed)) {
                consider(relaxed);
                return;
            }

            relaxedRejected++;
            Solution strict = buildInOrder(data, strictContext, order, true,
                    RoutePlanner.RouteMode.CORRIDOR, label + "/strict", false, forcedParents).solution;
            if (isSafeComplete(strict)) {
                strictFeasible++;
                consider(strict);
            }
        }

        private void consider(Solution candidate) {
            if (isBetter(candidate, best)) {
                best = candidate;
                improvements++;
            }
        }

        private boolean isSafeComplete(Solution solution) {
            return solution.getUnconnectedConnectionPointIds().isEmpty()
                    && hasAllowedNodeDegrees(solution)
                    && isGeometrySafe(solution, strictContext);
        }

        private List<int[]> decodePruferTree() {
            int nodeCount = points.size() + 1;
            int[] degree = new int[nodeCount];
            Arrays.fill(degree, 1);
            for (int node : pruferSequence) {
                degree[node]++;
            }
            List<int[]> edges = new ArrayList<>(nodeCount - 1);
            for (int node : pruferSequence) {
                int leaf = firstLeaf(degree);
                edges.add(new int[]{leaf, node});
                degree[leaf]--;
                degree[node]--;
            }
            int first = firstLeaf(degree);
            degree[first]--;
            int second = firstLeaf(degree);
            edges.add(new int[]{first, second});
            return edges;
        }

        private int firstLeaf(int[] degree) {
            for (int node = 0; node < degree.length; node++) {
                if (degree[node] == 1) {
                    return node;
                }
            }
            throw new IllegalStateException("Pruefer decoding produced no leaf");
        }

        private int[] orientFromRoot(List<int[]> edges) {
            List<List<Integer>> adjacency = new ArrayList<>();
            for (int node = 0; node <= rootIndex; node++) {
                adjacency.add(new ArrayList<>());
            }
            for (int[] edge : edges) {
                adjacency.get(edge[0]).add(edge[1]);
                adjacency.get(edge[1]).add(edge[0]);
            }
            adjacency.forEach(Collections::sort);
            int[] parent = new int[points.size()];
            Arrays.fill(parent, Integer.MIN_VALUE);
            boolean[] visited = new boolean[points.size() + 1];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            visited[rootIndex] = true;
            queue.add(rootIndex);
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                for (int next : adjacency.get(current)) {
                    if (visited[next]) {
                        continue;
                    }
                    visited[next] = true;
                    if (next < points.size()) {
                        parent[next] = current;
                    }
                    queue.addLast(next);
                }
            }
            for (int value : parent) {
                if (value == Integer.MIN_VALUE) {
                    return null;
                }
            }
            return parent;
        }

        private boolean exceedsPointDegree(int[] parent) {
            int[] degree = new int[points.size()];
            for (int child = 0; child < parent.length; child++) {
                degree[child]++;
                if (parent[child] < points.size()) {
                    degree[parent[child]]++;
                }
            }
            return Arrays.stream(degree).anyMatch(value -> value > MAX_CHAMBER_DEGREE);
        }

        private List<InputFeature> parentFirstOrder(int[] parent) {
            List<InputFeature> order = new ArrayList<>();
            boolean[] added = new boolean[points.size()];
            while (order.size() < points.size()) {
                boolean progressed = false;
                for (int child = 0; child < points.size(); child++) {
                    if (!added[child] && (parent[child] == rootIndex || added[parent[child]])) {
                        order.add(points.get(child));
                        added[child] = true;
                        progressed = true;
                    }
                }
                if (!progressed) {
                    throw new IllegalStateException("rooted tree order contains a cycle");
                }
            }
            return order;
        }

        private Map<String, String> forcedParentMap(int[] parent) {
            Map<String, String> result = new LinkedHashMap<>();
            for (int child = 0; child < points.size(); child++) {
                result.put(points.get(child).getId(), parent[child] == rootIndex
                        ? FORCED_EXISTING_PARENT
                        : points.get(parent[child]).getId());
            }
            return result;
        }
    }

    private final class SteinerGraphSearch {
        private static final double INFINITY = Double.POSITIVE_INFINITY;
        private final ProblemData data;
        private final RoutePlanner.RoutingContext context;
        private final List<InputFeature> points;
        private final double[] flowByMask;
        private Solution best;
        private List<SteinerNode> nodes;
        private List<TieInCandidate> roots;
        private Route[][] routes;
        private double[][] dp;
        private double[][] rawDp;
        private SteinerDpStep[][] dpSteps;
        private SteinerRawStep[][] rawSteps;
        private int graphEdges;
        private int evaluatedFinals;
        private int feasibleFinals;
        private int improvements;

        private SteinerGraphSearch(ProblemData data, RoutePlanner.RoutingContext context, Solution initial) {
            this.data = data;
            this.context = context;
            this.points = new ArrayList<>(data.getConnectionPoints());
            this.points.sort(Comparator.comparing(InputFeature::getId));
            this.best = initial;
            this.flowByMask = new double[1 << points.size()];
            for (int mask = 1; mask < flowByMask.length; mask++) {
                int bit = Integer.numberOfTrailingZeros(mask);
                flowByMask[mask] = flowByMask[mask & ~(1 << bit)]
                        + points.get(bit).getDouble("flow_tph", 0.0);
            }
        }

        private void run() {
            roots = buildRootCandidates();
            nodes = buildGraphNodes();
            if (roots.isEmpty() || nodes.size() == points.size()) {
                best.addDiagnostic("X2 Steiner graph skipped: no root or free-junction candidates");
                return;
            }
            prepareRoutes();
            runDynamicProgram();
            evaluateFinalConnections();
            best.addDiagnostic("X2 bounded Steiner graph nodes=" + nodes.size()
                    + ", free_junctions=" + (nodes.size() - points.size())
                    + ", graph_edges=" + graphEdges
                    + ", roots=" + roots.size()
                    + ", finals=" + evaluatedFinals
                    + ", feasible=" + feasibleFinals
                    + ", improvements=" + improvements);
            best.addDiagnostic("X2 exhaustively searches bounded subset-DP states; no global optimality certificate");
        }

        private List<TieInCandidate> buildRootCandidates() {
            Coordinate center = centroid(points.stream()
                    .map(item -> item.getMetricGeometry().getCoordinate())
                    .collect(java.util.stream.Collectors.toList()));
            Map<String, TieInCandidate> unique = new LinkedHashMap<>();
            List<InputFeature> probes = new ArrayList<>(points);
            probes.add(syntheticJunction("x2_root_probe", center, flowByMask[flowByMask.length - 1]));
            for (InputFeature probe : probes) {
                for (TieInCandidate candidate : tieInFinder.find(probe, data)) {
                    Coordinate point = candidate.getMetricPoint();
                    String key = candidate.getHostType() + "|" + candidate.getHostId() + "|"
                            + Math.round(point.x * 10.0) + "|" + Math.round(point.y * 10.0);
                    unique.putIfAbsent(key, candidate);
                }
            }
            List<TieInCandidate> result = new ArrayList<>(unique.values());
            result.sort(Comparator.comparingDouble(candidate -> candidate.getMetricPoint().distance(center)));
            if (result.size() > X2_MAX_ROOT_CANDIDATES) {
                return new ArrayList<>(result.subList(0, X2_MAX_ROOT_CANDIDATES));
            }
            return result;
        }

        private List<SteinerNode> buildGraphNodes() {
            List<SteinerNode> result = new ArrayList<>();
            for (InputFeature point : points) {
                result.add(new SteinerNode(point.getId(), point, false));
            }

            List<Coordinate> junctions = new ArrayList<>();
            for (int left = 0; left < points.size(); left++) {
                for (int right = left + 1; right < points.size(); right++) {
                    Coordinate a = points.get(left).getMetricGeometry().getCoordinate();
                    Coordinate b = points.get(right).getMetricGeometry().getCoordinate();
                    TieInCandidate root = nearestRoot(midpoint(a, b));
                    if (root != null) {
                        addJunctionCandidate(junctions, geometricMedian(Arrays.asList(
                                a, b, root.getMetricPoint())));
                    }
                }
            }
            for (int first = 0; first < points.size(); first++) {
                for (int second = first + 1; second < points.size(); second++) {
                    for (int third = second + 1; third < points.size(); third++) {
                        addJunctionCandidate(junctions, geometricMedian(Arrays.asList(
                                points.get(first).getMetricGeometry().getCoordinate(),
                                points.get(second).getMetricGeometry().getCoordinate(),
                                points.get(third).getMetricGeometry().getCoordinate())));
                    }
                }
            }
            for (int left = 0; left < points.size(); left++) {
                for (int right = left + 1; right < points.size(); right++) {
                    addJunctionCandidate(junctions, midpoint(
                            points.get(left).getMetricGeometry().getCoordinate(),
                            points.get(right).getMetricGeometry().getCoordinate()));
                }
            }
            addJunctionCandidate(junctions, centroid(points.stream()
                    .map(item -> item.getMetricGeometry().getCoordinate())
                    .collect(java.util.stream.Collectors.toList())));

            for (int index = 0; index < Math.min(junctions.size(), X2_MAX_JUNCTION_CANDIDATES); index++) {
                String id = "x2_junction_" + index;
                result.add(new SteinerNode(id, syntheticJunction(id, junctions.get(index), 0.0), true));
            }
            return result;
        }

        private void addJunctionCandidate(List<Coordinate> candidates, Coordinate coordinate) {
            if (coordinate == null || !isFreeJunction(coordinate)) {
                return;
            }
            boolean duplicate = candidates.stream().anyMatch(existing -> existing.distance(coordinate) < 1.0);
            if (!duplicate && candidates.size() < X2_MAX_JUNCTION_CANDIDATES) {
                candidates.add(new Coordinate(coordinate));
            }
        }

        private boolean isFreeJunction(Coordinate coordinate) {
            Point point = geometryFactory.createPoint(coordinate);
            for (InputFeature restriction : data.getRestrictions()) {
                String type = String.valueOf(restriction.getProperties().get("restriction_type"));
                if (("oks".equals(type) || "water".equals(type) || "railway".equals(type)
                        || "park".equals(type) || "social_area".equals(type)
                        || "prohibited_site".equals(type))
                        && restriction.getMetricGeometry().distance(point) < 0.76) {
                    return false;
                }
            }
            return true;
        }

        private TieInCandidate nearestRoot(Coordinate coordinate) {
            return roots.stream().min(Comparator.comparingDouble(
                    candidate -> candidate.getMetricPoint().distance(coordinate))).orElse(null);
        }

        private Coordinate midpoint(Coordinate a, Coordinate b) {
            return new Coordinate((a.x + b.x) / 2.0, (a.y + b.y) / 2.0);
        }

        private Coordinate centroid(List<Coordinate> coordinates) {
            double x = 0.0;
            double y = 0.0;
            for (Coordinate coordinate : coordinates) {
                x += coordinate.x;
                y += coordinate.y;
            }
            return new Coordinate(x / coordinates.size(), y / coordinates.size());
        }

        private Coordinate geometricMedian(List<Coordinate> coordinates) {
            Coordinate current = centroid(coordinates);
            for (int iteration = 0; iteration < 80; iteration++) {
                double sumWeights = 0.0;
                double x = 0.0;
                double y = 0.0;
                Coordinate coincident = null;
                for (Coordinate coordinate : coordinates) {
                    double distance = current.distance(coordinate);
                    if (distance < 0.001) {
                        coincident = coordinate;
                        break;
                    }
                    double weight = 1.0 / distance;
                    sumWeights += weight;
                    x += coordinate.x * weight;
                    y += coordinate.y * weight;
                }
                Coordinate next = coincident == null
                        ? new Coordinate(x / sumWeights, y / sumWeights)
                        : new Coordinate(coincident);
                if (current.distance(next) < 0.01) {
                    return next;
                }
                current = next;
            }
            return current;
        }

        private void prepareRoutes() {
            routes = new Route[nodes.size()][nodes.size()];
            for (int left = 0; left < nodes.size(); left++) {
                for (int right = left + 1; right < nodes.size(); right++) {
                    Optional<Route> route = routePlanner.plan(nodes.get(left).coordinate(),
                            nodes.get(right).coordinate(), context, RoutePlanner.RouteMode.CORRIDOR);
                    if (!route.isPresent() || route.get().getLengthMeters() < 0.25) {
                        continue;
                    }
                    routes[left][right] = route.get();
                    routes[right][left] = reverse(route.get());
                    graphEdges++;
                }
            }
        }

        private Route reverse(Route route) {
            LineString geometry = (LineString) route.getMetricGeometry().reverse();
            return new Route(geometry, route.getLengthMeters(), route.getLayingMethod(),
                    route.getSpecialCoefficient(), route.getNotes());
        }

        private void runDynamicProgram() {
            int stateCount = 1 << points.size();
            int nodeCount = nodes.size();
            dp = new double[stateCount][nodeCount];
            rawDp = new double[stateCount][nodeCount];
            dpSteps = new SteinerDpStep[stateCount][nodeCount];
            rawSteps = new SteinerRawStep[stateCount][nodeCount];
            for (int mask = 0; mask < stateCount; mask++) {
                Arrays.fill(dp[mask], INFINITY);
                Arrays.fill(rawDp[mask], INFINITY);
            }

            for (int mask = 1; mask < stateCount; mask++) {
                if (Integer.bitCount(mask) == 1) {
                    int terminal = Integer.numberOfTrailingZeros(mask);
                    rawDp[mask][terminal] = 0.0;
                    rawSteps[mask][terminal] = SteinerRawStep.base();
                }
                for (int leftMask = (mask - 1) & mask; leftMask > 0;
                     leftMask = (leftMask - 1) & mask) {
                    int rightMask = mask ^ leftMask;
                    if (rightMask == 0 || leftMask > rightMask) {
                        continue;
                    }
                    double chamberObjective = mergeChamberObjective(mask);
                    for (int node = 0; node < nodeCount; node++) {
                        if (!Double.isFinite(dp[leftMask][node]) || !Double.isFinite(dp[rightMask][node])) {
                            continue;
                        }
                        double candidate = dp[leftMask][node] + dp[rightMask][node] + chamberObjective;
                        if (candidate + 0.0000001 < rawDp[mask][node]) {
                            rawDp[mask][node] = candidate;
                            rawSteps[mask][node] = SteinerRawStep.merge(leftMask, rightMask);
                        }
                    }
                }

                for (int node = 0; node < nodeCount; node++) {
                    if (Double.isFinite(rawDp[mask][node])) {
                        dp[mask][node] = rawDp[mask][node];
                        dpSteps[mask][node] = SteinerDpStep.keep();
                    }
                }
                for (int source = 0; source < nodeCount; source++) {
                    if (!Double.isFinite(rawDp[mask][source])) {
                        continue;
                    }
                    for (int target = 0; target < nodeCount; target++) {
                        Route route = routes[source][target];
                        if (route == null) {
                            continue;
                        }
                        double candidate = rawDp[mask][source] + edgeObjective(route, flowByMask[mask]);
                        if (candidate + 0.0000001 < dp[mask][target]) {
                            dp[mask][target] = candidate;
                            dpSteps[mask][target] = SteinerDpStep.move(source);
                        }
                    }
                }
            }
        }

        private double mergeChamberObjective(int mask) {
            int diameter = diameterCatalog.select(flowByMask[mask], 100.0).getDiameter();
            return score(diameterCatalog.chamberCost(diameter), 0.0);
        }

        private double edgeObjective(Route route, double flow) {
            DiameterCatalog.Row diameter = diameterCatalog.select(flow, route.getLengthMeters());
            double cost = route.getLengthMeters() * diameter.getNewConstructionRubPerMeter()
                    * route.getSpecialCoefficient();
            return score(cost, route.getLengthMeters());
        }

        private double rootObjective(TieInCandidate root, Route route, double flow) {
            DiameterCatalog.Row diameter = diameterCatalog.select(flow, route.getLengthMeters());
            double tieCost = root.getHostType() == TieInCandidate.HostType.EXISTING_PIPE
                    ? diameterCatalog.chamberCost(Math.max(diameter.getDiameter(), root.getExistingDiameter()))
                    : EXISTING_CHAMBER_TIE_IN_COST;
            return edgeObjective(route, flow) + score(tieCost, 0.0);
        }

        private void evaluateFinalConnections() {
            int all = (1 << points.size()) - 1;
            List<SteinerFinal> finals = new ArrayList<>();
            for (int node = 0; node < nodes.size(); node++) {
                if (!Double.isFinite(dp[all][node])) {
                    continue;
                }
                for (TieInCandidate root : roots) {
                    Optional<Route> route = routePlanner.plan(nodes.get(node).coordinate(), root.getMetricPoint(),
                            context, RoutePlanner.RouteMode.CORRIDOR);
                    if (!route.isPresent() || route.get().getLengthMeters() < 0.25) {
                        continue;
                    }
                    finals.add(new SteinerFinal(node, root, route.get(),
                            dp[all][node] + rootObjective(root, route.get(), flowByMask[all])));
                }
            }
            finals.sort(Comparator.comparingDouble(item -> item.estimatedObjective));
            for (SteinerFinal item : finals.subList(0, Math.min(64, finals.size()))) {
                evaluatedFinals++;
                Solution candidate = materialize(all, item);
                if (candidate == null) {
                    continue;
                }
                feasibleFinals++;
                if (isBetter(candidate, best)) {
                    best = candidate;
                    improvements++;
                }
            }
        }

        private Solution materialize(int mask, SteinerFinal item) {
            List<TreeEdge> reconstructed = new ArrayList<>();
            collectDp(mask, item.node, reconstructed);
            SteinerNode rootChild = nodes.get(item.node);
            String parentId = item.root.getHostType() == TieInCandidate.HostType.EXISTING_CHAMBER
                    ? item.root.getHostId() : "ch_tie_" + rootChild.id;
            reconstructed.add(new TreeEdge(rootChild.feature, parentId, item.route, item.root,
                    rootChild.synthetic));
            List<TreeEdge> normalized = normalize(reconstructed);
            if (normalized == null || !connectsEveryTerminal(normalized)) {
                return null;
            }
            Solution candidate = newSolution(data, "X2/steiner_dp");
            emitTree(normalized, candidate);
            if (!hasAllowedNodeDegrees(candidate) || !isGeometrySafe(candidate, context)) {
                return null;
            }
            candidate.addDiagnostic("X2 estimated_objective=" + item.estimatedObjective
                    + ", root=" + item.root.getHostType() + ":" + item.root.getHostId());
            return candidate;
        }

        private void collectDp(int mask, int node, List<TreeEdge> edges) {
            SteinerDpStep step = dpSteps[mask][node];
            if (step == null) {
                throw new IllegalStateException("missing X2 DP predecessor");
            }
            if (step.source < 0) {
                collectRaw(mask, node, edges);
                return;
            }
            collectRaw(mask, step.source, edges);
            SteinerNode child = nodes.get(step.source);
            edges.add(new TreeEdge(child.feature, nodes.get(node).id, routes[step.source][node], null,
                    child.synthetic));
        }

        private void collectRaw(int mask, int node, List<TreeEdge> edges) {
            SteinerRawStep step = rawSteps[mask][node];
            if (step == null) {
                throw new IllegalStateException("missing X2 raw predecessor");
            }
            if (step.leftMask == 0) {
                return;
            }
            collectDp(step.leftMask, node, edges);
            collectDp(step.rightMask, node, edges);
        }

        private List<TreeEdge> normalize(List<TreeEdge> edges) {
            Map<String, TreeEdge> outgoing = new LinkedHashMap<>();
            for (TreeEdge edge : edges) {
                TreeEdge previous = outgoing.get(edge.childNodeId);
                if (previous == null) {
                    outgoing.put(edge.childNodeId, edge);
                } else if (!previous.parentNodeId.equals(edge.parentNodeId)) {
                    return null;
                } else if (edge.route.getLengthMeters() < previous.route.getLengthMeters()) {
                    outgoing.put(edge.childNodeId, edge);
                }
            }
            return new ArrayList<>(outgoing.values());
        }

        private boolean connectsEveryTerminal(List<TreeEdge> edges) {
            Map<String, TreeEdge> outgoing = edges.stream().collect(java.util.stream.Collectors.toMap(
                    edge -> edge.childNodeId, edge -> edge, (left, right) -> left));
            for (InputFeature point : points) {
                String node = point.getId();
                Set<String> visited = new HashSet<>();
                while (outgoing.containsKey(node)) {
                    if (!visited.add(node)) {
                        return false;
                    }
                    node = outgoing.get(node).parentNodeId;
                }
                if (visited.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class SteinerNode {
        private final String id;
        private final InputFeature feature;
        private final boolean synthetic;

        private SteinerNode(String id, InputFeature feature, boolean synthetic) {
            this.id = id;
            this.feature = feature;
            this.synthetic = synthetic;
        }

        private Coordinate coordinate() {
            return feature.getMetricGeometry().getCoordinate();
        }
    }

    private static final class SteinerRawStep {
        private final int leftMask;
        private final int rightMask;

        private SteinerRawStep(int leftMask, int rightMask) {
            this.leftMask = leftMask;
            this.rightMask = rightMask;
        }

        private static SteinerRawStep base() {
            return new SteinerRawStep(0, 0);
        }

        private static SteinerRawStep merge(int leftMask, int rightMask) {
            return new SteinerRawStep(leftMask, rightMask);
        }
    }

    private static final class SteinerDpStep {
        private final int source;

        private SteinerDpStep(int source) {
            this.source = source;
        }

        private static SteinerDpStep keep() {
            return new SteinerDpStep(-1);
        }

        private static SteinerDpStep move(int source) {
            return new SteinerDpStep(source);
        }
    }

    private static final class SteinerFinal {
        private final int node;
        private final TieInCandidate root;
        private final Route route;
        private final double estimatedObjective;

        private SteinerFinal(int node, TieInCandidate root, Route route, double estimatedObjective) {
            this.node = node;
            this.root = root;
            this.route = route;
            this.estimatedObjective = estimatedObjective;
        }
    }

    private static class JunctionStats {
        private int proposed;
        private int feasible;
        private int accepted;
        private final Map<String, Integer> rejections = new TreeMap<>();
        private final List<String> acceptedMoves = new ArrayList<>();

        private void reject(String reason) {
            rejections.merge(reason, 1, Integer::sum);
        }

        private void accept(String description) {
            accepted++;
            acceptedMoves.add(description);
        }

        private String describe() {
            return "JUNCTION_IN_EDGE proposed=" + proposed + ", feasible=" + feasible
                    + ", accepted=" + accepted + ", rejections=" + rejections
                    + ", accepted_moves=" + acceptedMoves;
        }
    }

    private static class JunctionTrial {
        private final List<TreeEdge> tree;
        private final String description;

        private JunctionTrial(List<TreeEdge> tree, String description) {
            this.tree = tree;
            this.description = description;
        }
    }

    private static class ScoredEdge {
        private final TreeEdge edge;
        private final double cost;

        private ScoredEdge(TreeEdge edge, double cost) {
            this.edge = edge;
            this.cost = cost;
        }
    }

    private static class InsertionChoice {
        private final InputFeature connection;
        private final TreeEdge edge;
        private final double bestCost;
        private final double regret;
        private final String failureReason;

        private InsertionChoice(InputFeature connection, TreeEdge edge, double bestCost,
                                double regret, String failureReason) {
            this.connection = connection;
            this.edge = edge;
            this.bestCost = bestCost;
            this.regret = regret;
            this.failureReason = failureReason;
        }

        private static InsertionChoice failed(InputFeature connection, String failureReason) {
            return new InsertionChoice(connection, null, Double.MAX_VALUE, Double.MAX_VALUE, failureReason);
        }
    }

    private static class OrderingStrategy {
        private final String name;
        private final Comparator<InputFeature> comparator;

        private OrderingStrategy(String name, Comparator<InputFeature> comparator) {
            this.name = name;
            this.comparator = comparator;
        }
    }
}
