package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

/**
 * Measures hopper-driven physical tracking separately from the bounded
 * lifecycle scan queue. All callbacks run on the Bukkit main thread.
 */
final class PaperHopperMoveObservation {
    @FunctionalInterface
    interface SnapshotScan {
        void scan(Optional<PaperPhysicalInventorySnapshot> snapshot, String source);
    }

    @FunctionalInterface
    interface MatchingScan {
        void scan(Optional<PaperPhysicalInventorySnapshot> snapshot,
                Set<LoreItemIdentity> identities, String source);
    }

    private final PaperPhysicalInventoryScanner scanner;
    private final PaperDeferredMainThreadActions deferredActions;
    private final MetricsPort metrics;
    private final SnapshotScan scanReference;
    private final MatchingScan submitMatching;

    PaperHopperMoveObservation(
            PaperPhysicalInventoryScanner scanner,
            PaperDeferredMainThreadActions deferredActions,
            MetricsPort metrics,
            SnapshotScan scanReference,
            MatchingScan submitMatching) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.deferredActions = Objects.requireNonNull(deferredActions, "deferredActions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scanReference = Objects.requireNonNull(scanReference, "scanReference");
        this.submitMatching = Objects.requireNonNull(submitMatching, "submitMatching");
    }

    void observe(InventoryMoveItemEvent event) {
        long started = System.nanoTime();
        try {
            // A plain moved stack cannot relocate a tracked identity. Avoid
            // snapshotting either inventory or enqueueing full reconciliations.
            // The guard also checks nested containers and malformed PDC.
            if (!scanner.hasIdentityEvidence(event.getItem())) {
                return;
            }
            Optional<PaperPhysicalInventorySnapshot> source =
                    PaperPhysicalInventorySnapshot.capture(event.getSource());
            Optional<PaperPhysicalInventorySnapshot> destination =
                    PaperPhysicalInventorySnapshot.capture(event.getDestination());
            Set<LoreItemIdentity> identities = scanner.trackedIdentities(event.getItem());
            deferredActions.schedule(() -> reconcile(source, destination, identities));
        } finally {
            long elapsed = System.nanoTime() - started;
            metrics.recordDurationNanos("tracking.hopper_move_event_nanos", elapsed);
            metrics.setGauge("tracking.hopper_move_event_peak_nanos", elapsed);
            metrics.increment("tracking.hopper_move_events");
        }
    }

    private void reconcile(
            Optional<PaperPhysicalInventorySnapshot> source,
            Optional<PaperPhysicalInventorySnapshot> destination,
            Set<LoreItemIdentity> identities) {
        long started = System.nanoTime();
        try {
            scanReference.scan(source, "inventory-move-source");
            // The matched destination scan may issue an authoritative transfer,
            // whereas the complete destination scan supplies conservative evidence.
            // Distinct provenance is required to diagnose which path fenced a
            // real one-copy move, without changing either persistence policy.
            submitMatching.scan(destination, identities, "inventory-move-destination-matched");
            if (!source.equals(destination)) {
                scanReference.scan(destination, "inventory-move-destination-reconciled");
            }
        } finally {
            long elapsed = System.nanoTime() - started;
            metrics.recordDurationNanos("tracking.hopper_move_deferred_nanos", elapsed);
            metrics.setGauge("tracking.hopper_move_deferred_peak_nanos", elapsed);
            metrics.increment("tracking.hopper_move_deferred");
        }
    }
}
