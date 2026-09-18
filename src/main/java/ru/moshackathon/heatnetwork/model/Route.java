package ru.moshackathon.heatnetwork.model;

import org.locationtech.jts.geom.LineString;

import java.util.Collections;
import java.util.List;

public class Route {
    private final LineString metricGeometry;
    private final double lengthMeters;
    private final String layingMethod;
    private final double specialCoefficient;
    private final List<String> notes;

    public Route(LineString metricGeometry, double lengthMeters, String layingMethod,
                 double specialCoefficient, List<String> notes) {
        this.metricGeometry = metricGeometry;
        this.lengthMeters = lengthMeters;
        this.layingMethod = layingMethod;
        this.specialCoefficient = specialCoefficient;
        this.notes = Collections.unmodifiableList(notes);
    }

    public LineString getMetricGeometry() {
        return metricGeometry;
    }

    public double getLengthMeters() {
        return lengthMeters;
    }

    public String getLayingMethod() {
        return layingMethod;
    }

    public double getSpecialCoefficient() {
        return specialCoefficient;
    }

    public List<String> getNotes() {
        return notes;
    }
}
