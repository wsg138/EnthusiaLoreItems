package net.enthusia.loreitems.paper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

/** Bounded inventory and nested-container scanning for physical tracking events. */
final class PaperPhysicalInventoryScanner {
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final int MAX_NESTING_DEPTH = 8;
    private final PaperBoundedInventoryContinuation continuation =
            new PaperBoundedInventoryContinuation();
    private final PaperPagedInventoryAccess pagedAccess = new PaperPagedInventoryAccess();
    private static final String SLOT_PREFIX = "slot:";
    private static final ItemStack[] EMPTY_CONTENTS = new ItemStack[0];

    private final PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
    private final PaperTrackingCoordinator coordinator;

    PaperPhysicalInventoryScanner(PaperTrackingCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    void scanPlayerUnique(Player player, String source) {
        // Preserve bounded immediate-player regressions until W07 schedules
        // continuation. A 256-unit partial scan never grants uniqueness.
        PaperScanLimit limit = new PaperScanLimit(512);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new HashMap<>();
        collectPlayer(player, observations, limit);
        submitUnique(observations, limit.hasRemaining(), source);
    }

    void scanPlayer(
            Player player,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        ScanContext context = new ScanContext(presence, reconciledMode(mode), source);
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
                new ScanContext(presence, reconciledMode(mode), source),
                limit);
    }

    /**
     * Stateless page for Worker 07's loaded-only scheduler. Only an entire
     * same-page complete result is eligible for uniqueness; multi-page
     * observations must remain RECONCILIATION until independently fenced.
     */
    PaperBoundedInventoryContinuation.Page scanBounded(
            Inventory inventory, LocationDescriptor.Type type, String key,
            long generation, String geometry,
            PaperBoundedInventoryContinuation.Cursor cursor, PaperScanLimit limit) {
        return scanBounded(inventory, type, key, generation, 0L, geometry, cursor, limit);
    }

    /** mutationFence must change on every known dirty event; never force-load. */
    PaperBoundedInventoryContinuation.Page scanBounded(
            Inventory inventory, LocationDescriptor.Type type, String key,
            long generation, long mutationFence, String geometry,
            PaperBoundedInventoryContinuation.Cursor cursor, PaperScanLimit limit) {
        return continuation.scan(
                inventory, type, key, generation, mutationFence, geometry, cursor, limit);
    }

    void scanItemTree(
            ItemStack item,
            LocationDescriptor location,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source,
            PaperScanLimit limit) {
        // Entity/display callers have already charged their source visit to
        // the shared limit. Do not charge the same root twice.
        scanAcceptedItem(item, location,
                new ScanContext(presence, reconciledMode(mode), source), 0, limit);
    }

    void submitMatchingIdentity(
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            LoreItemIdentity identity,
            String source) {
        submitMatchingIdentities(inventory, type, key, Set.of(identity), source);
    }

    /** One bounded destination snapshot serves every identity from the same move. */
    void submitMatchingIdentities(
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            Set<LoreItemIdentity> identities,
            String source) {
        Objects.requireNonNull(identities, "identities");
        if (identities.isEmpty()) {
            return;
        }
        MatchingLocations matching = matchingLocations(inventory, type, key);
        identities.forEach(identity -> submitMatches(
                identity,
                matching.observations().getOrDefault(identity, List.of()),
                matching.scanWithinBudget(),
                source));
    }

    /** Full evidence coverage is unknown until after traversal; never promote a partial scan. */
    private static TrackingObservationUseCase.EvidenceMode reconciledMode(
            TrackingObservationUseCase.EvidenceMode requested) {
        return requested == TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                ? TrackingObservationUseCase.EvidenceMode.RECONCILIATION : requested;
    }

    private void submitMatches(
            LoreItemIdentity identity,
            List<LocationDescriptor> matches,
            boolean scanWithinBudget,
            String source) {
        TrackingObservationUseCase.EvidenceMode mode = scanWithinBudget
                        && matches.size() == 1
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

    /** True for tracked, partial/malformed or nested identity evidence. */
    boolean hasIdentityEvidence(ItemStack item) {
        return collector.hasIdentityEvidence(item);
    }

    /** An empty identity set is NOT negative proof unless coverage is complete. */
    record IdentityEvidence(Set<LoreItemIdentity> identities,
                            PaperBoundedInventoryContinuation.State state) {
        IdentityEvidence {
            identities = Set.copyOf(identities);
        }

        boolean coverageComplete() {
            return state == PaperBoundedInventoryContinuation.State.COMPLETE;
        }
    }

    IdentityEvidence inspectIdentities(ItemStack item) {
        PaperBoundedInventoryContinuation.Page page = continuation.scanSlots(
                1, slot -> item, LocationDescriptor.Type.NESTED_CONTAINER,
                "identity-tree", 0L, "single-item", null,
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        return new IdentityEvidence(page.observations().keySet(), page.state());
    }

    /** Legacy callers MUST NOT interpret an empty set as verified absence. */
    Set<LoreItemIdentity> trackedIdentities(ItemStack item) {
        return inspectIdentities(item).identities();
    }

    /** Emit every discovered copy as reconciliation, even across several pages. */
    void submitPageObservations(PaperBoundedInventoryContinuation.Page page, String source) {
        Objects.requireNonNull(page, "page");
        submitReconciliation(page.observations(), source);
    }

    /** W06 player/cursor and container access call-site hook. */
    PaperPagedInventoryAccess.Batch scanAccessSources(
            List<PaperPagedInventoryAccess.Source> currentSources,
            long generation, long mutationFence, PaperPagedInventoryAccess.Cursor cursor,
            PaperScanLimit limit) {
        return pagedAccess.scan(currentSources, generation, mutationFence, cursor, limit);
    }

    void submitAccessBatch(PaperPagedInventoryAccess.Batch batch, String source) {
        Objects.requireNonNull(batch, "batch");
        submitReconciliation(batch.observations(), source);
    }

    private void submitReconciliation(
            Map<LoreItemIdentity, List<LocationDescriptor>> observations, String source) {
        observations.forEach((identity, locations) ->
                locations.forEach(location -> coordinator.submit(
                        new TrackingObservationUseCase.Request(identity, location,
                                TrackingObservationUseCase.Presence.PRESENT,
                                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                                source))));
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
            boolean scanWithinBudget,
            String source) {
        observations.forEach((identity, locations) -> {
            TrackingObservationUseCase.EvidenceMode mode = scanWithinBudget
                            && locations.size() == 1
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
        for (int slot = 0; slot < contents.length; slot++) {
            if (!limit.canVisit()) {
                limit.markTruncated();
                return;
            }
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
        // Every top-level visit, including an empty slot, consumes work.
        if (!limit.tryConsume() || !scannable(item)) {
            return;
        }
        scanAcceptedItem(item, new LocationDescriptor(type, key, path), context, 0, limit);
    }

    private void scanItem(
            ItemStack item,
            LocationDescriptor location,
            ScanContext context,
            int depth,
            PaperScanLimit limit) {
        if (limit.tryConsume()) {
            scanAcceptedItem(item, location, context, depth, limit);
        }
    }

    private void scanAcceptedItem(
            ItemStack item,
            LocationDescriptor location,
            ScanContext context,
            int depth,
            PaperScanLimit limit) {
        if (!scannable(item)) {
            return;
        }
        submitItem(item, location, context.presence(), context.mode(), context.source());
        // Materialize the container metadata at most once, without recursively
        // probing all leaves ahead of the work budget.
        PaperTrackedItemCollector.NestedEvidence evidence = collector.nestedContents(item);
        if (evidence == null) {
            return;
        }
        if (depth >= MAX_NESTING_DEPTH) {
            limit.markTruncated();
            return;
        }
        scanNested(evidence, location, context, depth, limit);
    }

    private void scanNested(
            PaperTrackedItemCollector.NestedEvidence evidence,
            LocationDescriptor parent,
            ScanContext context,
            int depth,
            PaperScanLimit limit) {
        if (evidence.meta() instanceof BlockStateMeta) {
            scanShulker(evidence.blockState(), parent, context, depth, limit);
        }
        if (evidence.meta() instanceof BundleMeta bundle) {
            scanBundle(bundle, parent, context, depth, limit);
        }
    }

    private void scanShulker(
            BlockState blockState,
            LocationDescriptor parent,
            ScanContext context,
            int depth,
            PaperScanLimit limit) {
        if (!(blockState instanceof ShulkerBox shulker) || !limit.tryConsume()) {
            return;
        }
        ItemStack[] nested = contentsOrEmpty(shulker.getInventory().getContents());
        for (int slot = 0; slot < nested.length; slot++) {
            if (!limit.canVisit()) {
                limit.markTruncated();
                return;
            }
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
        if (!limit.tryConsume()) {
            return;
        }
        List<ItemStack> nested = bundle.getItems();
        for (int index = 0; index < nested.size(); index++) {
            if (!limit.canVisit()) {
                limit.markTruncated();
                return;
            }
            scanItem(
                    nested.get(index),
                    nestedLocation(parent, "bundle", index),
                    context,
                    depth + 1,
                    limit);
        }
    }

    private MatchingLocations matchingLocations(
            Inventory inventory,
            LocationDescriptor.Type type,
            String key) {
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new HashMap<>();
        // Legacy immediate move reconciliation still reads one snapshot per
        // event. Use a finite cap that covers the pre-existing 270-leaf
        // nested regression; overflow remains non-authoritative.
        PaperScanLimit limit = new PaperScanLimit(512);
        collector.collectArray(
                contentsOrEmpty(inventory.getContents()),
                type,
                key,
                SLOT_PREFIX,
                observations,
                limit);
        return new MatchingLocations(observations, limit.hasRemaining());
    }

    private static boolean scannable(ItemStack item) {
        return item != null && !item.getType().isAir();
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

    private record MatchingLocations(
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            boolean scanWithinBudget) {}

    private record ScanContext(
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source) {
        private ScanContext withSource(String updatedSource) {
            return new ScanContext(presence, mode, updatedSource);
        }
    }
}
