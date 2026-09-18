package ru.moshackathon.heatnetwork.model;

import org.locationtech.jts.geom.LineString;

public class NewSegment {
    private final String id;
    private final String variantId;
    private final String startNodeId;
    private final String endNodeId;
    private final LineString metricGeometry;
    private final double flowTph;
    private final int diameter;
    private final double lengthMeters;
    private final String layingMethod;
    private final double cost;

    public NewSegment(String id, String variantId, String startNodeId, String endNodeId,
                      LineString metricGeometry, double flowTph, int diameter, double lengthMeters,
                      String layingMethod, double cost) {
        this.id = id;
        this.variantId = variantId;
        this.startNodeId = startNodeId;
        this.endNodeId = endNodeId;
        this.metricGeometry = metricGeometry;
        this.flowTph = flowTph;
        this.diameter = diameter;
        this.lengthMeters = lengthMeters;
        this.layingMethod = layingMethod;
        this.cost = cost;
    }

    public String getId() {
        return id;
    }

    public String getVariantId() {
        return variantId;
    }

    public String getStartNodeId() {
        return startNodeId;
    }

    public String getEndNodeId() {
        return endNodeId;
    }

    public LineString getMetricGeometry() {
        return metricGeometry;
    }

    public double getFlowTph() {
        return flowTph;
    }

    public int getDiameter() {
        return diameter;
    }

    public double getLengthMeters() {
        return lengthMeters;
    }

    public String getLayingMethod() {
        return layingMethod;
    }

    public double getCost() {
        return cost;
    }
}
