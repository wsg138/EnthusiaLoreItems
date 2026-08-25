package net.enthusia.loreitems.paper;

import java.util.Locale;
import java.util.Objects;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Bounded reconciliation scanner for item frames, item displays, and armor-stand equipment. */
final class PaperDisplayEntityScanner {
    private static final String ITEM_PATH = "item";
    private static final String SLOT_PREFIX = "slot:";
    private static final EquipmentSlot[] ARMOR_STAND_SLOTS = {
        EquipmentSlot.HAND,
        EquipmentSlot.OFF_HAND,
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD
    };

    private final PaperPhysicalInventoryScanner scanner;

    PaperDisplayEntityScanner(PaperPhysicalInventoryScanner scanner) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    void scan(
            Entity entity,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        if (entity instanceof ItemFrame frame) {
            submit(
                    frame.getItem(),
                    location(frame, LocationDescriptor.Type.ITEM_FRAME, ITEM_PATH),
                    presence,
                    source + "-item-frame",
                    limit);
        } else if (entity instanceof ItemDisplay display) {
            submit(
                    display.getItemStack(),
                    location(display, LocationDescriptor.Type.ITEM_DISPLAY, ITEM_PATH),
                    presence,
                    source + "-item-display",
                    limit);
        } else if (entity instanceof ArmorStand stand) {
            scanArmorStand(stand, presence, source, limit);
        }
    }

    private void scanArmorStand(
            ArmorStand stand,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        for (EquipmentSlot slot : ARMOR_STAND_SLOTS) {
            if (!limit.hasRemaining()) {
                return;
            }
            submit(
                    stand.getEquipment().getItem(slot),
                    location(
                            stand,
                            LocationDescriptor.Type.ARMOR_STAND,
                            SLOT_PREFIX + slot.name().toLowerCase(Locale.ROOT)),
                    presence,
                    source + "-armor-stand",
                    limit);
        }
    }

    private void submit(
            ItemStack item,
            LocationDescriptor location,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        if (item == null || item.getType().isAir() || !limit.hasRemaining()) {
            return;
        }
        scanner.scanItemTree(
                item,
                location,
                presence,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                source,
                limit);
    }

    static LocationDescriptor location(
            Entity entity, LocationDescriptor.Type type, String path) {
        Location location = entity.getLocation();
        return new LocationDescriptor(
                type,
                entity.getWorld().getKey() + ":"
                        + location.getBlockX() + ":"
                        + location.getBlockY() + ":"
                        + location.getBlockZ() + ":"
                        + entity.getUniqueId(),
                path);
    }
}
