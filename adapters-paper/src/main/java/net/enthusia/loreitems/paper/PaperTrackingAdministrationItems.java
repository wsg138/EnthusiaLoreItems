package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingMetricsSource;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** Shared rendering primitives for the tracking administration inventories. */
final class PaperTrackingAdministrationItems {
    static final int BACK = 45;
    static final int PREVIOUS = 48;
    static final int STATUS = 49;
    static final int NEXT = 50;
    private static final int FIRST_PAGE = 1;

    private PaperTrackingAdministrationItems() {}

    static void decorate(
            Inventory inventory,
            int pageNumber,
            boolean hasMore,
            List<String> statusLore) {
        if (pageNumber > FIRST_PAGE) {
            inventory.setItem(
                    PREVIOUS,
                    item(
                            Material.ARROW,
                            "Previous page",
                            PaperGuiStyle.Tone.NAVIGATION,
                            List.of("Go to page " + (pageNumber - FIRST_PAGE) + '.')));
        }
        if (hasMore) {
            inventory.setItem(
                    NEXT,
                    item(
                            Material.ARROW,
                            "Next page",
                            PaperGuiStyle.Tone.NAVIGATION,
                            List.of("Go to page " + (pageNumber + FIRST_PAGE) + '.')));
        }
        inventory.setItem(
                STATUS,
                item(
                        Material.CLOCK,
                        "Tracking status",
                        PaperGuiStyle.Tone.METADATA,
                        statusLore));
    }

    static void decorateNested(
            Inventory inventory,
            int pageNumber,
            boolean hasMore,
            String backLabel,
            List<String> backLore,
            List<String> statusLore) {
        inventory.setItem(
                BACK,
                item(
                        Material.ARROW,
                        backLabel,
                        PaperGuiStyle.Tone.NAVIGATION,
                        backLore));
        decorate(inventory, pageNumber, hasMore, statusLore);
    }

    static ItemStack evidenceItem(ObservationChoice choice, DuplicateChoice duplicate) {
        List<String> lore = new ArrayList<>();
        lore.add(describe(choice.location()));
        lore.add("Confidence: " + humanize(choice.confidence().name()));
        lore.add("Source: " + choice.source());
        if (selectable(choice, duplicate)) {
            lore.add("");
            lore.add("Click to select this location.");
            lore.add("You will review it before anything changes.");
        }
        boolean conflicting =
                choice.confidence() == InstanceObservation.Confidence.CONFLICTING;
        Material material = conflicting ? Material.REDSTONE : Material.COMPASS;
        PaperGuiStyle.Tone tone =
                conflicting ? PaperGuiStyle.Tone.WARNING : PaperGuiStyle.Tone.INFO;
        return item(material, "Observation " + choice.observationId(), tone, lore);
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
        return item(material, name, PaperGuiStyle.Tone.INFO, lore);
    }

    static ItemStack item(
            Material material,
            String name,
            PaperGuiStyle.Tone tone,
            List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(PaperGuiStyle.name(name, tone));
        meta.lore(lore.stream().map(PaperGuiStyle::lore).toList());
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
        StringBuilder description = new StringBuilder(humanize(location.type().name()))
                .append(": ")
                .append(location.locationKey());
        if (location.containerPath() != null) {
            description.append(" • ").append(location.containerPath());
        }
        return description.toString();
    }

    static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String humanize(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
