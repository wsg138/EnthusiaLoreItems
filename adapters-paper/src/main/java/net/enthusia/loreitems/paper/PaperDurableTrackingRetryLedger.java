package net.enthusia.loreitems.paper;

import java.util.concurrent.ConcurrentHashMap;
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

    private static final String ALL_HOLDERS = "<all-physical-holders>";
    private final Object lock = new Object();
    private final ConcurrentHashMap<Holder, Long> unresolved = new ConcurrentHashMap<>();
    private boolean overflow;
    private long overflowedKeys;

    boolean markUnresolved(TrackingObservationUseCase.Request request, long sequence) {
        Holder holder = request.mode()
                == TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                ? Holder.allHolders(request) : Holder.from(request);
        synchronized (lock) {
            return markHolderUnresolved(holder, sequence);
        }
    }

    private boolean markHolderUnresolved(Holder holder, long sequence) {
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

    boolean sameHolder(
            TrackingObservationUseCase.Request first,
            TrackingObservationUseCase.Request second) {
        return Holder.from(first).equals(Holder.from(second));
    }

    boolean needsReconciliation(TrackingObservationUseCase.Request request) {
        Holder holder = Holder.from(request);
        synchronized (lock) {
            return overflow || unresolved.containsKey(holder)
                    || unresolved.containsKey(Holder.allHolders(request));
        }
    }

    /** Successful NEW, present reconciliation for the same holder is the only clearing proof. */
    boolean reconcile(TrackingObservationUseCase.Request request, long sequence) {
        if (request.presence() != TrackingObservationUseCase.Presence.PRESENT
                || request.mode() != TrackingObservationUseCase.EvidenceMode.RECONCILIATION) {
            return false;
        }
        Holder holder = Holder.from(request);
        synchronized (lock) {
            if (overflow || unresolved.containsKey(Holder.allHolders(request))) {
                return false;
            }
            Long failedSequence = unresolved.get(holder);
            if (failedSequence == null || sequence <= failedSequence) {
                return false;
            }
            unresolved.remove(holder);
            return true;
        }
    }

    Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(unresolved.size(), overflow, overflowedKeys);
        }
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

        /** Tracking requests cannot use DUPLICATE_CONFLICT; this key is local, never persisted. */
        private static Holder allHolders(TrackingObservationUseCase.Request request) {
            return new Holder(request.identity(), LocationDescriptor.Type.DUPLICATE_CONFLICT,
                    ALL_HOLDERS);
        }
    }
}
