package ru.moshackathon.heatnetwork.solver;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import ru.moshackathon.heatnetwork.model.*;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class BaselineSolver {
    private static final String VARIANT_ID = "variant_1";
    private static final double EXISTING_CHAMBER_TIE_IN_COST = 5_000_000.0;
    private static final int EXISTING_CANDIDATE_LIMIT = 5;
    private static final int PLANNED_PARENT_LIMIT = 3;
    private static final int JUNCTION_EDGE_LIMIT = 5;
    private static final double JUNCTION_ENDPOINT_MARGIN_METERS = 2.0;
    private static final double COINCIDENT_TAIL_TOLERANCE_METERS = 0.25;
    private static final double MIN_SHARED_TAIL_METERS = 2.0;
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
    private final ExecutorService portfolioExecutor;
    private final ThreadLocal<SearchBudget> activeBudget = new ThreadLocal<>();

    public BaselineSolver(TieInFinder tieInFinder, RoutePlanner routePlanner, DiameterCatalog diameterCatalog,
                          R1Solver r1Solver, R2Solver r2Solver,
                          ExecutorService portfolioExecutor) {
        this.tieInFinder = tieInFinder;
        this.routePlanner = routePlanner;
        this.diameterCatalog = diameterCatalog;
        this.r1Solver = r1Solver;
        this.r2Solver = r2Solver;
        this.portfolioExecutor = portfolioExecutor;
    }

    public Solution solve(ProblemData data) {
        return solve(data, AlgorithmId.PORTFOLIO, 0);
    }

    public Solution solve(ProblemData data, AlgorithmId algorithm) {
        return solve(data, algorithm, 0);
    }

    public Solution solve(ProblemData data, AlgorithmId algorithm, long budgetMs) {
        return solve(data, algorithm, budgetMs, null, RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);
    }

    public Solution solve(ProblemData data, AlgorithmId algorithm, long budgetMs,
                          RoutePlanner.EntryStrategy forcedEntryStrategy) {
        return solve(data, algorithm, budgetMs, forcedEntryStrategy,
                RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);
    }

    public Solution solve(ProblemData data, AlgorithmId algorithm, long budgetMs,
                          RoutePlanner.EntryStrategy forcedEntryStrategy,
                          RoutePlanner.RuleSet ruleSet) {
        SearchBudget budget = SearchBudget.ofMillis(budgetMs);
        activeBudget.set(budget);
        try {
            Solution result = solveWithinBudget(
                    data, algorithm, budgetMs, forcedEntryStrategy, ruleSet);
            result.addDiagnostic("search_budget_ms=" + (budget.isLimited() ? budgetMs : "unlimited")
                    + ", elapsed_ms=" + budget.elapsedMillis()
                    + ", deadline_reached=" + !budget.canContinue());
            return result;
        } finally {
            activeBudget.remove();
        }
    }

    private Solution solveWithinBudget(ProblemData data, AlgorithmId algorithm, long budgetMs,
                                       RoutePlanner.EntryStrategy forcedEntryStrategy,
                                       RoutePlanner.RuleSet ruleSet) {
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

        if (algorithm == AlgorithmId.PORTFOLIO) {
            return solveParallelPortfolio(data, budgetMs, forcedEntryStrategy, ruleSet);
        }

        CertifiedArchive entryArchive = new CertifiedArchive(candidate -> true);
        List<String> entryFailures = new ArrayList<>();
        RuntimeException firstFailure = null;
        Solution diagnosticFallback = null;
        SearchBudget overallBudget = activeBudget.get();
        RoutePlanner.EntryStrategy[] strategies = entryStrategies(forcedEntryStrategy, ruleSet);
        long strategyBudgetMs = budgetMs > 0
                ? Math.max(1L, budgetMs / strategies.length)
                : 0L;
        for (RoutePlanner.EntryStrategy strategy : strategies) {
            activeBudget.set(overallBudget);
            if (!overallBudget.canContinue() && !entryArchive.isEmpty()) {
                break;
            }
            activeBudget.set(overallBudget.child(strategyBudgetMs));
            RoutePlanner.RoutingContext context = routePlanner.prepare(
                    data, true, this::canContinueSearch, strategy, ruleSet);
            try {
                Solution candidate = solveWithContext(data, algorithm, context);
                candidate.addDiagnostic("entry_strategy_candidate=" + context.describeEntryStrategy());
                candidate.addDiagnostic("routing_cache=" + context.describeCache());
                candidate.addDiagnostic("routing_attempts=" + context.describeRoutingAttempts());
                candidate.addDiagnostic("entry_witnesses=" + context.describeEntryWitnesses());
                if (isGeometrySafe(candidate, context)) {
                    entryArchive.offer(strategy.name(), candidate);
                } else {
                    entryFailures.add(strategy.name() + ":CERTIFICATION_REJECTED");
                    if (diagnosticFallback == null
                            || CertifiedArchive.isBetter(candidate, diagnosticFallback)) {
                        diagnosticFallback = candidate.snapshot();
                    }
                }
            } catch (NoCertifiedSolutionException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                }
                Solution fallback = exception.getDiagnosticFallback();
                if (fallback != null && (diagnosticFallback == null
                        || CertifiedArchive.isBetter(fallback, diagnosticFallback))) {
                    diagnosticFallback = fallback;
                }
                entryFailures.add(strategy.name() + ":NO_CERTIFIED_SOLUTION");
            } catch (RuntimeException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                }
                entryFailures.add(strategy.name() + ":" + exception.getClass().getSimpleName());
            } finally {
                activeBudget.set(overallBudget);
            }
        }
        if (entryArchive.isEmpty()) {
            if (diagnosticFallback != null) {
                throw new NoCertifiedSolutionException(algorithm.getExternalName(),
                        "no candidate passed internal certification: "
                                + String.join(",", entryFailures), diagnosticFallback);
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
            throw new IllegalStateException("ENTRY_STRATEGY_ARCHIVE_EMPTY");
        }
        Solution result = entryArchive.bestSnapshot();
        result.addDiagnostic("ruleset=" + ruleSet.name());
        result.addDiagnostic("ruleset_hash=" + ruleSet.getProfileHash());
        result.addDiagnostic("ruleset_experimental=" + ruleSet.isExperimental());
        result.addDiagnostic("ruleset_authority=" + ruleSet.getAuthorityReference());
        result.addDiagnostic("entry_strategy_mode="
                + (forcedEntryStrategy == null ? "AUTO" : forcedEntryStrategy.name()));
        result.addDiagnostic("entry_strategy_selected=" + entryArchive.getBestLabel());
        result.addDiagnostic("entry_strategy_accepted="
                + String.join(",", entryArchive.getAcceptedLabels()));
        result.addDiagnostic("entry_strategy_failures="
                + (entryFailures.isEmpty() ? "none" : String.join(",", entryFailures)));
        return result;
    }

    private Solution solveParallelPortfolio(ProblemData data, long requestedBudgetMs,
                                            RoutePlanner.EntryStrategy forcedEntryStrategy,
                                            RoutePlanner.RuleSet ruleSet) {
        SearchBudget overallBudget = activeBudget.get();
        long startedNanos = System.nanoTime();
        List<PortfolioCandidate> candidates = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        // B3 is also the seed used by R1 and R2. Build it once with the full CPU
        // before launching improvers, otherwise three duplicate B3 searches
        // compete in parallel and can all miss the request deadline.
        if (overallBudget.canContinue()) {
            try {
                candidates.add(runPortfolioBranch(data, AlgorithmId.B3,
                        remainingBranchBudget(overallBudget), forcedEntryStrategy, ruleSet));
            } catch (RuntimeException exception) {
                failures.add("B3:" + exception.getClass().getSimpleName());
            } finally {
                activeBudget.set(overallBudget);
            }
        }

        if (overallBudget.canContinue()) {
            try {
                candidates.add(runPortfolioBranch(data, AlgorithmId.B2_C_J,
                        remainingBranchBudget(overallBudget), forcedEntryStrategy, ruleSet));
            } catch (RuntimeException exception) {
                failures.add("B2-C-J:" + exception.getClass().getSimpleName());
            } finally {
                activeBudget.set(overallBudget);
            }
        }

        List<AlgorithmId> branches = new ArrayList<>(Arrays.asList(
                AlgorithmId.R1, AlgorithmId.R2));
        if (data.getConnectionPoints().size() <= X0_MAX_CONNECTION_POINTS) {
            branches.add(AlgorithmId.X1);
            branches.add(AlgorithmId.X2);
        }
        List<Callable<PortfolioCandidate>> tasks = branches.stream()
                .map(branch -> (Callable<PortfolioCandidate>) () -> runPortfolioBranch(
                        data, branch, remainingBranchBudget(overallBudget),
                        forcedEntryStrategy, ruleSet))
                .collect(java.util.stream.Collectors.toList());

        List<Future<PortfolioCandidate>> futures = Collections.emptyList();
        try {
            if (overallBudget.isLimited()) {
                long waitMillis = overallBudget.remainingMillis();
                if (waitMillis > 0) {
                    futures = portfolioExecutor.invokeAll(tasks, waitMillis, TimeUnit.MILLISECONDS);
                }
            } else {
                futures = portfolioExecutor.invokeAll(tasks);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failures.add("PORTFOLIO:INTERRUPTED");
        }

        for (int index = 0; index < futures.size(); index++) {
            Future<PortfolioCandidate> future = futures.get(index);
            String label = branches.get(index).getExternalName();
            if (future.isCancelled()) {
                failures.add(label + ":DEADLINE_EXCEEDED");
                continue;
            }
            try {
                candidates.add(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failures.add(label + ":INTERRUPTED");
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof NoCertifiedSolutionException) {
                    Solution fallback = ((NoCertifiedSolutionException) cause).getDiagnosticFallback();
                    if (fallback != null) {
                        candidates.add(new PortfolioCandidate(label + "/DIAGNOSTIC", fallback));
                    }
                }
                failures.add(label + ":" + (cause == null
                        ? exception.getClass().getSimpleName()
                        : cause.getClass().getSimpleName()));
            }
        }
        activeBudget.set(overallBudget);

        List<RoutePlanner.RoutingContext> verificationContexts = new ArrayList<>();
        RoutePlanner.EntryStrategy[] verificationStrategies = entryStrategies(
                forcedEntryStrategy, ruleSet);
        for (RoutePlanner.EntryStrategy strategy : verificationStrategies) {
            verificationContexts.add(routePlanner.prepare(
                    data, true, () -> true, strategy, ruleSet));
        }
        CertifiedArchive archive = new CertifiedArchive(candidate ->
                hasAllowedNodeDegrees(candidate)
                        && verificationContexts.stream().anyMatch(context ->
                        isGeometrySafe(candidate, context)));
        for (PortfolioCandidate candidate : candidates) {
            archive.offer(candidate.label, candidate.solution);
        }
        for (String rejected : archive.getRejectedLabels()) {
            failures.add(rejected + ":CERTIFICATION_REJECTED");
        }
        if (archive.isEmpty()) {
            Solution fallback = candidates.stream()
                    .map(candidate -> candidate.solution)
                    .min((left, right) -> CertifiedArchive.isBetter(left, right) ? -1
                            : CertifiedArchive.isBetter(right, left) ? 1 : 0)
                    .map(Solution::snapshot)
                    .orElseGet(() -> solve(data, AlgorithmId.B0_CORRIDOR,
                            remainingBranchBudget(overallBudget), forcedEntryStrategy, ruleSet));
            throw new NoCertifiedSolutionException("PORTFOLIO",
                    "parallel portfolio has no certified result: " + String.join(",", failures),
                    fallback);
        }

        Solution best = archive.bestSnapshot();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        best.addDiagnostic("algorithm=PORTFOLIO");
        best.addDiagnostic("PORTFOLIO mode=parallel, selected=" + archive.getBestLabel());
        best.addDiagnostic("PORTFOLIO accepted=" + String.join(",", archive.getAcceptedLabels()));
        best.addDiagnostic("PORTFOLIO construction_diversity="
                + archive.describeConstructionDiversity());
        best.addDiagnostic("PORTFOLIO failures="
                + (failures.isEmpty() ? "none" : String.join(",", failures)));
        best.addDiagnostic("PORTFOLIO branches=" + branches.stream()
                .map(AlgorithmId::getExternalName).collect(java.util.stream.Collectors.joining(","))
                + ", wall_time_ms=" + elapsedMillis
                + ", requested_budget_ms=" + requestedBudgetMs);
        return best;
    }

    private PortfolioCandidate runPortfolioBranch(ProblemData data, AlgorithmId algorithm,
                                                   long budgetMs,
                                                   RoutePlanner.EntryStrategy forcedEntryStrategy,
                                                   RoutePlanner.RuleSet ruleSet) {
        long startedNanos = System.nanoTime();
        Solution solution = solve(data, algorithm, budgetMs, forcedEntryStrategy, ruleSet);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        solution.addDiagnostic("portfolio_branch=" + algorithm.getExternalName()
                + ", elapsed_ms=" + elapsedMillis);
        return new PortfolioCandidate(algorithm.getExternalName(), solution);
    }

    private long remainingBranchBudget(SearchBudget overallBudget) {
        return overallBudget.isLimited()
                ? Math.max(1L, overallBudget.remainingMillis())
                : 0L;
    }

    private RoutePlanner.EntryStrategy[] entryStrategies(
            RoutePlanner.EntryStrategy forcedEntryStrategy, RoutePlanner.RuleSet ruleSet) {
        if (forcedEntryStrategy != null) {
            return new RoutePlanner.EntryStrategy[]{forcedEntryStrategy};
        }
        if (ruleSet == RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1) {
            return new RoutePlanner.EntryStrategy[]{RoutePlanner.EntryStrategy.PORTAL_ONLY};
        }
        return RoutePlanner.EntryStrategy.values();
    }

    private Solution solveWithContext(ProblemData data, AlgorithmId algorithm,
                                      RoutePlanner.RoutingContext context) {
        Solution result;
        switch (algorithm) {
            case B0_GRID:
                result = solveOrdered(data, context, flowDescending(), false,
                        RoutePlanner.RouteMode.GRID, algorithm.getExternalName());
                break;
            case B0_CORRIDOR:
                result = solveOrdered(data, context, flowDescending(), false,
                        RoutePlanner.RouteMode.CORRIDOR, algorithm.getExternalName());
                break;
            case B1:
                result = solveRegret(data, context, algorithm.getExternalName());
                break;
            case B2_U:
            case B2_Q:
            case B2_C:
                result = solveMedoid(data, context, algorithm);
                break;
            case B2_C_J:
                result = solveMedoidWithJunctions(data, context);
                break;
            case B3: {
                result = solveDestroyRepair(data, context, context);
                break;
            }
            case R1_PILOT:
                result = solveR1(data, context, "R1-PILOT");
                break;
            case R1:
                result = solveR1Concrete(data, context);
                break;
            case R2:
                result = solveR2(data, context);
                break;
            case X0:
                result = solveSmallExactControl(data, context);
                break;
            case X1: {
                Solution initial = solveDestroyRepair(data, context, context);
                result = solveExactParentTree(data, initial, context, context);
                break;
            }
            case X2: {
                Solution initial = solveDestroyRepair(data, context, context);
                Solution exactTree = solveExactParentTree(data, initial, context, context);
                result = solveSteinerGraph(data, context, exactTree);
                break;
            }
            case PORTFOLIO:
            default:
                result = solveProductionPortfolio(data, context);
                break;
        }
        return result;
    }

    private Solution solveR1(ProblemData data, RoutePlanner.RoutingContext context, String algorithmLabel) {
        Solution initial = solveRegret(data, context, algorithmLabel + "/INITIAL_B1");
        Solution result = r1Solver.solve(data, initial, actionName -> {
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
                    return solveDestroyRepair(data, context, context);
                default:
                    throw new IllegalStateException("unknown R1 action " + actionName);
            }
        }, algorithmLabel, this::canContinueSearch);
        if (!isGeometrySafe(result, context)) {
            initial.addDiagnostic(algorithmLabel + " rejected unsafe policy result; returned certified initial");
            return initial;
        }
        return result;
    }

    private Solution solveR1Concrete(ProblemData data, RoutePlanner.RoutingContext context) {
        // R1 is an improver, so it must not have a weaker starting guarantee than B3.
        // Keep this certified incumbent even when no concrete policy seed survives validation.
        Solution b3Incumbent = solveDestroyRepair(data, context, context);
        boolean b3IncumbentSafe = isGeometrySafe(b3Incumbent, context);
        List<ConcretePlan> seedPlans = new ArrayList<>();
        List<InputFeature> flowOrder = new ArrayList<>(data.getConnectionPoints());
        flowOrder.sort(flowDescending());
        seedPlans.add(new ConcretePlan(flowOrder, Collections.emptyMap(), false, "INITIAL_FLOW_DESC"));
        for (AlgorithmId seed : List.of(AlgorithmId.B2_U, AlgorithmId.B2_Q, AlgorithmId.B2_C)) {
            InputFeature medoid = weightedMedoid(data.getConnectionPoints(), seed);
            if (medoid == null) {
                continue;
            }
            List<List<InputFeature>> orders = List.of(
                    medoidOrder(data.getConnectionPoints(), medoid, false),
                    medoidOrder(data.getConnectionPoints(), medoid, true),
                    angularOrder(data.getConnectionPoints(), medoid));
            String[] modes = {"NEAR", "FAR", "ANGULAR"};
            for (int index = 0; index < orders.size(); index++) {
                String label = "INITIAL_" + seed.getExternalName() + "_" + modes[index];
                seedPlans.add(new ConcretePlan(orders.get(index), Collections.emptyMap(), false, label));
                if (seed == AlgorithmId.B2_C) {
                    String junctionLabel = "INITIAL_B2-C-J_" + modes[index];
                    seedPlans.add(new ConcretePlan(
                            orders.get(index), Collections.emptyMap(), true, junctionLabel));
                }
            }
        }
        ConcretePlan selectedPlan = seedPlans.get(0);
        TreeBuild selectedBuild = evaluateConcretePlan(data, context, selectedPlan, "R1/INITIAL");
        ConcretePlan selectedSafePlan = isGeometrySafe(selectedBuild.solution, context) ? selectedPlan : null;
        TreeBuild selectedSafeBuild = selectedSafePlan == null ? null : selectedBuild;
        for (int index = 1; index < seedPlans.size(); index++) {
            if (!canContinueSearch()) {
                break;
            }
            ConcretePlan candidatePlan = seedPlans.get(index);
            TreeBuild candidateBuild = evaluateConcretePlan(
                    data, context, candidatePlan, "R1/" + candidatePlan.label);
            if (isBetter(candidateBuild.solution, selectedBuild.solution)) {
                selectedPlan = candidatePlan;
                selectedBuild = candidateBuild;
            }
            if (isGeometrySafe(candidateBuild.solution, context)
                    && (selectedSafeBuild == null
                    || isBetter(candidateBuild.solution, selectedSafeBuild.solution))) {
                selectedSafePlan = candidatePlan;
                selectedSafeBuild = candidateBuild;
            }
        }
        if (b3IncumbentSafe && (selectedSafeBuild == null
                || isBetter(b3Incumbent, selectedSafeBuild.solution))) {
            selectedSafePlan = selectedPlan;
            selectedSafeBuild = new TreeBuild(
                    selectedBuild.order, selectedBuild.edges, b3Incumbent.snapshot());
            selectedSafeBuild.solution.addDiagnostic("R1 common certified seed=B3");
        }
        if (selectedSafeBuild == null) {
            Solution fallback = solveOrdered(data, context, flowDescending(), false,
                    RoutePlanner.RouteMode.CORRIDOR, "R1/diagnostic_independent_fallback");
            throw new NoCertifiedSolutionException("R1",
                    "R1 has neither a safe B3 incumbent nor a safe concrete seed", fallback);
        }
        selectedPlan = selectedSafePlan;
        selectedBuild = selectedSafeBuild;
        final ConcretePlan initialPlan = selectedPlan;
        final TreeBuild initialBuild = selectedBuild;

        class ConcreteEnvironment implements R1Solver.Environment {
            private ConcretePlan currentPlan = initialPlan;
            private Solution currentSolution = initialBuild.solution;
            private final Set<String> attempted = new HashSet<>(
                    Collections.singleton(initialPlan.signature()));

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
                attempted.add(next.signature());
                TreeBuild build = evaluateConcretePlan(data, context, next,
                        "R1/" + action.getActionType());
                int candidateUnconnected = build.solution.getUnconnectedConnectionPointIds().size();
                int currentUnconnected = currentSolution.getUnconnectedConnectionPointIds().size();
                if (candidateUnconnected > currentUnconnected) {
                    throw new IllegalStateException("R1_ACTION_COVERAGE_REGRESSION");
                }
                if (!isGeometrySafe(build.solution, context)) {
                    throw new IllegalStateException("R1_ACTION_GEOMETRY_UNSAFE");
                }
                currentPlan = next;
                currentSolution = build.solution;
                build.solution.addDiagnostic("R1 applied concrete action=" + action.getActionId());
                return build.solution;
            }
        }

        Solution result = r1Solver.solveConcrete(
                data, initialBuild.solution, new ConcreteEnvironment(), "R1", 32,
                this::canContinueSearch);
        result.addDiagnostic("R1 certified initial seed=" + initialPlan.label
                + " from " + seedPlans.size() + " concrete seeds");
        if (!isGeometrySafe(result, context)) {
            initialBuild.solution.addDiagnostic("R1 rejected unsafe final policy result");
            return initialBuild.solution;
        }
        return result;
    }

    private Solution solveR2(ProblemData data, RoutePlanner.RoutingContext context) {
        Solution initial = solveDestroyRepair(data, context, context);
        return solveR2(data, context, initial);
    }

    private Solution solveR2(ProblemData data, RoutePlanner.RoutingContext context, Solution initial) {
        initial.addDiagnostic("R2 initial archive=B3 guarded exact/heuristic search");
        Solution result = r2Solver.solve(initial,
                action -> solveR2Operator(data, context, action, initial),
                this::canContinueSearch);
        if (!isGeometrySafe(result, context)) {
            initial.addDiagnostic("R2 rejected unsafe policy result; returned certified B3 incumbent");
            return initial;
        }
        return result;
    }

    private Solution solveR2Operator(ProblemData data, RoutePlanner.RoutingContext context,
                                     String action, Solution incumbent) {
        List<InputFeature> order = new ArrayList<>(data.getConnectionPoints());
        switch (action) {
            case "RANDOM_20+GREEDY":
                order.sort(flowDescending());
                Collections.shuffle(order.subList(0, Math.max(2, order.size() / 5)), new Random(11));
                return solveInOrder(data, context, order, true, RoutePlanner.RouteMode.CORRIDOR,
                        "R2/RANDOM_20+GREEDY", true);
            case "GEOGRAPHIC_25+GREEDY":
                order.sort(Comparator.comparingDouble(item -> item.getMetricGeometry().getCoordinate().x));
                return solveInOrder(data, context, order, true, RoutePlanner.RouteMode.CORRIDOR,
                        "R2/GEOGRAPHIC_25+GREEDY", true);
            case "HIGH_FLOW_25+REGRET":
                return solveRegret(data, context, "R2/HIGH_FLOW_25+REGRET");
            case "BACKBONE_40+JUNCTION":
                return solveMedoidWithJunctions(data, context);
            case "SUBTREE_25+REGRET":
                return solveRegionOrderRepair(data, context, incumbent, 48);
            default:
                throw new IllegalArgumentException("unknown R2 operator " + action);
        }
    }

    private Solution solveRegionOrderRepair(ProblemData data, RoutePlanner.RoutingContext context,
                                            Solution incumbent, int candidateLimit) {
        CertifiedArchive archive = new CertifiedArchive(candidate -> isGeometrySafe(candidate, context));
        archive.offer("incumbent", incumbent);
        List<InputFeature> base = new ArrayList<>(data.getConnectionPoints());
        base.sort(flowDescending());
        List<InputFeature> centers = new ArrayList<>(base);
        centers.sort(Comparator.comparing(InputFeature::getId));
        Set<String> seenOrders = new HashSet<>();
        int evaluated = 0;
        boolean exhausted = true;

        outer:
        for (InputFeature center : centers) {
            List<InputFeature> group = new ArrayList<>(base);
            group.sort(Comparator.comparingDouble((InputFeature point) -> point.getMetricGeometry().getCoordinate()
                    .distance(center.getMetricGeometry().getCoordinate()))
                    .thenComparing(InputFeature::getId));
            group = new ArrayList<>(group.subList(0, Math.min(4, group.size())));
            if (group.size() < 2) {
                continue;
            }
            List<Integer> slots = group.stream().map(base::indexOf).sorted()
                    .collect(java.util.stream.Collectors.toList());
            List<List<InputFeature>> permutations = new ArrayList<>();
            permute(new ArrayList<>(group), 0, permutations);
            for (List<InputFeature> permutation : permutations) {
                if (!canContinueSearch()) {
                    exhausted = false;
                    break outer;
                }
                List<InputFeature> order = new ArrayList<>(base);
                for (int index = 0; index < slots.size(); index++) {
                    order.set(slots.get(index), permutation.get(index));
                }
                String signature = order.stream().map(InputFeature::getId)
                        .collect(java.util.stream.Collectors.joining("|"));
                if (!seenOrders.add(signature)) {
                    continue;
                }
                if (evaluated >= candidateLimit) {
                    exhausted = false;
                    break outer;
                }
                Solution candidate = solveInOrder(data, context, order, true,
                        RoutePlanner.RouteMode.CORRIDOR, "REGION_REPAIR/order_" + evaluated, true);
                archive.offer("order_" + evaluated, candidate);
                evaluated++;
            }
        }
        Solution best = archive.bestSnapshot();
        best.addDiagnostic("REGION_REOPTIMIZE model_kind=bounded_order_surrogate"
                + ", group_size=2..4, evaluated=" + evaluated
                + ", search_exhausted=" + exhausted
                + ", selected=" + archive.getBestLabel());
        return best;
    }

    private TreeBuild evaluateConcretePlan(ProblemData data, RoutePlanner.RoutingContext context,
                                           ConcretePlan plan, String label) {
        if (!plan.splitGroups.isEmpty()) {
            return evaluateSplitPlan(data, context, plan, label);
        }
        return buildInOrder(data, context, plan.order, true, plan.routeMode,
                label, plan.allowJunctions, plan.forcedParents);
    }

    private TreeBuild evaluateSplitPlan(ProblemData data, RoutePlanner.RoutingContext context,
                                        ConcretePlan plan, String label) {
        List<TreeEdge> combinedEdges = new ArrayList<>();
        Set<String> unconnected = new LinkedHashSet<>();
        for (Integer groupId : new TreeSet<>(plan.splitGroups.values())) {
            List<InputFeature> group = plan.order.stream()
                    .filter(point -> Objects.equals(plan.splitGroups.get(point.getId()), groupId))
                    .collect(java.util.stream.Collectors.toList());
            if (group.isEmpty()) {
                continue;
            }
            TreeBuild groupBuild = buildInOrder(data, context, group, true, plan.routeMode,
                    label + "/group_" + groupId, plan.allowJunctions, Collections.emptyMap());
            combinedEdges.addAll(groupBuild.edges);
            unconnected.addAll(groupBuild.solution.getUnconnectedConnectionPointIds());
        }
        Solution solution = newSolution(data, label);
        emitTree(combinedEdges, solution, context);
        for (String connectionId : unconnected) {
            InputFeature point = data.getConnectionPoints().stream()
                    .filter(item -> item.getId().equals(connectionId)).findFirst().orElse(null);
            double flow = point == null ? 0.0 : point.getDouble("flow_tph", 0.0);
            solution.addUnconnected(connectionId, penalty(flow), "split group has no certified route");
        }
        solution.addDiagnostic("SPLIT_AND_REDISTRIBUTE groups="
                + new TreeSet<>(plan.splitGroups.values()).size());
        return new TreeBuild(plan.order, combinedEdges, solution);
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
        for (ConcretePlan plan : splitAndRedistributePlans(points)) {
            addConcreteAction(result, attempted, plan.label, "SPLIT_REDISTRIBUTE",
                    plan.order.get(0), null, plan, step);
        }
        for (ConcretePlan plan : regionalCorridorJunctionPlans(current, points)) {
            addConcreteAction(result, attempted, plan.label, "REGIONAL_REBUILD",
                    plan.order.get(0), null, plan, step);
        }
        return result.stream().limit(127).collect(java.util.stream.Collectors.toList());
    }

    private List<ConcretePlan> splitAndRedistributePlans(List<InputFeature> points) {
        if (points.size() < 4) {
            return Collections.emptyList();
        }
        List<Comparator<InputFeature>> axes = List.of(
                Comparator.comparingDouble((InputFeature point) ->
                        point.getMetricGeometry().getCoordinate().x).thenComparing(InputFeature::getId),
                Comparator.comparingDouble((InputFeature point) ->
                        point.getMetricGeometry().getCoordinate().y).thenComparing(InputFeature::getId));
        List<ConcretePlan> plans = new ArrayList<>();
        String[] names = {"X", "Y"};
        for (int axis = 0; axis < axes.size(); axis++) {
            List<InputFeature> sorted = new ArrayList<>(points);
            sorted.sort(axes.get(axis));
            int split = sorted.size() / 2;
            List<InputFeature> first = clusteredOrder(sorted.subList(0, split));
            List<InputFeature> second = clusteredOrder(sorted.subList(split, sorted.size()));
            for (int swap = 0; swap < 2; swap++) {
                List<InputFeature> left = swap == 0 ? first : second;
                List<InputFeature> right = swap == 0 ? second : first;
                List<InputFeature> order = new ArrayList<>(left);
                order.addAll(right);
                Map<String, Integer> groups = new LinkedHashMap<>();
                left.forEach(point -> groups.put(point.getId(), 0));
                right.forEach(point -> groups.put(point.getId(), 1));
                String id = "SPLIT_REDISTRIBUTE:axis=" + names[axis] + ":swap=" + swap;
                plans.add(new ConcretePlan(order, Collections.emptyMap(), true,
                        RoutePlanner.RouteMode.CORRIDOR, groups, id));
            }
        }
        return plans;
    }

    private List<InputFeature> clusteredOrder(List<InputFeature> source) {
        List<InputFeature> group = new ArrayList<>(source);
        InputFeature medoid = weightedMedoid(group, AlgorithmId.B2_C);
        return medoid == null ? group : medoidOrder(group, medoid, false);
    }

    private List<ConcretePlan> regionalCorridorJunctionPlans(ConcretePlan current,
                                                              List<InputFeature> points) {
        if (points.size() < 3) {
            return Collections.emptyList();
        }
        List<InputFeature> centers = new ArrayList<>(points);
        centers.sort(Comparator.comparing(InputFeature::getId));
        List<ConcretePlan> plans = new ArrayList<>();
        List<InputFeature> selectedCenters = centers.subList(0, Math.min(4, centers.size()));
        for (RoutePlanner.RouteMode mode : List.of(
                RoutePlanner.RouteMode.CORRIDOR, RoutePlanner.RouteMode.GRID)) {
            for (InputFeature center : selectedCenters) {
                List<InputFeature> region = new ArrayList<>(points);
                Coordinate coordinate = center.getMetricGeometry().getCoordinate();
                region.sort(Comparator.comparingDouble((InputFeature point) -> coordinate.distance(
                        point.getMetricGeometry().getCoordinate())).thenComparing(InputFeature::getId));
                region = new ArrayList<>(region.subList(0, Math.min(4, region.size())));
                Collections.reverse(region);
                List<InputFeature> order = new ArrayList<>(region);
                for (InputFeature point : current.order) {
                    if (!region.contains(point)) {
                        order.add(point);
                    }
                }
                String id = "REGIONAL_REBUILD:center=" + center.getId() + ":mode=" + mode;
                plans.add(new ConcretePlan(order, Collections.emptyMap(), true,
                        mode, Collections.emptyMap(), id));
            }
        }
        return plans;
    }

    private void addConcreteAction(List<R1Solver.Action> actions, Set<String> attempted,
                                   String id, String type, InputFeature child, InputFeature parent,
                                   ConcretePlan payload, int step) {
        String stateId = "s" + step + ":" + id;
        if (attempted.contains(payload.signature())) {
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
        if ("SPLIT_REDISTRIBUTE".equals(type)) {
            type = "MERGE";
        } else if ("REGIONAL_REBUILD".equals(type)) {
            type = "REATTACH";
        }
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
        Solution initial = solveDestroyRepair(data, context, context);
        CertifiedArchive archive = new CertifiedArchive(candidate -> isGeometrySafe(candidate, context));
        List<String> portfolioFailures = new ArrayList<>();
        archive.offer("B3", initial);
        if (canContinueSearch()) try {
            archive.offer("R2", solveR2(data, context, initial));
        } catch (RuntimeException exception) {
            portfolioFailures.add("R2:" + exception.getClass().getSimpleName());
        }
        if (canContinueSearch()) try {
            archive.offer("R1", solveR1Concrete(data, context));
        } catch (RuntimeException exception) {
            portfolioFailures.add("R1:" + exception.getClass().getSimpleName());
        }
        Solution best = archive.bestSnapshot();
        if (data.getConnectionPoints().size() <= X0_MAX_CONNECTION_POINTS
                && hasSearchBudgetMillis(5_000)) {
            best = solveExactParentTree(data, best, context, context);
            best = solveSteinerGraph(data, context, best);
        }
        best.addDiagnostic("PORTFOLIO certified seed selected=" + archive.getBestLabel()
                + " from B3/R2/R1");
        best.addDiagnostic("PORTFOLIO failed branches="
                + (portfolioFailures.isEmpty() ? "none" : String.join(",", portfolioFailures)));
        best.addDiagnostic("PORTFOLIO selected guarded B3 + R2 regional repair + R1 concrete policy"
                + " + exact X1 + Steiner X2 cascade");
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
        emitTree(edges, solution, context);
        if (allowJunctions) {
            solution.addDiagnostic(junctionStats.describe());
        }
        return new TreeBuild(new ArrayList<>(ordered), edges, solution);
    }

    private Solution solveRegret(ProblemData data, RoutePlanner.RoutingContext context, String algorithmLabel) {
        Solution solution = newSolution(data, algorithmLabel);
        solution.addDiagnostic("B1-lite: regret-2 insertion into legal existing tie-ins; consumer nodes remain terminal");
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
        emitTree(edges, solution, context);
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
            if (best != null && !canContinueSearch()) {
                break;
            }
            Solution candidate = solveInOrder(data, context, orders.get(i), true,
                    RoutePlanner.RouteMode.CORRIDOR, algorithm.getExternalName() + "/" + names[i], true);
            if (isBetter(candidate, best)) {
                best = candidate;
                selected = names[i];
            }
        }
        best.addDiagnostic(algorithm.getExternalName() + " medoid=" + medoid.getId()
                + ", selected backbone seed=" + selected);
        best.addDiagnostic("B2-lite: medoid/order seeds with exterior projected junctions; consumer nodes remain terminal");
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
            if (bestJunction != null && !canContinueSearch()) {
                break;
            }
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
        RoutePlanner.RoutingContext searchContext = routePlanner.prepare(data, false, this::canContinueSearch);
        RoutePlanner.RoutingContext strictContext = routePlanner.prepare(data, true, this::canContinueSearch);
        return solveDestroyRepair(data, searchContext, strictContext);
    }

    private Solution solveDestroyRepair(ProblemData data,
                                        RoutePlanner.RoutingContext searchContext,
                                        RoutePlanner.RoutingContext strictContext) {
        List<List<InputFeature>> orderArchive = new ArrayList<>();
        for (OrderingStrategy strategy : standardStrategies()) {
            List<InputFeature> order = new ArrayList<>(data.getConnectionPoints());
            order.sort(strategy.comparator);
            orderArchive.add(order);
        }
        for (AlgorithmId seed : List.of(AlgorithmId.B2_U, AlgorithmId.B2_Q, AlgorithmId.B2_C)) {
            InputFeature medoid = weightedMedoid(data.getConnectionPoints(), seed);
            if (medoid == null) {
                continue;
            }
            orderArchive.add(medoidOrder(data.getConnectionPoints(), medoid, false));
            orderArchive.add(medoidOrder(data.getConnectionPoints(), medoid, true));
            orderArchive.add(angularOrder(data.getConnectionPoints(), medoid));
        }

        List<InputFeature> base = new ArrayList<>(data.getConnectionPoints());
        base.sort(flowDescending());
        for (long seed : new long[]{11, 23, 37, 53, 71}) {
            List<InputFeature> destroyed = new ArrayList<>(base);
            Collections.shuffle(destroyed, new Random(seed));
            orderArchive.add(destroyed);
        }
        for (int start = 0; start < Math.min(base.size(), 10); start++) {
            List<InputFeature> repaired = new ArrayList<>(base);
            int end = Math.min(repaired.size(), start + Math.max(2, repaired.size() / 4));
            Collections.reverse(repaired.subList(start, end));
            orderArchive.add(repaired);
        }
        boolean exactOrderSearch = base.size() <= X0_MAX_CONNECTION_POINTS;
        if (exactOrderSearch) {
            List<List<InputFeature>> permutations = new ArrayList<>();
            permute(new ArrayList<>(base), 0, permutations);
            orderArchive.addAll(permutations);
        }

        CertifiedArchive certified = new CertifiedArchive(candidate -> isGeometrySafe(candidate, strictContext));
        for (AlgorithmId seed : List.of(AlgorithmId.B2_U, AlgorithmId.B2_Q, AlgorithmId.B2_C)) {
            if (!canContinueSearch() && !certified.isEmpty()) {
                break;
            }
            certified.offer(seed.getExternalName(), solveMedoid(data, strictContext, seed));
        }
        if (canContinueSearch()) {
            certified.offer(AlgorithmId.B2_C_J.getExternalName(),
                    solveMedoidWithJunctions(data, strictContext));
        }
        ConcretePlan structuredBase = new ConcretePlan(base, Collections.emptyMap(), true,
                "STRUCTURED_BASE");
        List<ConcretePlan> regionalPlans = regionalCorridorJunctionPlans(
                structuredBase, data.getConnectionPoints());
        List<ConcretePlan> structuredPlans = new ArrayList<>(regionalPlans);
        structuredPlans.addAll(splitAndRedistributePlans(data.getConnectionPoints()));
        int priorityStructuredPlans = Math.min(4, regionalPlans.size());
        int structuredAccepted = 0;
        int structuredRejected = 0;
        int structuredEvaluated = 0;
        for (ConcretePlan plan : structuredPlans) {
            if (!canContinueSearch()) {
                break;
            }
            TreeBuild build = evaluateConcretePlan(data, strictContext, plan,
                    "B3/" + plan.label);
            if (isGeometrySafe(build.solution, strictContext)) {
                certified.offer(plan.label, build.solution);
                structuredAccepted++;
            } else {
                structuredRejected++;
            }
            structuredEvaluated++;
            if (structuredEvaluated >= priorityStructuredPlans
                    && hasCompleteCandidate(certified)) {
                break;
            }
        }
        boolean completeStructuredIncumbent = hasCompleteCandidate(certified);
        if (!completeStructuredIncumbent && canContinueSearch()) {
            certified.offer("strict_regret_fallback",
                    solveRegret(data, strictContext, "B3/strict_regret_fallback"));
        }
        Solution independentFallback = null;
        if (certified.isEmpty()) {
            independentFallback = solveOrdered(data, strictContext, flowDescending(), false,
                    RoutePlanner.RouteMode.CORRIDOR, "B3/independent_fallback");
            certified.offer("B0-CORRIDOR", independentFallback);
        }
        int rejectedUnsafe = 0;
        if (!completeStructuredIncumbent && canContinueSearch()) {
            Solution relaxedRegret = solveRegret(data, searchContext, "B3/regret_repair");
            if (isGeometrySafe(relaxedRegret, strictContext)) {
                certified.offer("regret_repair", relaxedRegret);
            } else {
                rejectedUnsafe++;
            }
        }
        int index = 0;
        for (List<InputFeature> order : completeStructuredIncumbent
                ? Collections.<List<InputFeature>>emptyList() : orderArchive) {
            if (!canContinueSearch()) {
                break;
            }
            Solution candidate = solveInOrder(data, searchContext, order, true,
                    RoutePlanner.RouteMode.CORRIDOR, "B3/destroy_repair_" + index, true);
            if (isGeometrySafe(candidate, strictContext)) {
                certified.offer("destroy_repair_" + index, candidate);
            } else {
                rejectedUnsafe++;
                Solution repaired = solveInOrder(data, strictContext, order, true,
                        RoutePlanner.RouteMode.CORRIDOR, "B3/strict_repair_" + index, true);
                certified.offer("strict_repair_" + index, repaired);
            }
            index++;
        }
        if (certified.isEmpty()) {
            Solution fallback = independentFallback != null
                    ? independentFallback.snapshot()
                    : solveOrdered(data, strictContext, flowDescending(), false,
                    RoutePlanner.RouteMode.CORRIDOR, "B3/independent_fallback");
            throw new NoCertifiedSolutionException("B3",
                    "B3 rejected labels=" + String.join(",", certified.getRejectedLabels()),
                    fallback);
        }
        Solution best = certified.bestSnapshot();
        best.addDiagnostic("B3 certified archive selected=" + certified.getBestLabel()
                + ", accepted=" + certified.getAcceptedLabels().size()
                + ", rejected=" + certified.getRejectedLabels().size()
                + ", " + certified.describeConstructionDiversity());
        best.addDiagnostic("B3-lite evaluated " + orderArchive.size()
                + " deterministic destroy/repair orders plus verified B2/B2-C-J seeds");
        best.addDiagnostic("B3 exact order search=" + exactOrderSearch
                + " (enabled for at most " + X0_MAX_CONNECTION_POINTS + " connection points)");
        best.addDiagnostic("B3 geometry guard rejected=" + rejectedUnsafe
                + " unsafe relaxed candidates; strict fallback remained available");
        best.addDiagnostic("B3 structured_rebuilds proposed=" + structuredPlans.size()
                + ", evaluated=" + structuredEvaluated
                + ", accepted=" + structuredAccepted + ", rejected=" + structuredRejected
                + ", complete_incumbent=" + completeStructuredIncumbent);
        best.addDiagnostic("adaptive operator weights and edge-level subtree removal remain research extensions");
        return best;
    }

    private boolean canContinueSearch() {
        SearchBudget budget = activeBudget.get();
        return budget == null || budget.canContinue();
    }

    private boolean hasCompleteCandidate(CertifiedArchive archive) {
        return !archive.isEmpty()
                && archive.bestSnapshot().getUnconnectedConnectionPointIds().isEmpty();
    }

    private boolean hasSearchBudgetMillis(long millis) {
        SearchBudget budget = activeBudget.get();
        return budget == null || budget.hasAtLeastMillis(millis);
    }

    private boolean isGeometrySafe(Solution solution, RoutePlanner.RoutingContext strictContext) {
        if (!hasAllowedNodeDegrees(solution)
                || hasParallelNetworkOverlap(solution)
                || !solution.getSegments().stream().allMatch(segment ->
                routePlanner.avoidsForbiddenRestrictions(
                        segment.getMetricGeometry(), strictContext, segment.getDiameter())
                        && routePlanner.hasAllowedTurns(segment.getMetricGeometry()))) {
            return false;
        }

        Map<String, List<NewSegment>> incoming = new HashMap<>();
        Map<String, List<NewSegment>> outgoing = new HashMap<>();
        for (NewSegment segment : solution.getSegments()) {
            incoming.computeIfAbsent(segment.getEndNodeId(), ignored -> new ArrayList<>()).add(segment);
            outgoing.computeIfAbsent(segment.getStartNodeId(), ignored -> new ArrayList<>()).add(segment);
        }
        Set<String> declaredNetworkNodes = new HashSet<>();
        solution.getChambers().forEach(chamber -> declaredNetworkNodes.add(chamber.getId()));
        solution.getTechnicalNodes().forEach(node -> declaredNetworkNodes.add(node.getId()));
        for (String nodeId : incoming.keySet()) {
            if (!outgoing.containsKey(nodeId)) {
                continue;
            }
            if (!declaredNetworkNodes.contains(nodeId)) {
                return false;
            }
            for (NewSegment before : incoming.get(nodeId)) {
                for (NewSegment after : outgoing.get(nodeId)) {
                    if (!routePlanner.hasAllowedTransition(
                            before.getMetricGeometry(), after.getMetricGeometry())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public boolean isCertifiedAfterExport(ProblemData data, Solution solution,
                                          RoutePlanner.EntryStrategy forcedEntryStrategy,
                                          RoutePlanner.RuleSet ruleSet) {
        RoutePlanner.EntryStrategy[] strategies = entryStrategies(forcedEntryStrategy, ruleSet);
        for (RoutePlanner.EntryStrategy strategy : strategies) {
            RoutePlanner.RoutingContext context = routePlanner.prepare(
                    data, true, () -> true, strategy, ruleSet);
            if (isGeometrySafe(solution, context)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasParallelNetworkOverlap(Solution solution) {
        List<NewSegment> segments = solution.getSegments();
        for (int leftIndex = 0; leftIndex < segments.size(); leftIndex++) {
            Coordinate[] left = segments.get(leftIndex).getMetricGeometry().getCoordinates();
            for (int rightIndex = leftIndex + 1; rightIndex < segments.size(); rightIndex++) {
                Coordinate[] right = segments.get(rightIndex).getMetricGeometry().getCoordinates();
                for (int i = 0; i < left.length - 1; i++) {
                    for (int j = 0; j < right.length - 1; j++) {
                        if (nearCollinearOverlapLength(left[i], left[i + 1], right[j], right[j + 1])
                                > MIN_SHARED_TAIL_METERS) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private double nearCollinearOverlapLength(Coordinate a, Coordinate b,
                                              Coordinate c, Coordinate d) {
        double abX = b.x - a.x;
        double abY = b.y - a.y;
        double cdX = d.x - c.x;
        double cdY = d.y - c.y;
        double abLength = Math.hypot(abX, abY);
        double cdLength = Math.hypot(cdX, cdY);
        if (abLength < 1e-6 || cdLength < 1e-6) {
            return 0.0;
        }
        double unitX = abX / abLength;
        double unitY = abY / abLength;
        double parallel = Math.abs(unitX * cdY - unitY * cdX) / cdLength;
        if (parallel > 0.01) {
            return 0.0;
        }
        double perpendicularC = Math.abs((c.x - a.x) * unitY - (c.y - a.y) * unitX);
        double perpendicularD = Math.abs((d.x - a.x) * unitY - (d.y - a.y) * unitX);
        if (Math.max(perpendicularC, perpendicularD) > COINCIDENT_TAIL_TOLERANCE_METERS) {
            return 0.0;
        }
        double projectionC = (c.x - a.x) * unitX + (c.y - a.y) * unitY;
        double projectionD = (d.x - a.x) * unitX + (d.y - a.y) * unitY;
        double overlapStart = Math.max(0.0, Math.min(projectionC, projectionD));
        double overlapEnd = Math.min(abLength, Math.max(projectionC, projectionD));
        return Math.max(0.0, overlapEnd - overlapStart);
    }

    private Solution solveSmallExactControl(ProblemData data, RoutePlanner.RoutingContext context) {
        List<InputFeature> points = new ArrayList<>(data.getConnectionPoints());
        List<List<InputFeature>> permutations = new ArrayList<>();
        permute(points, 0, permutations);
        Solution best = null;
        int bestIndex = -1;
        for (int i = 0; i < permutations.size(); i++) {
            Solution candidate = solveInOrder(data, context, permutations.get(i), true,
                    RoutePlanner.RouteMode.CORRIDOR, "X0/permutation_" + i, true);
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
        RoutePlanner.RoutingContext relaxedContext = routePlanner.prepare(data, false, this::canContinueSearch);
        RoutePlanner.RoutingContext strictContext = routePlanner.prepare(data, true, this::canContinueSearch);
        Solution initial = solveDestroyRepair(data, relaxedContext, strictContext);
        return solveExactParentTree(data, initial, relaxedContext, strictContext);
    }

    private Solution solveExactParentTree(ProblemData data, Solution initial) {
        RoutePlanner.RoutingContext relaxedContext = routePlanner.prepare(data, false, this::canContinueSearch);
        RoutePlanner.RoutingContext strictContext = routePlanner.prepare(data, true, this::canContinueSearch);
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
            Coordinate parentPoint = parent.getMetricGeometry().getCoordinate();
            Optional<Route> forcedRoute = routePlanner.plan(start, parentPoint, context, routeMode,
                    routeRequest(flow, start.distance(parentPoint), RoutePlanner.RouteRole.PLANNED_BRANCH));
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
        int existingLimit = Math.min(EXISTING_CANDIDATE_LIMIT, existingCandidates.size());
        for (int i = 0; i < existingLimit; i++) {
            TieInCandidate candidate = existingCandidates.get(i);
            if (candidate.getHostType() == TieInCandidate.HostType.EXISTING_CHAMBER) {
                long incident = edges.stream()
                        .filter(edge -> edge.parentNodeId.equals(candidate.getHostId()))
                        .count();
                if (incident >= MAX_CHAMBER_DEGREE) {
                    continue;
                }
            }
            double lowerBound = candidate.getDistanceFromConnection()
                    * diameterCatalog.cheapestNewConstructionRubPerMeter()
                    + candidateTieCostLowerBound(candidate);
            if (best != null && score(lowerBound, candidate.getDistanceFromConnection()) >= bestScore) {
                break;
            }
            Optional<Route> route = routePlanner.plan(start, candidate.getMetricPoint(), context, routeMode,
                    routeRequest(flow, start.distance(candidate.getMetricPoint()),
                            RoutePlanner.RouteRole.EXISTING_TIE_IN));
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

        // A consumer point is a terminal: shared routes may join only at an exterior junction.
        if (allowSharedParents && !allowJunctions && !forceExistingParent) {
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
                Optional<Route> route = routePlanner.plan(start, parentPoint, context, routeMode,
                        routeRequest(flow, start.distance(parentPoint),
                                RoutePlanner.RouteRole.PLANNED_BRANCH));
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

        if (best == null && !forceExistingParent) {
            for (int i = 0; i < Math.min(3, existingCandidates.size()); i++) {
                TieInCandidate candidate = existingCandidates.get(i);
                if (candidate.getHostType() == TieInCandidate.HostType.EXISTING_CHAMBER) {
                    long incident = edges.stream()
                            .filter(edge -> edge.parentNodeId.equals(candidate.getHostId()))
                            .count();
                    if (incident >= MAX_CHAMBER_DEGREE) {
                        continue;
                    }
                }
                Optional<Route> route = routePlanner.plan(start, candidate.getMetricPoint(), context,
                        RoutePlanner.RouteMode.LONG_RANGE,
                        routeRequest(flow, start.distance(candidate.getMetricPoint()),
                                RoutePlanner.RouteRole.EXISTING_TIE_IN));
                if (route.isPresent()) {
                    best = TreeEdge.toExisting(connection, candidate, route.get());
                    solution.addDiagnostic("long-range fallback used for " + connection.getId());
                    break;
                }
            }
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
        if (best == null) {
            solution.addUnconnected(connection.getId(), penalty(flow), "no route to existing network or exterior junction found");
            return;
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
        if (regular != null) {
            regularTree.add(regular);
        }
        double regularScore = regular == null
                ? Double.POSITIVE_INFINITY
                : score(estimateTreeCost(regularTree), totalLength(regularTree));
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
            double flow = connection.getDouble("flow_tph", 0.0);
            Optional<Route> branch = routePlanner.plan(start, junction, context, routeMode,
                    routeRequest(flow, start.distance(junction), RoutePlanner.RouteRole.JUNCTION_BRANCH));
            if (!branch.isPresent() || branch.get().getLengthMeters() < 0.25) {
                stats.reject("NO_BRANCH_ROUTE");
                continue;
            }
            List<TreeEdge> trial = splitAndAttach(edges, host, connection, junction, index, branch.get());
            if (trial == null) {
                stats.reject("SPLIT_FAILED");
                continue;
            }
            if (!hasAllowedTreeTransitions(trial)) {
                stats.reject("TURN_ANGLE");
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

    private boolean hasAllowedTreeTransitions(List<TreeEdge> edges) {
        Map<String, List<TreeEdge>> incoming = new HashMap<>();
        Map<String, List<TreeEdge>> outgoing = new HashMap<>();
        for (TreeEdge edge : edges) {
            incoming.computeIfAbsent(edge.parentNodeId, ignored -> new ArrayList<>()).add(edge);
            outgoing.computeIfAbsent(edge.childNodeId, ignored -> new ArrayList<>()).add(edge);
        }
        for (String nodeId : incoming.keySet()) {
            List<TreeEdge> nextEdges = outgoing.get(nodeId);
            if (nextEdges == null) {
                continue;
            }
            if (nextEdges.stream().anyMatch(edge -> !edge.syntheticJunction)) {
                return false;
            }
            for (TreeEdge before : incoming.get(nodeId)) {
                for (TreeEdge after : nextEdges) {
                    if (!routePlanner.hasAllowedTransition(
                            before.route.getMetricGeometry(), after.route.getMetricGeometry())) {
                        return false;
                    }
                }
            }
        }
        return true;
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
        trial.add(new TreeEdge(host.child, junctionId, routeSlice(host.route, downstream), null,
                host.syntheticJunction));
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
        return mergeCoincidentRootTails(edges).stream()
                .mapToDouble(edge -> edge.route.getLengthMeters())
                .sum();
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
            if (!canAcceptExistingCandidate(candidate, edges)) {
                continue;
            }
            Optional<Route> route = routePlanner.plan(start, candidate.getMetricPoint(), context,
                    RoutePlanner.RouteMode.CORRIDOR,
                    routeRequest(flow, start.distance(candidate.getMetricPoint()),
                            RoutePlanner.RouteRole.EXISTING_TIE_IN));
            if (route.isPresent()) {
                TreeEdge edge = TreeEdge.toExisting(connection, candidate, route.get());
                scored.add(new ScoredEdge(edge, estimateTreeCostWith(edges, edge)));
            }
        }
        // A consumer is a terminal, never a legal parent. Shared topology is proposed through
        // bestJunctionTree(), which creates an exterior chamber on an existing pipe instead.
        List<InputFeature> plannedParents = Collections.emptyList();
        for (int i = 0; i < Math.min(PLANNED_PARENT_LIMIT, plannedParents.size()); i++) {
            InputFeature parent = plannedParents.get(i);
            if (!canAcceptPlannedChild(parent, edges)) {
                continue;
            }
            Coordinate parentPoint = parent.getMetricGeometry().getCoordinate();
            Optional<Route> route = routePlanner.plan(start, parentPoint, context,
                    RoutePlanner.RouteMode.CORRIDOR,
                    routeRequest(flow, start.distance(parentPoint), RoutePlanner.RouteRole.PLANNED_BRANCH));
            if (route.isPresent()) {
                TreeEdge edge = TreeEdge.toConnection(connection, parent, route.get());
                scored.add(new ScoredEdge(edge, estimateTreeCostWith(edges, edge)));
            }
        }

        if (scored.isEmpty()) {
            for (TieInCandidate candidate : existingCandidates) {
                if (!canAcceptExistingCandidate(candidate, edges)) {
                    continue;
                }
                Optional<Route> route = routePlanner.plan(start, candidate.getMetricPoint(), context,
                        RoutePlanner.RouteMode.LONG_RANGE,
                        routeRequest(flow, start.distance(candidate.getMetricPoint()),
                                RoutePlanner.RouteRole.EXISTING_TIE_IN));
                if (route.isPresent()) {
                    TreeEdge edge = TreeEdge.toExisting(connection, candidate, route.get());
                    scored.add(new ScoredEdge(edge, estimateTreeCostWith(edges, edge)));
                }
            }
            for (InputFeature parent : plannedParents) {
                if (!canAcceptPlannedChild(parent, edges)) {
                    continue;
                }
                Coordinate parentPoint = parent.getMetricGeometry().getCoordinate();
                Optional<Route> route = routePlanner.plan(start, parentPoint, context,
                        RoutePlanner.RouteMode.LONG_RANGE,
                        routeRequest(flow, start.distance(parentPoint),
                                RoutePlanner.RouteRole.PLANNED_BRANCH));
                if (route.isPresent()) {
                    TreeEdge edge = TreeEdge.toConnection(connection, parent, route.get());
                    scored.add(new ScoredEdge(edge, estimateTreeCostWith(edges, edge)));
                }
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

    private boolean canAcceptExistingCandidate(TieInCandidate candidate, List<TreeEdge> edges) {
        if (candidate.getHostType() != TieInCandidate.HostType.EXISTING_CHAMBER) {
            return true;
        }
        long incident = edges.stream()
                .filter(edge -> edge.parentNodeId.equals(candidate.getHostId()))
                .count();
        return incident < MAX_CHAMBER_DEGREE;
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
        return estimateTreeCostRaw(mergeCoincidentRootTails(candidateEdges));
    }

    private double estimateTreeCostRaw(List<TreeEdge> candidateEdges) {
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
        return branchChamberSpecs(childrenByParent, outgoingByChild).stream()
                .mapToDouble(spec -> diameterCatalog.chamberCost(spec.diameter))
                .sum();
    }

    private void emitTree(List<TreeEdge> edges, Solution solution,
                          RoutePlanner.RoutingContext context) {
        int originalEdgeCount = edges.size();
        edges = mergeCoincidentRootTails(edges);
        if (edges.size() > originalEdgeCount) {
            solution.addDiagnostic("coincident_root_tails_merged="
                    + (edges.size() - originalEdgeCount));
        }
        Map<String, List<TreeEdge>> childrenByParent = new HashMap<>();
        Map<String, TreeEdge> outgoingByChild = new HashMap<>();
        for (TreeEdge edge : edges) {
            childrenByParent.computeIfAbsent(edge.parentNodeId, ignored -> new ArrayList<>()).add(edge);
            outgoingByChild.put(edge.childNodeId, edge);
        }
        int relocatedSharedJunctions = relocateUnsafeSharedJunctions(
                edges, childrenByParent, context);
        if (relocatedSharedJunctions > 0) {
            solution.addDiagnostic("aggregate_diameter_shared_junctions_relocated="
                    + relocatedSharedJunctions);
            childrenByParent.clear();
            outgoingByChild.clear();
            for (TreeEdge edge : edges) {
                childrenByParent.computeIfAbsent(edge.parentNodeId,
                        ignored -> new ArrayList<>()).add(edge);
                outgoingByChild.put(edge.childNodeId, edge);
            }
        }
        int reroutedEdges = rerouteEdgesForAggregateDiameter(
                edges, childrenByParent, context);
        if (reroutedEdges > 0) {
            solution.addDiagnostic("aggregate_diameter_edges_rerouted=" + reroutedEdges);
        }
        edges.stream()
                .sorted(Comparator.comparingDouble((TreeEdge edge) -> edge.child.getDouble("flow_tph", 0)).reversed()
                        .thenComparing(edge -> edge.child.getId()))
                .forEach(edge -> emitEdge(edge, aggregateFlow(edge, childrenByParent), solution));
        emitBranchChambers(childrenByParent, outgoingByChild, solution);
    }

    private int relocateUnsafeSharedJunctions(
            List<TreeEdge> edges, Map<String, List<TreeEdge>> childrenByParent,
            RoutePlanner.RoutingContext context) {
        int relocated = 0;
        List<TreeEdge> sharedEdges = edges.stream().filter(edge -> edge.syntheticJunction)
                .collect(java.util.stream.Collectors.toList());
        for (TreeEdge outgoing : sharedEdges) {
            double outgoingFlow = aggregateFlow(outgoing, childrenByParent);
            int outgoingDiameter = diameterCatalog.select(
                    outgoingFlow, outgoing.route.getLengthMeters()).getDiameter();
            if (routePlanner.avoidsForbiddenRestrictions(
                    outgoing.route.getMetricGeometry(), context, outgoingDiameter)) {
                continue;
            }
            List<TreeEdge> incoming = new ArrayList<>(childrenByParent.getOrDefault(
                    outgoing.childNodeId, Collections.emptyList()));
            if (incoming.size() < 2) {
                continue;
            }
            Coordinate origin = outgoing.route.getMetricGeometry().getCoordinateN(0);
            boolean moved = false;
            double[] radii = {0.20, 0.35, 0.50, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0};
            for (double radius : radii) {
                for (int bearing = 0; bearing < 32; bearing++) {
                    double angle = 2.0 * Math.PI * bearing / 32.0;
                    Coordinate candidate = new Coordinate(
                            origin.x + radius * Math.cos(angle),
                            origin.y + radius * Math.sin(angle));
                    Route adjustedOutgoing = routeWithEndpoint(
                            outgoing.route, candidate, true);
                    if (!routePlanner.avoidsForbiddenRestrictions(
                            adjustedOutgoing.getMetricGeometry(), context, outgoingDiameter)
                            || !routePlanner.hasAllowedTurns(adjustedOutgoing.getMetricGeometry())) {
                        continue;
                    }
                    List<Route> adjustedIncoming = new ArrayList<>();
                    boolean valid = true;
                    for (TreeEdge edge : incoming) {
                        Route adjusted = routeWithEndpoint(edge.route, candidate, false);
                        int diameter = diameterCatalog.select(aggregateFlow(edge, childrenByParent),
                                adjusted.getLengthMeters()).getDiameter();
                        if (!routePlanner.avoidsForbiddenRestrictions(
                                adjusted.getMetricGeometry(), context, diameter)
                                || !routePlanner.hasAllowedTurns(adjusted.getMetricGeometry())
                                || !routePlanner.hasAllowedTransition(
                                adjusted.getMetricGeometry(), adjustedOutgoing.getMetricGeometry())) {
                            valid = false;
                            break;
                        }
                        adjustedIncoming.add(adjusted);
                    }
                    if (!valid) {
                        continue;
                    }
                    InputFeature movedJunction = syntheticJunction(
                            outgoing.childNodeId, candidate);
                    replaceEdge(edges, outgoing, new TreeEdge(movedJunction,
                            outgoing.parentNodeId, adjustedOutgoing, outgoing.candidate, true));
                    for (int index = 0; index < incoming.size(); index++) {
                        TreeEdge edge = incoming.get(index);
                        replaceEdge(edges, edge, new TreeEdge(edge.child, edge.parentNodeId,
                                adjustedIncoming.get(index), edge.candidate,
                                edge.syntheticJunction));
                    }
                    relocated++;
                    moved = true;
                    break;
                }
                if (moved) {
                    break;
                }
            }
        }
        return relocated;
    }

    private void replaceEdge(List<TreeEdge> edges, TreeEdge oldEdge, TreeEdge newEdge) {
        int index = edges.indexOf(oldEdge);
        if (index >= 0) {
            edges.set(index, newEdge);
        }
    }

    private Route routeWithEndpoint(Route source, Coordinate endpoint, boolean first) {
        Coordinate[] coordinates = source.getMetricGeometry().getCoordinates();
        Coordinate[] adjusted = Arrays.stream(coordinates).map(Coordinate::new)
                .toArray(Coordinate[]::new);
        adjusted[first ? 0 : adjusted.length - 1] = new Coordinate(endpoint);
        return routeSlice(source, geometryFactory.createLineString(adjusted));
    }

    private int rerouteEdgesForAggregateDiameter(
            List<TreeEdge> edges, Map<String, List<TreeEdge>> childrenByParent,
            RoutePlanner.RoutingContext context) {
        int rerouted = 0;
        for (int index = 0; index < edges.size(); index++) {
            TreeEdge edge = edges.get(index);
            double flow = aggregateFlow(edge, childrenByParent);
            int diameter = diameterCatalog.select(flow, edge.route.getLengthMeters()).getDiameter();
            LineString geometry = edge.route.getMetricGeometry();
            if (routePlanner.avoidsForbiddenRestrictions(geometry, context, diameter)) {
                continue;
            }
            Coordinate start = geometry.getCoordinateN(0);
            Coordinate end = geometry.getCoordinateN(geometry.getNumPoints() - 1);
            RoutePlanner.RouteRole role = edge.candidate == null
                    ? RoutePlanner.RouteRole.JUNCTION_BRANCH
                    : RoutePlanner.RouteRole.EXISTING_TIE_IN;
            Optional<Route> replacement = routePlanner.plan(start, end, context,
                    RoutePlanner.RouteMode.CORRIDOR,
                    new RoutePlanner.RouteRequest(diameter, role));
            if (!replacement.isPresent()) {
                continue;
            }
            TreeEdge updated = new TreeEdge(edge.child, edge.parentNodeId,
                    replacement.get(), edge.candidate, edge.syntheticJunction);
            edges.set(index, updated);
            childrenByParent.get(edge.parentNodeId).remove(edge);
            childrenByParent.get(edge.parentNodeId).add(updated);
            rerouted++;
        }
        return rerouted;
    }

    private List<TreeEdge> mergeCoincidentRootTails(List<TreeEdge> source) {
        List<TreeEdge> result = new ArrayList<>(source);
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int leftIndex = 0; leftIndex < result.size(); leftIndex++) {
                for (int rightIndex = leftIndex + 1; rightIndex < result.size(); rightIndex++) {
                    TreeEdge left = result.get(leftIndex);
                    TreeEdge right = result.get(rightIndex);
                    List<TreeEdge> merged = mergeCoincidentRootTailPair(result, left, right);
                    if (merged != null) {
                        result = merged;
                        changed = true;
                        break outer;
                    }
                }
            }
        }
        return result;
    }

    private List<TreeEdge> mergeCoincidentRootTailPair(List<TreeEdge> edges,
                                                        TreeEdge left, TreeEdge right) {
        if (!compatibleRootParents(left, right)
                || (left.candidate == null) != (right.candidate == null)
                || (left.candidate != null
                && left.candidate.getHostType() != right.candidate.getHostType())
                || Math.abs(left.route.getSpecialCoefficient() - right.route.getSpecialCoefficient()) > 1e-9
                || !Objects.equals(left.route.getLayingMethod(), right.route.getLayingMethod())) {
            return null;
        }
        List<TreeEdge> duplicateMerged = mergeDuplicateSyntheticEdge(edges, left, right);
        if (duplicateMerged != null) {
            return duplicateMerged;
        }
        List<TreeEdge> attached = attachAtSyntheticTailStart(edges, left, right);
        if (attached == null) {
            attached = attachAtSyntheticTailStart(edges, right, left);
        }
        if (attached != null) {
            return attached;
        }
        SharedTail shared = longestSharedTail(left.route.getMetricGeometry(),
                right.route.getMetricGeometry());
        List<TreeEdge> merged = buildSharedTailMerge(edges, left, right, shared);
        if (merged != null) {
            return merged;
        }
        shared = convergingSharedTail(left.route.getMetricGeometry(),
                right.route.getMetricGeometry());
        merged = buildSharedTailMerge(edges, left, right, shared);
        if (merged != null) {
            return merged;
        }
        merged = mergeParallelBranches(edges, left, right);
        return merged != null ? merged : mergeParallelBranches(edges, right, left);
    }

    private List<TreeEdge> attachAtSyntheticTailStart(List<TreeEdge> edges,
                                                       TreeEdge guest, TreeEdge sharedTail) {
        if (!sharedTail.syntheticJunction || sharedTail.candidate == null
                || guest.syntheticJunction) {
            return null;
        }
        LineString guestLine = guest.route.getMetricGeometry();
        LineString sharedLine = sharedTail.route.getMetricGeometry();
        Coordinate junction = sharedLine.getCoordinateN(0);
        LengthIndexedLine guestIndexed = new LengthIndexedLine(guestLine);
        double joinIndex = guestIndexed.project(junction);
        if (joinIndex <= 0.25 || guestLine.getLength() - joinIndex < MIN_SHARED_TAIL_METERS
                || guestIndexed.extractPoint(joinIndex).distance(junction)
                > COINCIDENT_TAIL_TOLERANCE_METERS) {
            return null;
        }
        LineString guestTail = snappedLine(
                guestIndexed.extractLine(joinIndex, guestLine.getLength()), junction, true);
        LineString guestPrefix = snappedLine(
                guestIndexed.extractLine(0.0, joinIndex), junction, false);
        if (guestTail == null || guestPrefix == null
                || guestPrefix.getLength() < 0.25
                || Math.abs(guestTail.getLength() - sharedLine.getLength())
                > COINCIDENT_TAIL_TOLERANCE_METERS * 2.0
                || DiscreteHausdorffDistance.distance(guestTail, sharedLine)
                > COINCIDENT_TAIL_TOLERANCE_METERS) {
            return null;
        }
        List<TreeEdge> trial = new ArrayList<>(edges);
        trial.remove(guest);
        trial.add(new TreeEdge(guest.child, sharedTail.childNodeId,
                routeSlice(guest.route, guestPrefix), null, guest.syntheticJunction));
        return hasAcyclicParentLinks(trial) && hasAllowedTreeTransitions(trial)
                ? trial : null;
    }

    private boolean compatibleRootParents(TreeEdge left, TreeEdge right) {
        if (left.parentNodeId.equals(right.parentNodeId)) {
            return true;
        }
        if (left.candidate == null || right.candidate == null) {
            return false;
        }
        return left.candidate.getHostType() == right.candidate.getHostType()
                && Objects.equals(left.candidate.getHostId(), right.candidate.getHostId())
                && left.candidate.getMetricPoint().distance(right.candidate.getMetricPoint())
                <= COINCIDENT_TAIL_TOLERANCE_METERS;
    }

    private List<TreeEdge> mergeParallelBranches(List<TreeEdge> edges,
                                                  TreeEdge guest, TreeEdge host) {
        LineString guestLine = guest.route.getMetricGeometry();
        LineString hostLine = host.route.getMetricGeometry();
        Coordinate[] guestCoordinates = guestLine.getCoordinates();
        Coordinate[] hostCoordinates = hostLine.getCoordinates();
        double guestOffset = 0.0;
        for (int guestIndex = 0; guestIndex < guestCoordinates.length - 1; guestIndex++) {
            Coordinate guestStart = guestCoordinates[guestIndex];
            Coordinate guestEnd = guestCoordinates[guestIndex + 1];
            double guestSegmentLength = guestStart.distance(guestEnd);
            for (int hostIndex = 0; hostIndex < hostCoordinates.length - 1; hostIndex++) {
                Coordinate hostStart = hostCoordinates[hostIndex];
                Coordinate hostEnd = hostCoordinates[hostIndex + 1];
                if (nearCollinearOverlapLength(guestStart, guestEnd, hostStart, hostEnd)
                        <= MIN_SHARED_TAIL_METERS) {
                    continue;
                }
                double overlapStart = overlapStartOnSegment(
                        guestStart, guestEnd, hostStart, hostEnd);
                double joinIndex = Math.max(0.0,
                        guestOffset + overlapStart - MIN_SHARED_TAIL_METERS - 0.5);
                LengthIndexedLine guestIndexed = new LengthIndexedLine(guestLine);
                LengthIndexedLine hostIndexed = new LengthIndexedLine(hostLine);
                Coordinate approach = guestIndexed.extractPoint(joinIndex);
                double hostJoinIndex = hostIndexed.project(approach);
                if (hostJoinIndex <= 0.25 || hostLine.getLength() - hostJoinIndex <= 0.25) {
                    continue;
                }
                Coordinate junction = hostIndexed.extractPoint(hostJoinIndex);
                LineString guestBranch = branchToJunction(guestLine, joinIndex, junction);
                LineString hostPrefix = asLine(hostIndexed.extractLine(0.0, hostJoinIndex));
                LineString commonTail = asLine(hostIndexed.extractLine(
                        hostJoinIndex, hostLine.getLength()));
                if (guestBranch == null || hostPrefix == null || commonTail == null
                        || guestBranch.getLength() < 0.25
                        || hostPrefix.getLength() < 0.25
                        || commonTail.getLength() < 0.25) {
                    continue;
                }
                String junctionId = "ch_parallel_" + safeId(guest.childNodeId)
                        + "_" + safeId(host.childNodeId);
                InputFeature junctionFeature = syntheticJunction(junctionId, junction);
                List<TreeEdge> trial = new ArrayList<>(edges);
                trial.remove(guest);
                trial.remove(host);
                trial.add(new TreeEdge(guest.child, junctionId,
                        routeSlice(guest.route, guestBranch), null, guest.syntheticJunction));
                trial.add(new TreeEdge(host.child, junctionId,
                        routeSlice(host.route, hostPrefix), null, host.syntheticJunction));
                trial.add(new TreeEdge(junctionFeature, host.parentNodeId,
                        routeSlice(host.route, commonTail), host.candidate, true));
                if (hasAcyclicParentLinks(trial) && hasAllowedTreeTransitions(trial)) {
                    return trial;
                }
            }
            guestOffset += guestSegmentLength;
        }
        return null;
    }

    private double overlapStartOnSegment(Coordinate a, Coordinate b,
                                         Coordinate c, Coordinate d) {
        double length = a.distance(b);
        if (length < 1e-9) {
            return 0.0;
        }
        double unitX = (b.x - a.x) / length;
        double unitY = (b.y - a.y) / length;
        double projectionC = (c.x - a.x) * unitX + (c.y - a.y) * unitY;
        double projectionD = (d.x - a.x) * unitX + (d.y - a.y) * unitY;
        return Math.max(0.0, Math.min(projectionC, projectionD));
    }

    private LineString branchToJunction(LineString source, double sourceIndex,
                                        Coordinate junction) {
        LengthIndexedLine indexed = new LengthIndexedLine(source);
        if (sourceIndex <= 0.01) {
            Coordinate start = source.getCoordinateN(0);
            if (start.distance(junction) < 0.01) {
                return null;
            }
            return geometryFactory.createLineString(new Coordinate[]{
                    new Coordinate(start), new Coordinate(junction)});
        }
        return appendJunction(indexed.extractLine(0.0, sourceIndex),
                indexed.extractPoint(sourceIndex), junction);
    }

    private List<TreeEdge> buildSharedTailMerge(List<TreeEdge> edges,
                                                 TreeEdge left, TreeEdge right,
                                                 SharedTail shared) {
        if (shared == null) {
            return null;
        }
        String junctionId = "ch_merge_" + safeId(left.childNodeId) + "_" + safeId(right.childNodeId);
        InputFeature junction = syntheticJunction(junctionId, shared.junction);
        List<TreeEdge> trial = new ArrayList<>(edges);
        trial.remove(left);
        trial.remove(right);
        trial.add(new TreeEdge(left.child, junctionId,
                routeSlice(left.route, shared.leftPrefix), null, left.syntheticJunction));
        trial.add(new TreeEdge(right.child, junctionId,
                routeSlice(right.route, shared.rightPrefix), null, right.syntheticJunction));
        trial.add(new TreeEdge(junction, left.parentNodeId,
                routeSlice(left.route, shared.commonTail), left.candidate, true));
        if (!hasAcyclicParentLinks(trial) || !hasAllowedTreeTransitions(trial)) {
            return null;
        }
        return trial;
    }

    private SharedTail convergingSharedTail(LineString left, LineString right) {
        Coordinate leftEnd = left.getCoordinateN(left.getNumPoints() - 1);
        Coordinate rightEnd = right.getCoordinateN(right.getNumPoints() - 1);
        if (leftEnd.distance(rightEnd) > COINCIDENT_TAIL_TOLERANCE_METERS) {
            return null;
        }
        Coordinate leftBefore = left.getCoordinateN(left.getNumPoints() - 2);
        Coordinate rightBefore = right.getCoordinateN(right.getNumPoints() - 2);
        double leftLastLength = leftBefore.distance(leftEnd);
        double rightLastLength = rightBefore.distance(rightEnd);
        if (leftLastLength < MIN_SHARED_TAIL_METERS || rightLastLength < MIN_SHARED_TAIL_METERS) {
            return null;
        }
        double leftUx = (leftBefore.x - leftEnd.x) / leftLastLength;
        double leftUy = (leftBefore.y - leftEnd.y) / leftLastLength;
        double rightUx = (rightBefore.x - rightEnd.x) / rightLastLength;
        double rightUy = (rightBefore.y - rightEnd.y) / rightLastLength;
        double directionDelta = Math.hypot(leftUx - rightUx, leftUy - rightUy);
        double sharedLength = directionDelta < 1e-9
                ? Math.min(leftLastLength, rightLastLength)
                : COINCIDENT_TAIL_TOLERANCE_METERS / directionDelta;
        sharedLength = Math.min(sharedLength, Math.min(leftLastLength, rightLastLength));
        sharedLength -= 0.01;
        if (sharedLength < MIN_SHARED_TAIL_METERS) {
            return null;
        }
        Coordinate leftJunction = new Coordinate(
                leftEnd.x + leftUx * sharedLength,
                leftEnd.y + leftUy * sharedLength);
        Coordinate rightJunction = new Coordinate(
                rightEnd.x + rightUx * sharedLength,
                rightEnd.y + rightUy * sharedLength);
        if (leftJunction.distance(rightJunction) > COINCIDENT_TAIL_TOLERANCE_METERS) {
            return null;
        }
        double longitudinalAdvance = Math.min(0.5, sharedLength / 4.0);
        Coordinate junction = new Coordinate(
                leftJunction.x - leftUx * longitudinalAdvance,
                leftJunction.y - leftUy * longitudinalAdvance);
        Coordinate end = new Coordinate(
                (leftEnd.x + rightEnd.x) / 2.0,
                (leftEnd.y + rightEnd.y) / 2.0);
        LengthIndexedLine leftIndexed = new LengthIndexedLine(left);
        LengthIndexedLine rightIndexed = new LengthIndexedLine(right);
        LineString leftPrefix = snappedLine(
                leftIndexed.extractLine(0.0,
                        left.getLength() - sharedLength + longitudinalAdvance), junction, false);
        LineString rightPrefix = appendJunction(
                rightIndexed.extractLine(0.0, right.getLength() - sharedLength),
                rightJunction, junction);
        LineString commonTail = geometryFactory.createLineString(
                new Coordinate[]{junction, end});
        if (leftPrefix == null || rightPrefix == null
                || leftPrefix.getLength() < 0.25 || rightPrefix.getLength() < 0.25) {
            return null;
        }
        return new SharedTail(junction, leftPrefix, rightPrefix, commonTail);
    }

    private LineString appendJunction(org.locationtech.jts.geom.Geometry geometry,
                                      Coordinate approach, Coordinate junction) {
        LineString line = asLine(geometry);
        if (line == null || line.getNumPoints() < 2) {
            return null;
        }
        Coordinate[] source = line.getCoordinates();
        List<Coordinate> coordinates = new ArrayList<>(Arrays.asList(source));
        coordinates.set(coordinates.size() - 1, new Coordinate(approach));
        if (approach.distance(junction) > 0.01) {
            coordinates.add(new Coordinate(junction));
        }
        return geometryFactory.createLineString(coordinates.toArray(new Coordinate[0]));
    }

    private List<TreeEdge> mergeDuplicateSyntheticEdge(List<TreeEdge> edges,
                                                        TreeEdge left, TreeEdge right) {
        LineString leftLine = left.route.getMetricGeometry();
        LineString rightLine = right.route.getMetricGeometry();
        if (!left.syntheticJunction || !right.syntheticJunction
                || leftLine.getCoordinateN(0).distance(rightLine.getCoordinateN(0))
                > COINCIDENT_TAIL_TOLERANCE_METERS
                || leftLine.getCoordinateN(leftLine.getNumPoints() - 1).distance(
                rightLine.getCoordinateN(rightLine.getNumPoints() - 1))
                > COINCIDENT_TAIL_TOLERANCE_METERS
                || Math.abs(leftLine.getLength() - rightLine.getLength())
                > COINCIDENT_TAIL_TOLERANCE_METERS * 2.0
                || DiscreteHausdorffDistance.distance(leftLine, rightLine)
                > COINCIDENT_TAIL_TOLERANCE_METERS) {
            return null;
        }
        String canonicalNodeId = left.childNodeId.compareTo(right.childNodeId) <= 0
                ? left.childNodeId : right.childNodeId;
        TreeEdge canonical = canonicalNodeId.equals(left.childNodeId) ? left : right;
        String removedNodeId = canonical == left ? right.childNodeId : left.childNodeId;
        List<TreeEdge> result = new ArrayList<>();
        for (TreeEdge edge : edges) {
            if (edge == left || edge == right) {
                continue;
            }
            if (edge.parentNodeId.equals(removedNodeId)) {
                result.add(new TreeEdge(edge.child, canonicalNodeId, edge.route,
                        edge.candidate, edge.syntheticJunction));
            } else {
                result.add(edge);
            }
        }
        result.add(canonical);
        return hasAcyclicParentLinks(result) && hasAllowedTreeTransitions(result) ? result : null;
    }

    private boolean hasAcyclicParentLinks(List<TreeEdge> edges) {
        Map<String, String> parentByChild = new HashMap<>();
        for (TreeEdge edge : edges) {
            String previous = parentByChild.put(edge.childNodeId, edge.parentNodeId);
            if (previous != null && !previous.equals(edge.parentNodeId)) {
                return false;
            }
        }
        for (String start : parentByChild.keySet()) {
            Set<String> path = new HashSet<>();
            String node = start;
            while (parentByChild.containsKey(node)) {
                if (!path.add(node)) {
                    return false;
                }
                node = parentByChild.get(node);
            }
        }
        return true;
    }

    private SharedTail longestSharedTail(LineString left, LineString right) {
        LengthIndexedLine leftIndexed = new LengthIndexedLine(left);
        LengthIndexedLine rightIndexed = new LengthIndexedLine(right);
        List<Coordinate> probes = new ArrayList<>();
        Coordinate[] leftCoordinates = left.getCoordinates();
        Coordinate[] rightCoordinates = right.getCoordinates();
        for (int index = 0; index < leftCoordinates.length - 1; index++) {
            probes.add(leftCoordinates[index]);
        }
        for (int index = 0; index < rightCoordinates.length - 1; index++) {
            probes.add(rightCoordinates[index]);
        }
        SharedTail best = null;
        for (Coordinate probe : probes) {
            double leftIndex = leftIndexed.project(probe);
            double rightIndex = rightIndexed.project(probe);
            Coordinate leftPoint = leftIndexed.extractPoint(leftIndex);
            Coordinate rightPoint = rightIndexed.extractPoint(rightIndex);
            if (leftPoint.distance(rightPoint) > COINCIDENT_TAIL_TOLERANCE_METERS) {
                continue;
            }
            Coordinate junction = new Coordinate(
                    (leftPoint.x + rightPoint.x) / 2.0,
                    (leftPoint.y + rightPoint.y) / 2.0);
            LineString leftPrefix = snappedLine(leftIndexed.extractLine(0.0, leftIndex), junction, false);
            LineString rightPrefix = snappedLine(rightIndexed.extractLine(0.0, rightIndex), junction, false);
            LineString leftTail = snappedLine(
                    leftIndexed.extractLine(leftIndex, left.getLength()), junction, true);
            LineString rightTail = snappedLine(
                    rightIndexed.extractLine(rightIndex, right.getLength()), junction, true);
            if (leftPrefix == null || rightPrefix == null || leftTail == null || rightTail == null
                    || leftPrefix.getLength() < 0.25 || rightPrefix.getLength() < 0.25
                    || leftTail.getLength() < MIN_SHARED_TAIL_METERS
                    || Math.abs(leftTail.getLength() - rightTail.getLength())
                    > COINCIDENT_TAIL_TOLERANCE_METERS * 2.0
                    || DiscreteHausdorffDistance.distance(leftTail, rightTail)
                    > COINCIDENT_TAIL_TOLERANCE_METERS) {
                continue;
            }
            if (best == null || leftTail.getLength() > best.commonTail.getLength()) {
                best = new SharedTail(junction, leftPrefix, rightPrefix, leftTail);
            }
        }
        return best;
    }

    private LineString snappedLine(org.locationtech.jts.geom.Geometry geometry,
                                   Coordinate junction, boolean snapStart) {
        LineString line = asLine(geometry);
        if (line == null || line.getNumPoints() < 2) {
            return null;
        }
        Coordinate[] coordinates = line.getCoordinates();
        coordinates[snapStart ? 0 : coordinates.length - 1] = new Coordinate(junction);
        return geometryFactory.createLineString(coordinates);
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
            if (outgoing.syntheticJunction) {
                solution.addTechnicalNode(new TechnicalNodeOutput(nodeId, VARIANT_ID, point));
            }
        }
        for (BranchChamberSpec spec : branchChamberSpecs(childrenByParent, outgoingByChild)) {
            Point point = geometryFactory.createPoint(spec.coordinate);
            solution.addChamber(new ChamberOutput(spec.id, VARIANT_ID,
                    point, spec.diameter, diameterCatalog.chamberCost(spec.diameter)));
        }
    }

    private List<BranchChamberSpec> branchChamberSpecs(
            Map<String, List<TreeEdge>> childrenByParent,
            Map<String, TreeEdge> outgoingByChild) {
        List<BranchChamberSpec> specs = new ArrayList<>();
        childrenByParent.keySet().stream().sorted().forEach(nodeId -> {
            TreeEdge outgoing = outgoingByChild.get(nodeId);
            if (outgoing == null) {
                return;
            }
            Coordinate coordinate = outgoing.child.getMetricGeometry().getCoordinate();
            int diameter = branchChamberDiameter(nodeId, childrenByParent, outgoingByChild);
            BranchChamberSpec coincident = specs.stream()
                    .filter(spec -> spec.coordinate.distance(coordinate) <= 0.25)
                    .findFirst()
                    .orElse(null);
            if (coincident == null) {
                specs.add(new BranchChamberSpec("ch_branch_" + nodeId,
                        new Coordinate(coordinate), diameter));
            } else {
                coincident.diameter = Math.max(coincident.diameter, diameter);
            }
        });
        return specs;
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

    private RoutePlanner.RouteRequest routeRequest(double flowTph, double estimatedLengthMeters,
                                                    RoutePlanner.RouteRole role) {
        int diameter = diameterCatalog.select(flowTph, Math.max(0.0, estimatedLengthMeters)).getDiameter();
        return new RoutePlanner.RouteRequest(diameter, role);
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

    private static class BranchChamberSpec {
        private final String id;
        private final Coordinate coordinate;
        private int diameter;

        private BranchChamberSpec(String id, Coordinate coordinate, int diameter) {
            this.id = id;
            this.coordinate = coordinate;
            this.diameter = diameter;
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
        private final RoutePlanner.RouteMode routeMode;
        private final Map<String, Integer> splitGroups;
        private final String label;

        private ConcretePlan(List<InputFeature> order, Map<String, String> forcedParents,
                             boolean allowJunctions, String label) {
            this(order, forcedParents, allowJunctions, RoutePlanner.RouteMode.CORRIDOR,
                    Collections.emptyMap(), label);
        }

        private ConcretePlan(List<InputFeature> order, Map<String, String> forcedParents,
                             boolean allowJunctions, RoutePlanner.RouteMode routeMode,
                             Map<String, Integer> splitGroups, String label) {
            this.order = new ArrayList<>(order);
            this.forcedParents = new LinkedHashMap<>(forcedParents);
            this.allowJunctions = allowJunctions;
            this.routeMode = routeMode;
            this.splitGroups = new LinkedHashMap<>(splitGroups);
            this.label = label;
        }

        private String signature() {
            String orderedIds = order.stream().map(InputFeature::getId)
                    .collect(java.util.stream.Collectors.joining(">"));
            String parents = forcedParents.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "->" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
            String groups = splitGroups.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
            return orderedIds + "|parents=" + parents + "|junctions=" + allowJunctions
                    + "|mode=" + routeMode + "|groups=" + groups;
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
                            nodes.get(right).coordinate(), context, RoutePlanner.RouteMode.CORRIDOR,
                            new RoutePlanner.RouteRequest(0, RoutePlanner.RouteRole.EXACT_GRAPH));
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
                            context, RoutePlanner.RouteMode.CORRIDOR,
                            routeRequest(flowByMask[all],
                                    nodes.get(node).coordinate().distance(root.getMetricPoint()),
                                    RoutePlanner.RouteRole.EXACT_GRAPH));
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
            emitTree(normalized, candidate, context);
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

    private static final class PortfolioCandidate {
        private final String label;
        private final Solution solution;

        private PortfolioCandidate(String label, Solution solution) {
            this.label = label;
            this.solution = solution;
        }
    }

    private static final class SharedTail {
        private final Coordinate junction;
        private final LineString leftPrefix;
        private final LineString rightPrefix;
        private final LineString commonTail;

        private SharedTail(Coordinate junction, LineString leftPrefix,
                           LineString rightPrefix, LineString commonTail) {
            this.junction = junction;
            this.leftPrefix = leftPrefix;
            this.rightPrefix = rightPrefix;
            this.commonTail = commonTail;
        }
    }
}
