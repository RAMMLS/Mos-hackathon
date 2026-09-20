package ru.moshackathon.heatnetwork.model;

import org.locationtech.jts.geom.Point;

public class TechnicalNodeOutput {
    private final String id;
    private final String variantId;
    private final Point metricPoint;

    public TechnicalNodeOutput(String id, String variantId, Point metricPoint) {
        this.id = id;
        this.variantId = variantId;
        this.metricPoint = metricPoint;
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
}
