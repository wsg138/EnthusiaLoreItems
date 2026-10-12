package net.enthusia.loreitems.paper;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.InventoryHolder;

/** Loaded-only, bounded chunk inventory/entity scanner. Never loads a chunk. */
final class PaperPhysicalChunkScanner {
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private final PaperPhysicalInventoryScanner scanner;
    private final PaperPhysicalEntityScanner entityScanner;
    private final MetricsPort metrics;
    private final Logger logger;

    PaperPhysicalChunkScanner(
            PaperPhysicalInventoryScanner scanner,
            PaperPhysicalEntityScanner entityScanner,
            MetricsPort metrics,
            Logger logger) {
        this.scanner = scanner;
        this.entityScanner = entityScanner;
        this.metrics = metrics;
        this.logger = logger;
    }

    boolean scanChunk(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        scanChunkEntities(chunk, presence, source, limit);
        scanChunkContainers(chunk, presence, source, limit);
        if (!limit.hasRemaining()) {
            metrics.increment("tracking.scan_truncated");
            logger.fine(
                    "Lore-item chunk scan reached its bounded item limit for " + source + '.');
            return false;
        }
        return true;
    }

    private void scanChunkEntities(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        entityScanner.scan(List.of(chunk.getEntities()), presence, source, limit);
    }

    private void scanChunkContainers(
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
            org.bukkit.inventory.Inventory inventory = holder.getInventory();
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
