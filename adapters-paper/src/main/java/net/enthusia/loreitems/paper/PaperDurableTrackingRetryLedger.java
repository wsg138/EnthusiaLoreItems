package net.enthusia.loreitems.paper;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;

/**
 * Ephemeral failure evidence, not a retry queue. Replaying old authoritative moves is unsafe
 * without a durable sequencing fence. Natural re-observation alone may resolve a holder.
 * Overflow is sticky and represents unenumerable uncertainty, not discarded success.
 */
final class PaperDurableTrackingRetryLedger {
    static final int MAX_UNRESOLVED = 4_096;

    private final Map<Holder, Long> unresolved = new HashMap<>();
    private boolean overflow;
    private long overflowedKeys;

    synchronized boolean markUnresolved(TrackingObservationUseCase.Request request, long sequence) {
        Holder holder = Holder.from(request);
        Long previous = unresolved.get(holder);
        if (previous != null) {
            unresolved.put(holder, Math.max(previous, sequence));
            return false;
        }
        if (unresolved.size() == MAX_UNRESOLVED) {
            overflowedKeys++;
            boolean firstOverflow = !overflow;
            overflow = true;
            return firstOverflow;
        }
        unresolved.put(holder, sequence);
        return false;
    }

    synchronized boolean needsReconciliation(TrackingObservationUseCase.Request request) {
        return overflow || unresolved.containsKey(Holder.from(request));
    }

    /** Successful NEW, present reconciliation for the same holder is the only clearing proof. */
    synchronized boolean reconcile(TrackingObservationUseCase.Request request, long sequence) {
        if (request.presence() != TrackingObservationUseCase.Presence.PRESENT
                || request.mode() != TrackingObservationUseCase.EvidenceMode.RECONCILIATION) {
            return false;
        }
        Holder holder = Holder.from(request);
        Long failedSequence = unresolved.get(holder);
        if (failedSequence == null || sequence <= failedSequence) {
            return false;
        }
        unresolved.remove(holder);
        return true;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(unresolved.size(), overflow, overflowedKeys);
    }

    record Snapshot(int unresolvedHolders, boolean overflow, long overflowedKeys) {}

    private record Holder(
            LoreItemIdentity identity, LocationDescriptor.Type type, String locationKey) {
        private Holder {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(locationKey, "locationKey");
        }

        private static Holder from(TrackingObservationUseCase.Request request) {
            return new Holder(request.identity(),
                    request.location().type(), request.location().locationKey());
        }
    }
}
