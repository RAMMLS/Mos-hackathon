package ru.moshackathon.heatnetwork.solver;

import java.util.Arrays;
import java.util.List;

/** Numeric horizontal-clearance rules from the case technical appendix. */
final class ClearanceRules {
    static final String VERSION = "clearance-rules-v1";
    static final double NUMERIC_TOLERANCE_METERS = 0.01;
    static final double MAX_REQUIRED_CLEARANCE_METERS = 10.725;

    private static final List<Integer> DIAMETERS = Arrays.asList(
            50, 65, 80, 100, 125, 150, 200, 250, 300, 400,
            500, 600, 700, 800, 900, 1000, 1200, 1400);
    private static final List<Double> PAIR_WIDTHS = Arrays.asList(
            0.400, 0.430, 0.470, 0.510, 0.600, 0.650, 0.880, 1.050,
            1.150, 1.370, 1.670, 1.850, 2.050, 2.250, 2.450, 2.650, 3.100, 3.450);

    private ClearanceRules() {
    }

    static double pairWidth(int diameter) {
        int effective = diameter <= 0 ? 50 : diameter;
        for (int i = 0; i < DIAMETERS.size(); i++) {
            if (effective <= DIAMETERS.get(i)) {
                return PAIR_WIDTHS.get(i);
            }
        }
        return PAIR_WIDTHS.get(PAIR_WIDTHS.size() - 1);
    }

    static double requiredCenterlineClearance(String type, int newDiameter, int existingDiameter) {
        double base;
        switch (type) {
            case "oks":
                int effective = newDiameter <= 0 ? 50 : newDiameter;
                base = effective < 500 ? 5.0 : effective < 900 ? 7.0 : 9.0;
                break;
            case "road":
            case "tram_tracks":
                base = 1.5;
                break;
            case "gas_pipeline":
            case "power_cable":
                base = 2.0;
                break;
            case "heat_network":
            case "park":
            case "social_area":
            case "prohibited_site":
            case "water":
            case "railway":
                base = 1.0;
                break;
            default:
                return pairWidth(newDiameter) / 2.0;
        }
        double existingHalfWidth = 0.0;
        if ("gas_pipeline".equals(type)) {
            existingHalfWidth = 0.20;
        } else if ("power_cable".equals(type)) {
            existingHalfWidth = 0.10;
        } else if ("heat_network".equals(type)) {
            existingHalfWidth = pairWidth(existingDiameter) / 2.0;
        }
        return base + pairWidth(newDiameter) / 2.0 + existingHalfWidth;
    }
}
