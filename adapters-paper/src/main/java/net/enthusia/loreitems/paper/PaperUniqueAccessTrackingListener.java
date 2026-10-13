package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Resolves natural-access moves only when one physical location is observed for an identity. */
public final class PaperUniqueAccessTrackingListener implements Listener, AutoCloseable {
    private static final int UNIQUE_LOCATION_COUNT = 1;
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final int MAX_PENDING_CONTAINERS = 8;
    private static final String ACCESS_SOURCE = "inventory-access-unique";
    private static final String ITEM_FRAME_PATH = "item";
    private static final String SLOT_PREFIX = "slot:";
    private static final ItemStack[] EMPTY_CONTENTS = new ItemStack[0];

    private final Plugin plugin;
    private final PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
    private final PaperUniquePlayerCollector playerCollector = new PaperUniquePlayerCollector(collector);
    private final PaperInventoryEventHint eventHint = new PaperInventoryEventHint(collector);
    private final MetricsPort metrics;
    private final Map<UUID, PendingAccess> pendingAccess = new HashMap<>();
    private final PaperTrackingCoordinator coordinator;
    private final PaperDeferredMainThreadActions deferredActions;
    private final Set<UUID> quittingPlayers = new HashSet<>();
    private boolean closed;

    public PaperUniqueAccessTrackingListener(
            Plugin plugin,
            Supplier<TrackingObservationUseCase> useCaseSupplier,
            IntSupplier budgetSupplier,
            MetricsPort metrics) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.coordinator = new PaperTrackingCoordinator(
                plugin,
                Objects.requireNonNull(useCaseSupplier, "useCaseSupplier"),
                Objects.requireNonNull(budgetSupplier, "budgetSupplier"),
                Objects.requireNonNull(metrics, "metrics"));
        this.deferredActions = new PaperDeferredMainThreadActions(
                plugin,
                "Could not schedule unique lore-item tracking during shutdown.");
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Unique-access tracking listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        long started = System.nanoTime();
        try {
            if (event.getWhoClicked() instanceof Player player) {
                if (eventHint.relevant(event, player)) {
                    queueAccess(player, event.getView().getTopInventory());
                } else {
                    metrics.increment("tracking.unique.plain_click_skipped");
                }
            }
        } finally {
            metrics.recordDurationNanos("tracking.unique.click_event_nanos",
                    System.nanoTime() - started);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrag(InventoryDragEvent event) {
        long started = System.nanoTime();
        try {
            if (event.getWhoClicked() instanceof Player player) {
                if (eventHint.relevant(event)) {
                    queueAccess(player, event.getView().getTopInventory());
                } else {
                    metrics.increment("tracking.unique.plain_drag_skipped");
                }
            }
        } finally {
            metrics.recordDurationNanos("tracking.unique.drag_event_nanos",
                    System.nanoTime() - started);
        }
    }

    private void queueAccess(Player player, Inventory top) {
        UUID playerId = player.getUniqueId();
        Optional<PaperPhysicalInventorySnapshot> snapshot = PaperPhysicalInventorySnapshot.capture(top);
        PendingAccess pending = pendingAccess.get(playerId);
        if (pending != null) {
            if (!pending.add(snapshot)) {
                metrics.increment("tracking.unique.capture_incomplete");
            }
            metrics.increment("tracking.unique.access_coalesced");
            return;
        }
        PendingAccess access = new PendingAccess();
        if (!access.add(snapshot)) {
            metrics.increment("tracking.unique.capture_incomplete");
        }
        pendingAccess.put(playerId, access);
        if (!deferredActions.schedule(() -> drainAccess(playerId))) {
            pendingAccess.remove(playerId);
            metrics.increment("tracking.unique.schedule_rejected");
            plugin.getLogger().warning(
                    "Lore-item inventory movement unresolved: post-event scheduling failed.");
            return;
        }
        metrics.increment("tracking.unique.access_scheduled");
    }

    private void drainAccess(UUID playerId) {
        drainAccess(playerId, false);
    }

    private void drainAccess(UUID playerId, boolean beforeLogoutOrClose) {
        PendingAccess pending = pendingAccess.remove(playerId);
        if (pending == null || quittingPlayers.contains(playerId)) {
            return;
        }
        long started = System.nanoTime();
        try {
            Player player = plugin.getServer().getPlayer(playerId);
            PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
            Map<LoreItemIdentity, List<LocationDescriptor>> observed = new HashMap<>();
            // Close/quit is a lifecycle boundary, not proof of a completed click.
            boolean complete = pending.complete && !beforeLogoutOrClose;
            if (player != null) {
                collectPlayer(player, observed, limit);
            } else {
                complete = false;
            }
            complete &= collectPendingContainers(player, pending, observed, limit);
            if (!limit.hasRemaining() || !complete) {
                metrics.increment("tracking.unique.incomplete_reconciliation");
            }
            submitUnique(observed, false, complete && limit.hasRemaining(), ACCESS_SOURCE);
        } finally {
            metrics.recordDurationNanos("tracking.unique.access_deferred_nanos",
                    System.nanoTime() - started);
        }
    }

    /** Report missing inventory coverage without discarding already observed evidence. */
    private boolean collectPendingContainers(
            Player player,
            PendingAccess pending,
            Map<LoreItemIdentity, List<LocationDescriptor>> observed,
            PaperScanLimit limit) {
        boolean complete = true;
        for (PaperPhysicalInventorySnapshot reference : pending.containers) {
            if (player != null && coveredByPlayer(player, reference.type(), reference.key())) {
                continue;
            }
            Optional<Inventory> inventory = reference.resolve(plugin);
            if (inventory.isEmpty()) {
                complete = false;
                metrics.increment("tracking.unique.inventory_unresolved");
                continue;
            }
            collector.collectArray(contentsOrEmpty(inventory.orElseThrow().getContents()),
                    reference.type(), reference.key(), SLOT_PREFIX, observed, limit);
        }
        return complete;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || quittingPlayers.contains(player.getUniqueId())) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PendingAccess pending = pendingAccess.get(playerId);
        if (pending != null) {
            // The click's next-tick job will re-resolve the chest.
            // Closing is not proof that the click committed.
            pending.complete = false;
            metrics.increment("tracking.unique.access_close_deferred");
            return;
        }
        Optional<PaperPhysicalInventorySnapshot> reference =
                PaperPhysicalInventorySnapshot.capture(event.getView().getTopInventory());
        // Only reconciliation evidence is safe at this lifecycle boundary.
        submitPlayerReconciliation(player, "inventory-close-unique");
        reference.ifPresent(snapshot -> scheduleNextTick(() -> collectClosedContainer(snapshot)));
    }

    private void collectClosedContainer(PaperPhysicalInventorySnapshot snapshot) {
        Optional<Inventory> resolved = snapshot.resolve(plugin);
        if (resolved.isEmpty()) {
            metrics.increment("tracking.unique.inventory_unresolved");
            return;
        }
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observed = new HashMap<>();
        collector.collectArray(contentsOrEmpty(resolved.orElseThrow().getContents()),
                snapshot.type(), snapshot.key(), SLOT_PREFIX, observed, limit);
        submitUnique(observed, false, false, "inventory-close-unique");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        scheduleDisplayAccess(
                event.getPlayer().getUniqueId(),
                event.getItemFrame().getUniqueId(),
                LocationDescriptor.Type.ITEM_FRAME,
                ITEM_FRAME_PATH,
                null,
                "item-frame-change-unique");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        EquipmentSlot slot = event.getSlot();
        scheduleDisplayAccess(
                event.getPlayer().getUniqueId(),
                event.getRightClicked().getUniqueId(),
                LocationDescriptor.Type.ARMOR_STAND,
                slotPath(slot),
                slot,
                "armor-stand-manipulate-unique");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (pendingAccess.containsKey(playerId)) {
            drainAccess(playerId, true);
            metrics.increment("tracking.unique.access_drained_on_quit");
        }
        quittingPlayers.add(playerId);
        submitPlayer(event.getPlayer(), true, "player-quit-unique");
        scheduleNextTick(() -> quittingPlayers.remove(playerId));
    }

    private void scheduleDisplayAccess(
            UUID playerId,
            UUID displayId,
            LocationDescriptor.Type type,
            String path,
            EquipmentSlot slot,
            String source) {
        scheduleNextTick(() -> submitDisplayAccess(
                playerId, displayId, type, path, slot, source));
    }

    private void submitDisplayAccess(
            UUID playerId,
            UUID displayId,
            LocationDescriptor.Type type,
            String path,
            EquipmentSlot slot,
            String source) {
        Player player = plugin.getServer().getPlayer(playerId);
        Entity display = plugin.getServer().getEntity(displayId);
        ItemStack item = displayItem(display, type, slot);
        if (player == null) {
            submitDisplay(item, display, type, path, source);
            return;
        }
        if (display == null) {
            submitPlayerReconciliation(player, source);
            return;
        }
        submitPlayerAndDisplay(
                player,
                item,
                PaperDisplayEntityScanner.location(display, type, path),
                source);
    }

    private void submitDisplay(
            ItemStack item,
            Entity display,
            LocationDescriptor.Type type,
            String path,
            String source) {
        if (display == null) {
            return;
        }
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        collectItem(item, PaperDisplayEntityScanner.location(display, type, path), observations, limit);
        submitUnique(observations, false, false, source);
    }

    private static ItemStack displayItem(
            Entity display, LocationDescriptor.Type type, EquipmentSlot slot) {
        if (type == LocationDescriptor.Type.ITEM_FRAME && display instanceof ItemFrame frame) {
            return frame.getItem();
        }
        if (type == LocationDescriptor.Type.ARMOR_STAND
                && display instanceof ArmorStand stand
                && slot != null) {
            return stand.getEquipment().getItem(slot);
        }
        return null;
    }

    private void submitPlayerReconciliation(Player player, String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new HashMap<>();
        collectPlayer(player, observations, limit);
        submitUnique(observations, false, false, source);
    }

    void submitPlayer(Player player, boolean lastConfirmed, String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        collectPlayer(player, observations, limit);
        submitUnique(observations, lastConfirmed, limit.hasRemaining(), source);
    }

    void submitPlayerAndInventory(
            Player player,
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        collectPlayer(player, observations, limit);
        if (!coveredByPlayer(player, type, key)) {
            collector.collectArray(
                    contentsOrEmpty(inventory.getContents()),
                    type,
                    key,
                    SLOT_PREFIX,
                    observations,
                    limit);
        }
        submitUnique(observations, false, limit.hasRemaining(), source);
    }

    void submitPlayerAndDisplay(
            Player player,
            ItemStack item,
            LocationDescriptor location,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        collectPlayer(player, observations, limit);
        collectItem(item, location, observations, limit);
        submitUnique(observations, false, limit.hasRemaining(), source);
    }

    private void collectItem(
            ItemStack item,
            LocationDescriptor location,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            PaperScanLimit limit) {
        collector.collectItem(
                item,
                location.type(),
                location.locationKey(),
                location.containerPath(),
                observations,
                0,
                limit);
    }

    private void collectPlayer(
            Player player,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            PaperScanLimit limit) {
        playerCollector.collectPlayer(player, observations, limit);
    }

    private void submitUnique(
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            boolean lastConfirmed,
            boolean scanWithinBudget,
            String source) {
        observations.forEach((identity, locations) -> {
            TrackingObservationUseCase.Presence presence = lastConfirmed
                    ? TrackingObservationUseCase.Presence.LAST_CONFIRMED
                    : TrackingObservationUseCase.Presence.PRESENT;
            TrackingObservationUseCase.EvidenceMode mode = !lastConfirmed
                            && scanWithinBudget
                            && locations.size() == UNIQUE_LOCATION_COUNT
                    ? TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                    : TrackingObservationUseCase.EvidenceMode.RECONCILIATION;
            locations.forEach(location -> coordinator.submit(
                    new TrackingObservationUseCase.Request(
                            identity,
                            location,
                            presence,
                            mode,
                            source)));
        });
    }

    private static boolean coveredByPlayer(
            Player player, LocationDescriptor.Type type, String key) {
        String playerKey = "player:" + player.getUniqueId();
        return playerKey.equals(key)
                && (type == LocationDescriptor.Type.PLAYER_INVENTORY
                        || type == LocationDescriptor.Type.PLAYER_ENDER_CHEST);
    }

    private static String slotPath(EquipmentSlot slot) {
        return SLOT_PREFIX + slot.name().toLowerCase(Locale.ROOT);
    }

    private static ItemStack[] contentsOrEmpty(ItemStack[] contents) {
        return contents == null ? EMPTY_CONTENTS : contents;
    }

    private void scheduleNextTick(Runnable action) {
        deferredActions.schedule(action);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        HandlerList.unregisterAll(this);
        deferredActions.close();
        pendingAccess.clear();
        quittingPlayers.clear();
        coordinator.close();
    }

    private static final class PendingAccess {
        private final List<PaperPhysicalInventorySnapshot> containers = new ArrayList<>();
        private boolean complete = true;

        private boolean add(Optional<PaperPhysicalInventorySnapshot> snapshot) {
            if (snapshot.isEmpty()) {
                complete = false;
                return false;
            }
            PaperPhysicalInventorySnapshot reference = snapshot.orElseThrow();
            if (containers.contains(reference)) {
                return true;
            }
            if (containers.size() == MAX_PENDING_CONTAINERS) {
                complete = false;
                return false;
            }
            containers.add(reference);
            return true;
        }
    }

}
