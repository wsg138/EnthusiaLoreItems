package net.enthusia.loreitems.paper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/** Resolves natural-access moves only when one physical location is observed for an identity. */
public final class PaperUniqueAccessTrackingListener implements Listener, AutoCloseable {
    private static final int UNIQUE_LOCATION_COUNT = 1;
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final String SLOT_PREFIX = "slot:";
    private static final ItemStack[] EMPTY_CONTENTS = new ItemStack[0];

    private final Plugin plugin;
    private final PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
    private final PaperTrackingCoordinator coordinator;
    private boolean closed;

    public PaperUniqueAccessTrackingListener(
            Plugin plugin,
            Supplier<TrackingObservationUseCase> useCaseSupplier,
            IntSupplier budgetSupplier,
            MetricsPort metrics) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.coordinator = new PaperTrackingCoordinator(
                plugin,
                Objects.requireNonNull(useCaseSupplier, "useCaseSupplier"),
                Objects.requireNonNull(budgetSupplier, "budgetSupplier"),
                Objects.requireNonNull(metrics, "metrics"));
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Unique-access tracking listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Optional<InventorySnapshot> top = InventorySnapshot.capture(
                event.getView().getTopInventory());
        scheduleNextTick(() -> submitAccess(playerId, top, "inventory-click-unique"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Optional<InventorySnapshot> top = InventorySnapshot.capture(
                event.getView().getTopInventory());
        scheduleNextTick(() -> submitAccess(playerId, top, "inventory-drag-unique"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Optional<InventorySnapshot> snapshot = InventorySnapshot.capture(top);
        if (snapshot.isPresent()) {
            InventorySnapshot target = snapshot.orElseThrow();
            submitPlayerAndInventory(
                    player,
                    top,
                    target.type(),
                    target.key(),
                    "inventory-close-unique");
        } else {
            submitPlayer(player, false, "inventory-close-unique");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        submitPlayer(event.getPlayer(), true, "player-quit-unique");
    }

    private void submitAccess(
            UUID playerId, Optional<InventorySnapshot> snapshot, String source) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            submitReference(snapshot, source + "-container");
            return;
        }
        if (snapshot.isEmpty()) {
            submitPlayer(player, false, source);
            return;
        }
        InventorySnapshot target = snapshot.orElseThrow();
        Optional<Inventory> inventory = target.resolve(plugin);
        if (inventory.isEmpty()) {
            submitPlayer(player, false, source);
            return;
        }
        submitPlayerAndInventory(
                player, inventory.orElseThrow(), target.type(), target.key(), source);
    }

    private void submitReference(Optional<InventorySnapshot> snapshot, String source) {
        snapshot.flatMap(reference -> reference.resolve(plugin))
                .ifPresent(inventory -> submitInventory(inventory, source));
    }

    void submitPlayer(Player player, boolean lastConfirmed, String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new LinkedHashMap<>();
        collectPlayer(player, observations, limit);
        submitUnique(observations, lastConfirmed, source);
    }

    void submitPlayerAndInventory(
            Player player,
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new LinkedHashMap<>();
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
        submitUnique(observations, false, source);
    }

    private void collectPlayer(
            Player player,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            PaperScanLimit limit) {
        String key = "player:" + player.getUniqueId();
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
        collector.collectArray(
                contentsOrEmpty(player.getEnderChest().getContents()),
                LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                key,
                SLOT_PREFIX,
                observations,
                limit);
    }

    private void submitInventory(Inventory inventory, String source) {
        Optional<InventorySnapshot> snapshot = InventorySnapshot.capture(inventory);
        if (snapshot.isEmpty()) {
            return;
        }
        InventorySnapshot target = snapshot.orElseThrow();
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new LinkedHashMap<>();
        collector.collectArray(
                contentsOrEmpty(inventory.getContents()),
                target.type(),
                target.key(),
                SLOT_PREFIX,
                observations,
                limit);
        submitUnique(observations, false, source);
    }

    private void submitUnique(
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            boolean lastConfirmed,
            String source) {
        observations.forEach((identity, locations) -> {
            TrackingObservationUseCase.Presence presence = lastConfirmed
                    ? TrackingObservationUseCase.Presence.LAST_CONFIRMED
                    : TrackingObservationUseCase.Presence.PRESENT;
            TrackingObservationUseCase.EvidenceMode mode = !lastConfirmed
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

    private static ItemStack[] contentsOrEmpty(ItemStack[] contents) {
        return contents == null ? EMPTY_CONTENTS : contents;
    }

    private void scheduleNextTick(Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule unique lore-item tracking during shutdown.",
                    exception);
        }
    }

    @Override
    public void close() {
        closed = true;
        coordinator.close();
        HandlerList.unregisterAll(this);
    }

    private record InventorySnapshot(
            PaperInventoryReference reference,
            LocationDescriptor.Type type,
            String key) {
        private static Optional<InventorySnapshot> capture(Inventory inventory) {
            Optional<PaperInventoryReference> reference = PaperInventoryReference.capture(inventory);
            if (reference.isEmpty()) {
                return Optional.empty();
            }
            InventoryHolder holder = inventory.getHolder();
            if (holder instanceof Player player) {
                boolean main = inventory instanceof PlayerInventory;
                return Optional.of(new InventorySnapshot(
                        reference.orElseThrow(),
                        main ? LocationDescriptor.Type.PLAYER_INVENTORY
                                : LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                        "player:" + player.getUniqueId()));
            }
            Location location = inventory.getLocation();
            if (location != null && location.getWorld() != null) {
                return Optional.of(new InventorySnapshot(
                        reference.orElseThrow(),
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        PaperInventoryReference.blockKey(location)));
            }
            if (holder instanceof Entity entity) {
                return Optional.of(new InventorySnapshot(
                        reference.orElseThrow(),
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        entity.getWorld().getKey() + ":entity:" + entity.getUniqueId()));
            }
            return Optional.empty();
        }

        private Optional<Inventory> resolve(Plugin plugin) {
            return reference.resolve(plugin);
        }
    }
}
