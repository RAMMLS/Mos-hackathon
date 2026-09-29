package ru.moshackathon.heatnetwork.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolutionCoverageStatusTest {
    @Test
    void distinguishesFullPartialAndUncertifiedOutcomes() {
        Solution full = new Solution("full");
        full.setOutcome(3, true);
        assertEquals("FULL", full.getSolutionStatus());
        assertTrue(full.isComplete());
        assertEquals(100.0, full.getCoveragePercent());

        Solution partial = new Solution("partial");
        partial.addUnconnected("cp-2", 1.0, "test");
        partial.setOutcome(3, true);
        assertEquals("PARTIAL", partial.getSolutionStatus());
        assertFalse(partial.isComplete());
        assertEquals(2, partial.getConnectedConnectionPointCount());
        assertEquals(66.666666, partial.getCoveragePercent(), 1e-6);

        Solution zeroOfThree = new Solution("zero-of-three");
        zeroOfThree.addUnconnected("cp-1", 1.0, "test");
        zeroOfThree.addUnconnected("cp-2", 1.0, "test");
        zeroOfThree.addUnconnected("cp-3", 1.0, "test");
        zeroOfThree.setOutcome(3, true);
        assertEquals("PARTIAL", zeroOfThree.getSolutionStatus());
        assertEquals(0, zeroOfThree.getConnectedConnectionPointCount());

        Solution diagnostic = partial.snapshot();
        diagnostic.setOutcome(3, false);
        assertEquals("NO_CERTIFIED_SOLUTION", diagnostic.getSolutionStatus());
        assertFalse(diagnostic.isComplete());
    }
}
