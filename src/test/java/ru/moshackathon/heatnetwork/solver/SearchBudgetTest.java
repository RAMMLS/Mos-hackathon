package ru.moshackathon.heatnetwork.solver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchBudgetTest {
    @Test
    void childCannotOutliveParentDeadline() throws InterruptedException {
        SearchBudget parent = SearchBudget.ofMillis(20);
        SearchBudget child = parent.child(10_000);

        assertTrue(child.canContinue());
        Thread.sleep(30);
        assertFalse(parent.canContinue());
        assertFalse(child.canContinue());
    }
}
