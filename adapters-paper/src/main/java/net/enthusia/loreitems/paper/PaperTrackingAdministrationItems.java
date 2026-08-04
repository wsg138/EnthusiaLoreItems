package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingMetricsSource;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** Shared rendering primitives for the tracking administration inventories. */
final class PaperTrackingAdministrationItems {
    static final int PREVIOUS = 45;
    static final int STATUS = 49;
    static final int NEXT = 53;

    private PaperTrackingAdministrationItems() {}

    static void decorate(
            Inventory inventory,
            int pageNumber,
            boolean hasMore,
            List<String> statusLore) {
        if (pageNumber > 1) {
            inventory.setItem(
                    PREVIOUS,
                    item(
                            Material.ARROW,
                            "Previous page",
                            List.of("Page " + (pageNumber - 1))));
        }
        if (hasMore) {
            inventory.setItem(
                    NEXT,
                    item(
                            Material.ARROW,
                            "Next page",
                            List.of("Page " + (pageNumber + 1))));
        }
        inventory.setItem(STATUS, item(Material.CLOCK, "Tracking status", statusLore));
    }

    static ItemStack evidenceItem(ObservationChoice choice, DuplicateChoice duplicate) {
        List<String> lore = new ArrayList<>();
        lore.add(describe(choice.location()));
        lore.add("Confidence: " + choice.confidence().name());
        lore.add("Source: " + choice.source());
        if (selectable(choice, duplicate)) {
            lore.add("Click to choose, then confirm.");
        }
        Material material = choice.confidence() == InstanceObservation.Confidence.CONFLICTING
                ? Material.REDSTONE
                : Material.COMPASS;
        return item(material, "Observation " + choice.observationId(), lore);
    }

    static List<String> trackingMetricsLore(Plugin plugin) {
        AnomalyWarningSink sink = plugin.getServer().getServicesManager()
                .load(AnomalyWarningSink.class);
        if (!(sink instanceof TrackingMetricsSource source)) {
            return List.of("Tracking metrics unavailable.");
        }
        TrackingMetrics.Snapshot snapshot = source.trackingMetrics();
        return List.of(
                "Persistence queued: " + snapshot.queued(),
                "Persistence in flight: " + snapshot.inFlight(),
                "Scan backlog: " + snapshot.scanBacklog(),
                "Truncated bounded scans: " + snapshot.scanTruncated(),
                "Accepted/completed: " + snapshot.accepted() + '/' + snapshot.completed(),
                "Rejected/failed/conflicts: " + snapshot.rejected() + '/'
                        + snapshot.failed() + '/' + snapshot.conflicts());
    }

    static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(lore.stream().map(Component::text).toList());
        if (!item.setItemMeta(meta)) {
            throw new IllegalStateException("Could not apply lore-item administration metadata");
        }
        return item;
    }

    static boolean selectable(ObservationChoice observation, DuplicateChoice duplicate) {
        return duplicate != null
                && observation.observedAt() >= duplicate.firstSeenAt()
                && observation.confidence() == InstanceObservation.Confidence.CONFLICTING
                && switch (observation.location().type()) {
                    case PLAYER_INVENTORY,
                            PLAYER_ENDER_CHEST,
                            BLOCK_CONTAINER,
                            DROPPED_ITEM,
                            ITEM_FRAME,
                            ARMOR_STAND,
                            NESTED_CONTAINER -> true;
                    default -> false;
                };
    }

    static String describe(LocationDescriptor location) {
        return location.type().name() + ':' + location.locationKey()
                + (location.containerPath() == null ? "" : ':' + location.containerPath());
    }

    static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }
}
