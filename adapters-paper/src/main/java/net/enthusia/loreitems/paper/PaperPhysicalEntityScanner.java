package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

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
            if (entity instanceof Item item) {
                if (!limit.tryConsume()) {
                    return;
                }
                scanner.scanItemTree(
                        item.getItemStack(),
                        droppedLocation(item),
                        presence,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source + "-item",
                        limit);
            } else if (isDisplayEntity(entity)) {
                if (!limit.tryConsume()) {
                    return;
                }
                displayScanner.scan(entity, presence, source, limit);
            } else if (entity instanceof InventoryHolder holder && !(entity instanceof Player)) {
                if (!limit.tryConsume()) {
                    return;
                }
                scanner.scanInventory(
                        holder.getInventory(),
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        entityInventoryKey(entity),
                        presence,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source + "-entity-inventory",
                        limit);
            }
        }
    }

    private static boolean isDisplayEntity(Entity entity) {
        return entity instanceof ItemFrame
                || entity instanceof ItemDisplay
                || entity instanceof ArmorStand;
    }

    static String entityInventoryKey(Entity entity) {
        return entity.getWorld().getKey() + ":entity:" + entity.getUniqueId();
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
