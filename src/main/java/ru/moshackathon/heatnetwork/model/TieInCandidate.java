package ru.moshackathon.heatnetwork.model;

import org.locationtech.jts.geom.Coordinate;

public class TieInCandidate {
    public enum HostType {
        EXISTING_CHAMBER,
        EXISTING_PIPE
    }

    private final HostType hostType;
    private final String hostId;
    private final Coordinate metricPoint;
    private final Coordinate lonLatPoint;
    private final int existingDiameter;
    private final double distanceFromConnection;

    public TieInCandidate(HostType hostType, String hostId, Coordinate metricPoint, Coordinate lonLatPoint,
                          int existingDiameter, double distanceFromConnection) {
        this.hostType = hostType;
        this.hostId = hostId;
        this.metricPoint = metricPoint;
        this.lonLatPoint = lonLatPoint;
        this.existingDiameter = existingDiameter;
        this.distanceFromConnection = distanceFromConnection;
    }

    public HostType getHostType() {
        return hostType;
    }

    public String getHostId() {
        return hostId;
    }

    public Coordinate getMetricPoint() {
        return metricPoint;
    }

    public Coordinate getLonLatPoint() {
        return lonLatPoint;
    }

    public int getExistingDiameter() {
        return existingDiameter;
    }

    public double getDistanceFromConnection() {
        return distanceFromConnection;
    }
}
