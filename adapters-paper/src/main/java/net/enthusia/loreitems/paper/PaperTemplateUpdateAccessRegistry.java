package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * Main-thread snapshots of naturally accessible inventories and loaded item-bearing entities used
 * to fence duplicate identities before a physical template update is claimed.
 */
final class PaperTemplateUpdateAccessRegistry {
    private static final String REFERENCE_PARAMETER = "reference";

    private final Map<PaperInventoryReference, List<PaperTemplateUpdateScanner.Candidate>>
            inventorySnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, PaperTemplateUpdateScanner.Candidate> entitySnapshots =
            new ConcurrentHashMap<>();
    private final Set<PaperInventoryReference> incompleteReferences =
            ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirtyInstances = ConcurrentHashMap.newKeySet();

    private boolean entityCoverageComplete = true;

    void invalidate(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, REFERENCE_PARAMETER);
        invalidateSnapshot(reference);
    }

    void markIncomplete(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, REFERENCE_PARAMETER);
        invalidateSnapshot(reference);
        incompleteReferences.add(reference);
    }

    void remove(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, REFERENCE_PARAMETER);
        invalidateSnapshot(reference);
        incompleteReferences.remove(reference);
    }

    void replace(
            PaperInventoryReference reference,
            List<PaperTemplateUpdateScanner.Candidate> candidates) {
        Objects.requireNonNull(reference, REFERENCE_PARAMETER);
        Objects.requireNonNull(candidates, "candidates");
        addDirty(inventorySnapshots.put(reference, List.copyOf(candidates)));
        addDirty(candidates);
        incompleteReferences.remove(reference);
    }

    void markEntityCoverageIncomplete() {
        entityCoverageComplete = false;
    }

    boolean entityCoverageComplete() {
        return entityCoverageComplete;
    }

    void replaceEntity(
            UUID entityId,
            PaperTemplateUpdateScanner.Candidate candidate) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(candidate, "candidate");
        addDirty(entitySnapshots.put(entityId, candidate));
        addDirty(candidate);
    }

    void removeEntity(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId");
        addDirty(entitySnapshots.remove(entityId));
    }

    void completeEntityCoverage(Set<UUID> seenEntityIds) {
        Objects.requireNonNull(seenEntityIds, "seenEntityIds");
        for (Map.Entry<UUID, PaperTemplateUpdateScanner.Candidate> entry
                : entitySnapshots.entrySet()) {
            if (!seenEntityIds.contains(entry.getKey())
                    && entitySnapshots.remove(entry.getKey(), entry.getValue())) {
                addDirty(entry.getValue());
            }
        }
        entityCoverageComplete = true;
    }

    DispatchBatch prepareDispatch(Collection<? extends Player> onlinePlayers) {
        Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        if (!readyToDrain(onlinePlayers)) {
            return DispatchBatch.empty();
        }
        Map<UUID, CandidateCount> counts = countCandidates();
        List<PaperTemplateUpdateScanner.Candidate> unique = collectUnique(counts);
        dirtyInstances.clear();
        return new DispatchBatch(unique, transientReferences());
    }

    void finishDispatch(DispatchBatch batch, Set<UUID> rejectedInstances) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(rejectedInstances, "rejectedInstances");
        for (PaperInventoryReference reference : batch.transientReferences()) {
            List<PaperTemplateUpdateScanner.Candidate> candidates =
                    inventorySnapshots.get(reference);
            if (candidates != null && !containsRejected(candidates, rejectedInstances)) {
                inventorySnapshots.remove(reference, candidates);
            }
        }
        dirtyInstances.addAll(rejectedInstances);
    }

    List<PaperTemplateUpdateScanner.Candidate> drainUnique(
            Collection<? extends Player> onlinePlayers) {
        DispatchBatch batch = prepareDispatch(onlinePlayers);
        finishDispatch(batch, Set.of());
        return batch.candidates();
    }

    void clear() {
        inventorySnapshots.clear();
        entitySnapshots.clear();
        incompleteReferences.clear();
        dirtyInstances.clear();
        entityCoverageComplete = true;
    }

    private void invalidateSnapshot(PaperInventoryReference reference) {
        addDirty(inventorySnapshots.remove(reference));
    }

    private boolean readyToDrain(Collection<? extends Player> onlinePlayers) {
        return entityCoverageComplete
                && !dirtyInstances.isEmpty()
                && incompleteReferences.isEmpty()
                && hasCompletePlayerCoverage(onlinePlayers);
    }

    // This method-local aggregation never escapes the main-thread snapshot drain.
    @SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops", "PMD.UseConcurrentHashMap"})
    private Map<UUID, CandidateCount> countCandidates() {
        Map<UUID, CandidateCount> counts = new HashMap<>();
        for (List<PaperTemplateUpdateScanner.Candidate> candidates
                : inventorySnapshots.values()) {
            addCandidateCounts(counts, candidates);
        }
        addCandidateCounts(counts, entitySnapshots.values());
        return counts;
    }

    private static void addCandidateCounts(
            Map<UUID, CandidateCount> counts,
            Collection<PaperTemplateUpdateScanner.Candidate> candidates) {
        for (PaperTemplateUpdateScanner.Candidate candidate : candidates) {
            UUID instanceId = candidate.identity().instanceId().value();
            counts.compute(
                    instanceId,
                    (ignored, currentCount) -> currentCount == null
                            ? new CandidateCount(candidate)
                            : currentCount.increment());
        }
    }

    private List<PaperTemplateUpdateScanner.Candidate> collectUnique(
            Map<UUID, CandidateCount> counts) {
        List<PaperTemplateUpdateScanner.Candidate> unique = new ArrayList<>();
        for (UUID instanceId : dirtyInstances) {
            CandidateCount candidateCount = counts.get(instanceId);
            if (candidateCount != null && candidateCount.referenceCount() == 1) {
                unique.add(candidateCount.firstEncounteredCandidate());
            }
        }
        return unique;
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private boolean hasCompletePlayerCoverage(Collection<? extends Player> onlinePlayers) {
        for (Player player : onlinePlayers) {
            UUID playerId = player.getUniqueId();
            if (!inventorySnapshots.containsKey(
                            new PaperInventoryReference.PlayerMain(playerId))
                    || !inventorySnapshots.containsKey(
                            new PaperInventoryReference.PlayerEnder(playerId))) {
                return false;
            }
        }
        return true;
    }

    private Set<PaperInventoryReference> transientReferences() {
        Set<PaperInventoryReference> references = new HashSet<>();
        for (PaperInventoryReference reference : inventorySnapshots.keySet()) {
            if (!persistent(reference)) {
                references.add(reference);
            }
        }
        return Set.copyOf(references);
    }

    private static boolean containsRejected(
            List<PaperTemplateUpdateScanner.Candidate> candidates,
            Set<UUID> rejectedInstances) {
        for (PaperTemplateUpdateScanner.Candidate candidate : candidates) {
            if (rejectedInstances.contains(candidate.identity().instanceId().value())) {
                return true;
            }
        }
        return false;
    }

    private void addDirty(List<PaperTemplateUpdateScanner.Candidate> candidates) {
        if (candidates == null) {
            return;
        }
        for (PaperTemplateUpdateScanner.Candidate candidate : candidates) {
            addDirty(candidate);
        }
    }

    private void addDirty(PaperTemplateUpdateScanner.Candidate candidate) {
        if (candidate != null) {
            dirtyInstances.add(candidate.identity().instanceId().value());
        }
    }

    private static boolean persistent(PaperInventoryReference reference) {
        return reference instanceof PaperInventoryReference.PlayerMain
                || reference instanceof PaperInventoryReference.PlayerEnder;
    }

    record DispatchBatch(
            List<PaperTemplateUpdateScanner.Candidate> candidates,
            Set<PaperInventoryReference> transientReferences) {
        DispatchBatch {
            Objects.requireNonNull(candidates, "candidates");
            Objects.requireNonNull(transientReferences, "transientReferences");
            candidates = List.copyOf(candidates);
            transientReferences = Set.copyOf(transientReferences);
        }

        private static DispatchBatch empty() {
            return new DispatchBatch(List.of(), Set.of());
        }
    }

    private static final class CandidateCount {
        private final PaperTemplateUpdateScanner.Candidate representativeCandidate;
        private int occurrences = 1;

        private CandidateCount(PaperTemplateUpdateScanner.Candidate firstEncounteredCandidate) {
            this.representativeCandidate = Objects.requireNonNull(
                    firstEncounteredCandidate, "firstEncounteredCandidate");
        }

        private CandidateCount increment() {
            occurrences++;
            return this;
        }

        private PaperTemplateUpdateScanner.Candidate firstEncounteredCandidate() {
            return representativeCandidate;
        }

        private int referenceCount() {
            return occurrences;
        }
    }
}
