package ru.moshackathon.heatnetwork.solver;

import java.util.concurrent.TimeUnit;

final class SearchBudget {
    private final long startedNanos;
    private final long deadlineNanos;

    private SearchBudget(long budgetMillis) {
        this(System.nanoTime(), budgetMillis);
    }

    private SearchBudget(long startedNanos, long budgetMillis) {
        this.startedNanos = startedNanos;
        this.deadlineNanos = budgetMillis <= 0
                ? Long.MAX_VALUE
                : startedNanos + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    }

    private SearchBudget(long startedNanos, long deadlineNanos, boolean absoluteDeadline) {
        this.startedNanos = startedNanos;
        this.deadlineNanos = deadlineNanos;
    }

    static SearchBudget ofMillis(long budgetMillis) {
        return new SearchBudget(budgetMillis);
    }

    SearchBudget child(long budgetMillis) {
        long now = System.nanoTime();
        long childDeadline = budgetMillis <= 0
                ? Long.MAX_VALUE
                : now + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        return new SearchBudget(now, Math.min(deadlineNanos, childDeadline), true);
    }

    boolean canContinue() {
        return !Thread.currentThread().isInterrupted() && System.nanoTime() < deadlineNanos;
    }

    boolean hasAtLeastMillis(long millis) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return true;
        }
        return deadlineNanos - System.nanoTime() >= TimeUnit.MILLISECONDS.toNanos(millis);
    }

    long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    long remainingMillis() {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    boolean isLimited() {
        return deadlineNanos != Long.MAX_VALUE;
    }
}
