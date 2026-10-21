package net.enthusia.loreitems.paper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Loaded-only block inventory traversal, sharing the caller's bounded scan budget. */
final class PaperChunkContainerReconciler {
    private final PaperPhysicalInventoryScanner scanner;

    PaperChunkContainerReconciler(PaperPhysicalInventoryScanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    void scan(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        Set<String> scannedInventories = new HashSet<>();
        for (BlockState state : chunk.getTileEntities(false)) {
            if (!limit.hasRemaining()) {
                return;
            }
            if (!(state instanceof InventoryHolder holder)) {
                continue;
            }
            Inventory inventory = holder.getInventory();
            Optional<PaperPhysicalInventorySnapshot> snapshot =
                    PaperPhysicalInventorySnapshot.capture(inventory);
            if (snapshot.isEmpty()) {
                continue;
            }
            PaperPhysicalInventorySnapshot reference = snapshot.orElseThrow();
            String scanKey = reference.type().name() + ':' + reference.key();
            if (!scannedInventories.add(scanKey)) {
                continue;
            }
            if (!limit.tryConsume()) {
                return;
            }
            scanner.scanInventory(
                    inventory,
                    reference.type(),
                    reference.key(),
                    presence,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    source + "-container",
                    limit);
        }
    }
}
