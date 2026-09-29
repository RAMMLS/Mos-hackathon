package ru.moshackathon.heatnetwork.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;

public class Solution {
    private final String variantId;
    private final List<NewSegment> segments = new ArrayList<>();
    private final List<TieInOutput> tieIns = new ArrayList<>();
    private final List<ChamberOutput> chambers = new ArrayList<>();
    private final List<TechnicalNodeOutput> technicalNodes = new ArrayList<>();
    private final List<String> unconnectedConnectionPointIds = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private double constructionCost;
    private double chamberConstructionCost;
    private int existingChamberTieInCount;
    private double existingChamberTieInCost;
    private double unconnectedPenalty;
    private double newNetworkLength;
    private int totalConnectionPointCount = -1;
    private boolean certified;
    private String fullConnectivityStatus = "NOT_REQUESTED";

    public Solution(String variantId) {
        this.variantId = variantId;
    }

    private Solution(Solution source) {
        this.variantId = source.variantId;
        this.segments.addAll(source.segments);
        this.tieIns.addAll(source.tieIns);
        this.chambers.addAll(source.chambers);
        this.technicalNodes.addAll(source.technicalNodes);
        this.unconnectedConnectionPointIds.addAll(source.unconnectedConnectionPointIds);
        this.diagnostics.addAll(source.diagnostics);
        this.constructionCost = source.constructionCost;
        this.chamberConstructionCost = source.chamberConstructionCost;
        this.existingChamberTieInCount = source.existingChamberTieInCount;
        this.existingChamberTieInCost = source.existingChamberTieInCost;
        this.unconnectedPenalty = source.unconnectedPenalty;
        this.newNetworkLength = source.newNetworkLength;
        this.totalConnectionPointCount = source.totalConnectionPointCount;
        this.certified = source.certified;
        this.fullConnectivityStatus = source.fullConnectivityStatus;
    }

    public Solution snapshot() {
        return new Solution(this);
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
    }

    public void addChamber(ChamberOutput chamber) {
        chambers.add(chamber);
        constructionCost += chamber.getCost();
        chamberConstructionCost += chamber.getCost();
    }

    public void addTechnicalNode(TechnicalNodeOutput technicalNode) {
        technicalNodes.add(technicalNode);
    }

    public void addExistingChamberTieIn(double cost) {
        existingChamberTieInCount++;
        existingChamberTieInCost += cost;
        constructionCost += cost;
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

    public List<TechnicalNodeOutput> getTechnicalNodes() {
        return Collections.unmodifiableList(technicalNodes);
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

    public int getExistingChamberTieInCount() {
        return existingChamberTieInCount;
    }

    public double getExistingChamberTieInCost() {
        return existingChamberTieInCost;
    }

    public double getUnconnectedPenalty() {
        return unconnectedPenalty;
    }

    public double getNewNetworkLength() {
        return newNetworkLength;
    }

    public double getCalculatedCost() {
        return constructionCost + unconnectedPenalty;
    }

    public double getLength() {
        return newNetworkLength;
    }

    public double getScore() {
        return 0.7 * (getCalculatedCost() / 25_000_000.0) + 0.3 * (getLength() / 100.0);
    }

    public void setOutcome(int totalConnectionPointCount, boolean certified) {
        this.totalConnectionPointCount = Math.max(0, totalConnectionPointCount);
        this.certified = certified;
    }

    public int getTotalConnectionPointCount() {
        return totalConnectionPointCount;
    }

    public int getConnectedConnectionPointCount() {
        if (totalConnectionPointCount < 0) {
            return -1;
        }
        int distinctUnconnected = new HashSet<>(unconnectedConnectionPointIds).size();
        return Math.max(0, totalConnectionPointCount - distinctUnconnected);
    }

    public double getCoveragePercent() {
        if (totalConnectionPointCount < 0) {
            return Double.NaN;
        }
        if (totalConnectionPointCount == 0) {
            return 100.0;
        }
        return 100.0 * getConnectedConnectionPointCount() / totalConnectionPointCount;
    }

    public boolean isCertified() {
        return certified;
    }

    public boolean isComplete() {
        return certified && totalConnectionPointCount >= 0
                && getConnectedConnectionPointCount() == totalConnectionPointCount;
    }

    public String getSolutionStatus() {
        if (!certified) {
            return "NO_CERTIFIED_SOLUTION";
        }
        return isComplete() ? "FULL" : "PARTIAL";
    }

    public String getFullConnectivityStatus() {
        return fullConnectivityStatus;
    }

    public void setFullConnectivityStatus(String fullConnectivityStatus) {
        this.fullConnectivityStatus = fullConnectivityStatus;
    }
}
