package ru.moshackathon.heatnetwork.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProblemData {
    private final List<InputFeature> features;
    private final List<InputFeature> connectionPoints;
    private final List<InputFeature> heatNetworks;
    private final List<InputFeature> heatChambers;
    private final List<InputFeature> restrictions;
    private final List<String> diagnostics;

    public ProblemData(List<InputFeature> features, List<String> diagnostics) {
        this.features = Collections.unmodifiableList(new ArrayList<>(features));
        this.connectionPoints = filter(features, "oks_connection_point");
        this.heatNetworks = filter(features, "heat_network");
        this.heatChambers = filter(features, "heat_chamber");
        this.restrictions = filter(features, "restriction");
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    private static List<InputFeature> filter(List<InputFeature> features, String objectType) {
        List<InputFeature> result = new ArrayList<>();
        for (InputFeature feature : features) {
            if (objectType.equals(feature.getObjectType())) {
                result.add(feature);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<InputFeature> getFeatures() {
        return features;
    }

    public List<InputFeature> getConnectionPoints() {
        return connectionPoints;
    }

    public List<InputFeature> getHeatNetworks() {
        return heatNetworks;
    }

    public List<InputFeature> getHeatChambers() {
        return heatChambers;
    }

    public List<InputFeature> getRestrictions() {
        return restrictions;
    }

    public List<String> getDiagnostics() {
        return diagnostics;
    }
}
