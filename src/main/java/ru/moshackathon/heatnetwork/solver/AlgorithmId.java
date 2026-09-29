package ru.moshackathon.heatnetwork.solver;

import java.util.Arrays;
import java.util.Locale;

public enum AlgorithmId {
    PORTFOLIO("PORTFOLIO", "production portfolio", "IMPLEMENTED"),
    FIRST_FULL("FIRST-FULL", "coverage-first controller with explicit feasibility diagnostics", "IMPLEMENTED"),
    B0_GRID("B0-GRID", "independent connections on the 8-neighbour grid", "IMPLEMENTED"),
    B0_CORRIDOR("B0-CORRIDOR", "independent connections with corridor/dogleg routing", "IMPLEMENTED_LITE"),
    B1("B1", "regret-2 insertion into the new network", "IMPLEMENTED_LITE"),
    B2_U("B2-U", "unweighted medoid backbone seed", "IMPLEMENTED_LITE"),
    B2_Q("B2-Q", "flow-weighted medoid backbone seed", "IMPLEMENTED_LITE"),
    B2_C("B2-C", "construction-rate-weighted medoid backbone seed", "IMPLEMENTED_LITE"),
    B2_C_J("B2-C-J", "B2-C with transactional junctions inside new pipes", "IMPLEMENTED_EXPERIMENTAL"),
    B3("B3", "deterministic destroy/repair order search", "IMPLEMENTED_LITE"),
    R1_PILOT("R1-PILOT", "trained masked-action PPO pilot", "PILOT_NOT_PRODUCTION"),
    R1("R1", "trained PPO policy over concrete parameterized network actions", "IMPLEMENTED_EXPERIMENTAL"),
    R2("R2", "learned ALNS destroy/repair operator controller", "IMPLEMENTED_EXPERIMENTAL"),
    X0("X0", "exhaustive small-scene ordering control", "IMPLEMENTED_LIMITED"),
    X1("X1", "exhaustive small-scene rooted parent-tree oracle", "IMPLEMENTED_LIMITED"),
    X2("X2", "bounded Steiner graph dynamic program with free junction candidates", "IMPLEMENTED_EXPERIMENTAL");

    private final String externalName;
    private final String description;
    private final String status;

    AlgorithmId(String externalName, String description, String status) {
        this.externalName = externalName;
        this.description = description;
        this.status = status;
    }

    public String getExternalName() {
        return externalName;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public boolean isRunnable() {
        return true;
    }

    public static AlgorithmId parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return PORTFOLIO;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(item -> item.externalName.equals(normalized)
                        || item.name().replace('_', '-').equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown algorithm: " + value));
    }
}
