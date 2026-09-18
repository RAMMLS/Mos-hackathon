package ru.moshackathon.heatnetwork.model;

import org.locationtech.jts.geom.Point;

public class TieInOutput {
    private final String id;
    private final String variantId;
    private final Point metricPoint;
    private final TieInCandidate candidate;
    private final int requiredDiameter;
    private final double cost;

    public TieInOutput(String id, String variantId, Point metricPoint, TieInCandidate candidate,
                       int requiredDiameter, double cost) {
        this.id = id;
        this.variantId = variantId;
        this.metricPoint = metricPoint;
        this.candidate = candidate;
        this.requiredDiameter = requiredDiameter;
        this.cost = cost;
    }

    public String getId() {
        return id;
    }

    public String getVariantId() {
        return variantId;
    }

    public Point getMetricPoint() {
        return metricPoint;
    }

    public TieInCandidate getCandidate() {
        return candidate;
    }

    public int getRequiredDiameter() {
        return requiredDiameter;
    }

    public double getCost() {
        return cost;
    }
}
