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
import org.bukkit.entity.Player;

/**
 * Main-thread snapshots of naturally accessible inventories used to fence duplicate identities
 * before a physical template update is claimed.
 */
final class PaperTemplateUpdateAccessRegistry {
    private final Map<PaperInventoryReference, List<PaperTemplateUpdateScanner.Candidate>>
            snapshots = new HashMap<>();
    private final Set<PaperInventoryReference> incompleteReferences = new HashSet<>();
    private final Set<UUID> dirtyInstances = new HashSet<>();

    void invalidate(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        addDirty(snapshots.remove(reference));
    }

    void markIncomplete(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        invalidate(reference);
        incompleteReferences.add(reference);
    }

    void remove(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        invalidate(reference);
        incompleteReferences.remove(reference);
    }

    void replace(
            PaperInventoryReference reference,
            List<PaperTemplateUpdateScanner.Candidate> candidates) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(candidates, "candidates");
        addDirty(snapshots.put(reference, List.copyOf(candidates)));
        addDirty(candidates);
        incompleteReferences.remove(reference);
    }

    List<PaperTemplateUpdateScanner.Candidate> drainUnique(
            Collection<? extends Player> onlinePlayers) {
        Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        if (dirtyInstances.isEmpty()
                || !incompleteReferences.isEmpty()
                || !hasCompletePlayerCoverage(onlinePlayers)) {
            return List.of();
        }

        Map<UUID, CandidateCount> counts = new HashMap<>();
        for (List<PaperTemplateUpdateScanner.Candidate> candidates : snapshots.values()) {
            for (PaperTemplateUpdateScanner.Candidate candidate : candidates) {
                UUID instanceId = candidate.identity().instanceId().value();
                counts.compute(
                        instanceId,
                        (ignored, count) -> count == null
                                ? new CandidateCount(candidate)
                                : count.increment());
            }
        }

        List<PaperTemplateUpdateScanner.Candidate> unique = new ArrayList<>();
        for (UUID instanceId : dirtyInstances) {
            CandidateCount count = counts.get(instanceId);
            if (count != null && count.count() == 1) {
                unique.add(count.first());
            }
        }
        dirtyInstances.clear();
        snapshots.keySet().removeIf(reference -> !persistent(reference));
        return List.copyOf(unique);
    }

    void clear() {
        snapshots.clear();
        incompleteReferences.clear();
        dirtyInstances.clear();
    }

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
        private final PaperTemplateUpdateScanner.Candidate first;
        private int count = 1;

        private CandidateCount(PaperTemplateUpdateScanner.Candidate first) {
            this.first = Objects.requireNonNull(first, "first");
        }

        private CandidateCount increment() {
            count++;
            return this;
        }

        private PaperTemplateUpdateScanner.Candidate first() {
            return first;
        }

        private int count() {
            return count;
        }
    }
}
