package ru.moshackathon.heatnetwork.solver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClearanceRulesTest {
    @Test
    void oksClearanceIncludesPairWidthAndDiameterSteps() {
        assertEquals(5.255, ClearanceRules.requiredCenterlineClearance("oks", 100, 0), 1e-9);
        assertEquals(5.300, ClearanceRules.requiredCenterlineClearance("oks", 125, 0), 1e-9);
        assertEquals(5.685, ClearanceRules.requiredCenterlineClearance("oks", 400, 0), 1e-9);
        assertEquals(7.835, ClearanceRules.requiredCenterlineClearance("oks", 500, 0), 1e-9);
        assertEquals(8.125, ClearanceRules.requiredCenterlineClearance("oks", 800, 0), 1e-9);
        assertEquals(10.225, ClearanceRules.requiredCenterlineClearance("oks", 900, 0), 1e-9);
    }

    @Test
    void linearUtilitiesIncludeBothCalculatedHalfWidths() {
        assertEquals(2.455,
                ClearanceRules.requiredCenterlineClearance("gas_pipeline", 100, 0), 1e-9);
        assertEquals(2.355,
                ClearanceRules.requiredCenterlineClearance("power_cable", 100, 0), 1e-9);
        assertEquals(2.090,
                ClearanceRules.requiredCenterlineClearance("heat_network", 100, 500), 1e-9);
    }
}
