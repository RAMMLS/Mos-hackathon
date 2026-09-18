package ru.moshackathon.heatnetwork.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Solution {
    private final String variantId;
    private final List<NewSegment> segments = new ArrayList<>();
    private final List<TieInOutput> tieIns = new ArrayList<>();
    private final List<ChamberOutput> chambers = new ArrayList<>();
    private final List<String> unconnectedConnectionPointIds = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private double constructionCost;
    private double chamberConstructionCost;
    private double tieInCost;
    private double unconnectedPenalty;
    private double newNetworkLength;

    public Solution(String variantId) {
        this.variantId = variantId;
    }

    public String getVariantId() {
        return variantId;
    }

    public void addSegment(NewSegment segment) {
        segments.add(segment);
        constructionCost += segment.getCost();
        newNetworkLength += segment.getLengthMeters();
    }

    public void addTieIn(TieInOutput tieIn) {
        tieIns.add(tieIn);
        tieInCost += tieIn.getCost();
    }

    public void addChamber(ChamberOutput chamber) {
        chambers.add(chamber);
        chamberConstructionCost += chamber.getCost();
    }

    public void addUnconnected(String connectionPointId, double penalty, String reason) {
        unconnectedConnectionPointIds.add(connectionPointId);
        unconnectedPenalty += penalty;
        diagnostics.add("connection point " + connectionPointId + " unconnected: " + reason);
    }

    public void addDiagnostic(String diagnostic) {
        diagnostics.add(diagnostic);
    }

    public List<NewSegment> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    public List<TieInOutput> getTieIns() {
        return Collections.unmodifiableList(tieIns);
    }

    public List<ChamberOutput> getChambers() {
        return Collections.unmodifiableList(chambers);
    }

    public List<String> getUnconnectedConnectionPointIds() {
        return Collections.unmodifiableList(unconnectedConnectionPointIds);
    }

    public List<String> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public double getConstructionCost() {
        return constructionCost;
    }

    public double getChamberConstructionCost() {
        return chamberConstructionCost;
    }

    public double getTieInCost() {
        return tieInCost;
    }

    public double getUnconnectedPenalty() {
        return unconnectedPenalty;
    }

    public double getNewNetworkLength() {
        return newNetworkLength;
    }

    public double getCalculatedCost() {
        return constructionCost + chamberConstructionCost + tieInCost + unconnectedPenalty;
    }

    public double getLength() {
        return newNetworkLength;
    }

    public double getScore() {
        return 0.7 * (getCalculatedCost() / 25_000_000.0) + 0.3 * (getLength() / 100.0);
    }
}
