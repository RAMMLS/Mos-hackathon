package ru.moshackathon.heatnetwork.solver;

import ru.moshackathon.heatnetwork.model.Solution;

/** Raised when search produced diagnostics but no candidate passed certification. */
public final class NoCertifiedSolutionException extends RuntimeException {
    private final Solution diagnosticFallback;
    private final String source;

    public NoCertifiedSolutionException(String source, String message, Solution diagnosticFallback) {
        super(message);
        this.source = source;
        this.diagnosticFallback = diagnosticFallback == null ? null : diagnosticFallback.snapshot();
    }

    public Solution getDiagnosticFallback() {
        return diagnosticFallback == null ? null : diagnosticFallback.snapshot();
    }

    public String getSource() {
        return source;
    }
}
