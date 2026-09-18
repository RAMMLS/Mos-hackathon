package ru.moshackathon.heatnetwork.model;

import org.locationtech.jts.geom.Point;

public class ChamberOutput {
    private final String id;
    private final String variantId;
    private final Point metricPoint;
    private final int diameter;
    private final double cost;

    public ChamberOutput(String id, String variantId, Point metricPoint, int diameter, double cost) {
        this.id = id;
        this.variantId = variantId;
        this.metricPoint = metricPoint;
        this.diameter = diameter;
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

    public int getDiameter() {
        return diameter;
    }

    public double getCost() {
        return cost;
    }
}
