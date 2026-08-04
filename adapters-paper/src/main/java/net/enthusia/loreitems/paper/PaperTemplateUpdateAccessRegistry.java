package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * Main-thread snapshots of naturally accessible inventories used to fence duplicate identities
 * before a physical template update is claimed.
 */
final class PaperTemplateUpdateAccessRegistry {
    private static final String REFERENCE_PARAMETER = "reference";

    private final Map<PaperInventoryReference, List<PaperTemplateUpdateScanner.Candidate>>
            snapshots = new ConcurrentHashMap<>();
    private final Set<PaperInventoryReference> incompleteReferences =
            ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirtyInstances = ConcurrentHashMap.newKeySet();

    void invalidate(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, REFERENCE_PARAMETER);
        addDirty(snapshots.remove(reference));
    }

    void markIncomplete(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, REFERENCE_PARAMETER);
        invalidate(reference);
        incompleteReferences.add(reference);
    }

    void remove(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, REFERENCE_PARAMETER);
        invalidate(reference);
        incompleteReferences.remove(reference);
    }

    void replace(
            PaperInventoryReference reference,
            List<PaperTemplateUpdateScanner.Candidate> candidates) {
        Objects.requireNonNull(reference, REFERENCE_PARAMETER);
        Objects.requireNonNull(candidates, "candidates");
        addDirty(snapshots.put(reference, List.copyOf(candidates)));
        addDirty(candidates);
        incompleteReferences.remove(reference);
    }

    List<PaperTemplateUpdateScanner.Candidate> drainUnique(
            Collection<? extends Player> onlinePlayers) {
        Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        if (!readyToDrain(onlinePlayers)) {
            return List.of();
        }
        Map<UUID, CandidateCount> counts = countCandidates();
        List<PaperTemplateUpdateScanner.Candidate> unique = collectUnique(counts);
        dirtyInstances.clear();
        // Transient inventories are rediscovered through natural access, whose replace call marks
        // their observed instances dirty again.
        snapshots.keySet().removeIf(reference -> !persistent(reference));
        return List.copyOf(unique);
    }

    void clear() {
        snapshots.clear();
        incompleteReferences.clear();
        dirtyInstances.clear();
    }

    private boolean readyToDrain(Collection<? extends Player> onlinePlayers) {
        return !dirtyInstances.isEmpty()
                && incompleteReferences.isEmpty()
                && hasCompletePlayerCoverage(onlinePlayers);
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private Map<UUID, CandidateCount> countCandidates() {
        Map<UUID, CandidateCount> counts = new HashMap<>();
        for (List<PaperTemplateUpdateScanner.Candidate> candidates : snapshots.values()) {
            for (PaperTemplateUpdateScanner.Candidate candidate : candidates) {
                UUID instanceId = candidate.identity().instanceId().value();
                counts.compute(
                        instanceId,
                        (ignored, currentCount) -> currentCount == null
                                ? new CandidateCount(candidate)
                                : currentCount.increment());
            }
        }
        return counts;
    }

    private List<PaperTemplateUpdateScanner.Candidate> collectUnique(
            Map<UUID, CandidateCount> counts) {
        List<PaperTemplateUpdateScanner.Candidate> unique = new ArrayList<>();
        for (UUID instanceId : dirtyInstances) {
            CandidateCount candidateCount = counts.get(instanceId);
            if (candidateCount != null && candidateCount.referenceCount() == 1) {
                unique.add(candidateCount.firstCandidate());
            }
        }
        return unique;
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private boolean hasCompletePlayerCoverage(Collection<? extends Player> onlinePlayers) {
        for (Player player : onlinePlayers) {
            UUID playerId = player.getUniqueId();
            if (!snapshots.containsKey(new PaperInventoryReference.PlayerMain(playerId))
                    || !snapshots.containsKey(new PaperInventoryReference.PlayerEnder(playerId))) {
                return false;
            }
        }
        return true;
    }

    private void addDirty(List<PaperTemplateUpdateScanner.Candidate> candidates) {
        if (candidates == null) {
            return;
        }
        for (PaperTemplateUpdateScanner.Candidate candidate : candidates) {
            dirtyInstances.add(candidate.identity().instanceId().value());
        }
    }

    private static boolean persistent(PaperInventoryReference reference) {
        return reference instanceof PaperInventoryReference.PlayerMain
                || reference instanceof PaperInventoryReference.PlayerEnder;
    }

    private static final class CandidateCount {
        private final PaperTemplateUpdateScanner.Candidate representativeCandidate;
        private int occurrences = 1;

        private CandidateCount(PaperTemplateUpdateScanner.Candidate firstCandidate) {
            this.representativeCandidate = Objects.requireNonNull(firstCandidate, "firstCandidate");
        }

        private CandidateCount increment() {
            occurrences++;
            return this;
        }

        private PaperTemplateUpdateScanner.Candidate firstCandidate() {
            return representativeCandidate;
        }

        private int referenceCount() {
            return occurrences;
        }
    }
}
