package ru.moshackathon.heatnetwork.solver;

import ru.moshackathon.heatnetwork.model.Solution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Keeps immutable snapshots of independently admissible solver candidates. */
public final class CertifiedArchive {
    private final Predicate<Solution> verifier;
    private final List<String> acceptedLabels = new ArrayList<>();
    private final List<String> rejectedLabels = new ArrayList<>();
    private final Set<String> constructionFingerprints = new HashSet<>();
    private int duplicateConstructionCount;
    private Solution best;
    private String bestLabel;

    public CertifiedArchive(Predicate<Solution> verifier) {
        this.verifier = verifier;
    }

    public boolean offer(String label, Solution candidate) {
        if (candidate == null || !hasFiniteObjective(candidate) || !verifier.test(candidate)) {
            rejectedLabels.add(label);
            return false;
        }
        Solution snapshot = candidate.snapshot();
        acceptedLabels.add(label);
        if (!constructionFingerprints.add(SolutionConstructionFingerprint.of(snapshot))) {
            duplicateConstructionCount++;
        }
        if (best == null || isBetter(snapshot, best)) {
            best = snapshot;
            bestLabel = label;
            return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return best == null;
    }

    public Solution bestSnapshot() {
        if (best == null) {
            throw new IllegalStateException("CERTIFIED_ARCHIVE_EMPTY");
        }
        return best.snapshot();
    }

    public String getBestLabel() {
        return bestLabel;
    }

    public List<String> getAcceptedLabels() {
        return Collections.unmodifiableList(acceptedLabels);
    }

    public List<String> getRejectedLabels() {
        return Collections.unmodifiableList(rejectedLabels);
    }

    public int getUniqueConstructionCount() {
        return constructionFingerprints.size();
    }

    public int getDuplicateConstructionCount() {
        return duplicateConstructionCount;
    }

    public String describeConstructionDiversity() {
        return "evaluated=" + acceptedLabels.size()
                + ", unique_constructions=" + getUniqueConstructionCount()
                + ", duplicate_constructions=" + duplicateConstructionCount;
    }

    private boolean hasFiniteObjective(Solution candidate) {
        return Double.isFinite(candidate.getScore())
                && Double.isFinite(candidate.getCalculatedCost())
                && Double.isFinite(candidate.getLength());
    }

    static boolean isBetter(Solution candidate, Solution incumbent) {
        int candidateUnconnected = candidate.getUnconnectedConnectionPointIds().size();
        int incumbentUnconnected = incumbent.getUnconnectedConnectionPointIds().size();
        if (candidateUnconnected != incumbentUnconnected) {
            return candidateUnconnected < incumbentUnconnected;
        }
        if (Math.abs(candidate.getScore() - incumbent.getScore()) > 0.000001) {
            return candidate.getScore() < incumbent.getScore();
        }
        return candidate.getCalculatedCost() < incumbent.getCalculatedCost();
    }
}
