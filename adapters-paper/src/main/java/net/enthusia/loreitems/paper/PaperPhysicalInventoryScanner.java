package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Bounded inventory and nested-container scanning for physical tracking events. */
final class PaperPhysicalInventoryScanner {
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final int MAX_NESTING_DEPTH = 8;
    private static final String SLOT_PREFIX = "slot:";
    private static final ItemStack[] EMPTY_CONTENTS = new ItemStack[0];

    private final PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
    private final PaperTrackingCoordinator coordinator;

    PaperPhysicalInventoryScanner(PaperTrackingCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    void scanPlayerUnique(Player player, String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        collectPlayer(player, observations, limit);
        submitUnique(observations, source);
    }

    void scanPlayer(
            Player player,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        ScanContext context = new ScanContext(presence, mode, source);
        scanPlayerInventory(player, context, limit);
        scanArray(
                contentsOrEmpty(player.getEnderChest().getContents()),
                LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                playerKey(player),
                SLOT_PREFIX,
                context.withSource(source + "-ender"),
                limit);
    }

    void scanInventory(
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source) {
        scanInventory(
                inventory,
                type,
                key,
                presence,
                mode,
                source,
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
    }

    void scanInventory(
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source,
            PaperScanLimit limit) {
        if (type == null || key == null) {
            return;
        }
        scanArray(
                contentsOrEmpty(inventory.getContents()),
                type,
                key,
                SLOT_PREFIX,
                new ScanContext(presence, mode, source),
                limit);
    }

    void submitMatchingIdentity(
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            LoreItemIdentity identity,
            String source) {
        List<LocationDescriptor> matches = matchingLocations(inventory, type, key, identity);
        TrackingObservationUseCase.EvidenceMode mode = matches.size() == 1
                ? TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                : TrackingObservationUseCase.EvidenceMode.RECONCILIATION;
        matches.forEach(location -> coordinator.submit(
                new TrackingObservationUseCase.Request(
                        identity,
                        location,
                        TrackingObservationUseCase.Presence.PRESENT,
                        mode,
                        source)));
    }

    void submitItem(
            ItemStack item,
            LocationDescriptor location,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source) {
        LoreItemIdentity identity = trackedIdentity(item);
        if (identity != null) {
            coordinator.submit(new TrackingObservationUseCase.Request(
                    identity, location, presence, mode, source));
        }
    }

    LoreItemIdentity trackedIdentity(ItemStack item) {
        return collector.trackedIdentity(item);
    }

    private void collectPlayer(
            Player player,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            PaperScanLimit limit) {
        String key = playerKey(player);
        PlayerInventory inventory = player.getInventory();
        collector.collectArray(
                contentsOrEmpty(inventory.getStorageContents()),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                SLOT_PREFIX,
                observations,
                limit);
        collector.collectArray(
                contentsOrEmpty(inventory.getArmorContents()),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                "armor:",
                observations,
                limit);
        collectPlayerHandsAndCursor(player, inventory, key, observations, limit);
        collector.collectArray(
                contentsOrEmpty(player.getEnderChest().getContents()),
                LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                key,
                SLOT_PREFIX,
                observations,
                limit);
    }

    private void collectPlayerHandsAndCursor(
            Player player,
            PlayerInventory inventory,
            String key,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            PaperScanLimit limit) {
        collector.collectItem(
                inventory.getItemInOffHand(),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                "offhand",
                observations,
                0,
                limit);
        collector.collectItem(
                player.getItemOnCursor(),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                "cursor",
                observations,
                0,
                limit);
    }

    private void submitUnique(
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            String source) {
        observations.forEach((identity, locations) -> {
            TrackingObservationUseCase.EvidenceMode mode = locations.size() == 1
                    ? TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                    : TrackingObservationUseCase.EvidenceMode.RECONCILIATION;
            locations.forEach(location -> coordinator.submit(
                    new TrackingObservationUseCase.Request(
                            identity,
                            location,
                            TrackingObservationUseCase.Presence.PRESENT,
                            mode,
                            source)));
        });
    }

    private void scanPlayerInventory(
            Player player,
            ScanContext context,
            PaperScanLimit limit) {
        String key = playerKey(player);
        PlayerInventory inventory = player.getInventory();
        scanArray(
                contentsOrEmpty(inventory.getStorageContents()),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                SLOT_PREFIX,
                context,
                limit);
        scanArray(
                contentsOrEmpty(inventory.getArmorContents()),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                "armor:",
                context,
                limit);
        scanItem(
                inventory.getItemInOffHand(),
                new LocationDescriptor(LocationDescriptor.Type.PLAYER_INVENTORY, key, "offhand"),
                context,
                0,
                limit);
        scanItem(
                player.getItemOnCursor(),
                new LocationDescriptor(LocationDescriptor.Type.PLAYER_INVENTORY, key, "cursor"),
                context,
                0,
                limit);
    }

    private void scanArray(
            ItemStack[] contents,
            LocationDescriptor.Type type,
            String key,
            String prefix,
            ScanContext context,
            PaperScanLimit limit) {
        for (int slot = 0; slot < contents.length && limit.hasRemaining(); slot++) {
            scanSlot(contents[slot], type, key, prefix + slot, context, limit);
        }
    }

    private void scanSlot(
            ItemStack item,
            LocationDescriptor.Type type,
            String key,
            String path,
            ScanContext context,
            PaperScanLimit limit) {
        if (!scannable(item, limit)) {
            return;
        }
        scanItem(item, new LocationDescriptor(type, key, path), context, 0, limit);
    }

    private void scanItem(
            ItemStack item,
            LocationDescriptor location,
            ScanContext context,
            int depth,
            PaperScanLimit limit) {
        if (!scannable(item, limit)) {
            return;
        }
        limit.consume();
        submitItem(item, location, context.presence(), context.mode(), context.source());
        if (depth >= MAX_NESTING_DEPTH || !limit.hasRemaining()) {
            return;
        }
        scanNested(item.getItemMeta(), location, context, depth, limit);
    }

    private void scanNested(
            ItemMeta meta,
            LocationDescriptor parent,
            ScanContext context,
            int depth,
            PaperScanLimit limit) {
        if (meta instanceof BlockStateMeta blockMeta) {
            scanShulker(blockMeta.getBlockState(), parent, context, depth, limit);
        }
        if (meta instanceof BundleMeta bundle) {
            scanBundle(bundle, parent, context, depth, limit);
        }
    }

    private void scanShulker(
            BlockState blockState,
            LocationDescriptor parent,
            ScanContext context,
            int depth,
            PaperScanLimit limit) {
        if (!(blockState instanceof ShulkerBox shulker)) {
            return;
        }
        ItemStack[] nested = contentsOrEmpty(shulker.getInventory().getContents());
        for (int slot = 0; slot < nested.length && limit.hasRemaining(); slot++) {
            scanItem(
                    nested[slot],
                    nestedLocation(parent, "shulker", slot),
                    context,
                    depth + 1,
                    limit);
        }
    }

    private void scanBundle(
            BundleMeta bundle,
            LocationDescriptor parent,
            ScanContext context,
            int depth,
            PaperScanLimit limit) {
        List<ItemStack> nested = bundle.getItems();
        for (int index = 0; index < nested.size() && limit.hasRemaining(); index++) {
            scanItem(
                    nested.get(index),
                    nestedLocation(parent, "bundle", index),
                    context,
                    depth + 1,
                    limit);
        }
    }

    private List<LocationDescriptor> matchingLocations(
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            LoreItemIdentity identity) {
        List<LocationDescriptor> matches = new ArrayList<>();
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        ItemStack[] contents = contentsOrEmpty(inventory.getContents());
        for (int slot = 0; slot < contents.length && limit.hasRemaining(); slot++) {
            limit.consume();
            if (identity.equals(trackedIdentity(contents[slot]))) {
                matches.add(new LocationDescriptor(type, key, SLOT_PREFIX + slot));
            }
        }
        return matches;
    }

    private static boolean scannable(ItemStack item, PaperScanLimit limit) {
        return item != null && !item.getType().isAir() && limit.hasRemaining();
    }

    static LocationDescriptor nestedLocation(
            LocationDescriptor parent, String container, int index) {
        String path = parent.containerPath() + '/' + container + ':' + index;
        return new LocationDescriptor(
                LocationDescriptor.Type.NESTED_CONTAINER,
                PaperTrackedItemCollector.nestedLocationKey(
                        parent.type(), parent.locationKey()),
                path);
    }

    private static String playerKey(Player player) {
        return "player:" + player.getUniqueId();
    }

    private static ItemStack[] contentsOrEmpty(ItemStack[] contents) {
        return contents == null ? EMPTY_CONTENTS : contents;
    }

    private record ScanContext(
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source) {
        private ScanContext withSource(String updatedSource) {
            return new ScanContext(presence, mode, updatedSource);
        }
    }
}
