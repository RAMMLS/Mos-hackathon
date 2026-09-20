package ru.moshackathon.heatnetwork.solver;

public class AlgorithmUnavailableException extends RuntimeException {
    private final String status;

    public AlgorithmUnavailableException(String status, String message) {
        super(message);
        this.status = status;
    }

    public String getStatus() {
        return status;
    }
}
