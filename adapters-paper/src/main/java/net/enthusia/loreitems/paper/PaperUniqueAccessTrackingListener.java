package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
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
import java.util.logging.Level;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
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
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/** Resolves natural-access moves only when one physical location is observed for an identity. */
public final class PaperUniqueAccessTrackingListener implements Listener, AutoCloseable {
    private static final int UNIQUE_LOCATION_COUNT = 1;
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final String ITEM_FRAME_PATH = "item";
    private static final String SLOT_PREFIX = "slot:";
    private static final ItemStack[] EMPTY_CONTENTS = new ItemStack[0];

    private final Plugin plugin;
    private final PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
    private final PaperTrackingCoordinator coordinator;
    private final Set<UUID> quittingPlayers = new HashSet<>();
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
        if (quittingPlayers.contains(player.getUniqueId())) {
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
            submitPlayer(player, false, source);
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
        submitUnique(observations, false, limit.hasRemaining(), source);
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
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        collector.collectArray(
                contentsOrEmpty(inventory.getContents()),
                target.type(),
                target.key(),
                SLOT_PREFIX,
                observations,
                limit);
        submitUnique(observations, false, limit.hasRemaining(), source);
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
        quittingPlayers.clear();
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
            if (holder instanceof Entity entity) {
                return Optional.of(new InventorySnapshot(
                        reference.orElseThrow(),
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        entity.getWorld().getKey() + ":entity:" + entity.getUniqueId()));
            }
            Location location = inventory.getLocation();
            if (location != null && location.getWorld() != null) {
                return Optional.of(new InventorySnapshot(
                        reference.orElseThrow(),
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        PaperInventoryReference.blockKey(location)));
            }
            return Optional.empty();
        }

        private Optional<Inventory> resolve(Plugin plugin) {
            return reference.resolve(plugin);
        }
    }
}
