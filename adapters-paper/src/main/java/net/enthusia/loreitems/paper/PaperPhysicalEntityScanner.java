package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;

/** Bounded reconciliation scanner shared by chunk and entity lifecycle tracking. */
final class PaperPhysicalEntityScanner {
    private final PaperPhysicalInventoryScanner scanner;
    private final PaperDisplayEntityScanner displayScanner;

    PaperPhysicalEntityScanner(PaperPhysicalInventoryScanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.displayScanner = new PaperDisplayEntityScanner(scanner);
    }

    void scan(
            Iterable<? extends Entity> entities,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        for (Entity entity : entities) {
            if (!limit.hasRemaining()) {
                return;
            }
            if (entity instanceof Item item) {
                scanner.submitItem(
                        item.getItemStack(),
                        droppedLocation(item),
                        presence,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source + "-item");
                limit.consume();
            } else {
                displayScanner.scan(entity, presence, source, limit);
            }
        }
    }

    static LocationDescriptor droppedLocation(Item item) {
        Location location = item.getLocation();
        return new LocationDescriptor(
                LocationDescriptor.Type.DROPPED_ITEM,
                item.getWorld().getKey() + ":entity:" + item.getUniqueId() + ':'
                        + location.getBlockX() + ':' + location.getBlockY() + ':'
                        + location.getBlockZ(),
                "item-entity");
    }
}
