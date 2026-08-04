package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
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
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;

/** Resolves natural-access moves only when one physical location is observed for an identity. */
public final class PaperUniqueAccessTrackingListener implements Listener, AutoCloseable {
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final int MAX_NESTING_DEPTH = 8;
    private static final String SLOT_PREFIX = "slot:";

    private final Plugin plugin;
    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
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
        InventoryReference top = InventoryReference.capture(event.getView().getTopInventory());
        scheduleNextTick(() -> {
            Player current = plugin.getServer().getPlayer(playerId);
            if (current != null) {
                submitPlayer(current, false, "inventory-click-unique");
            }
            submitReference(top, "inventory-click-container-unique");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        InventoryReference top = InventoryReference.capture(event.getView().getTopInventory());
        scheduleNextTick(() -> {
            Player current = plugin.getServer().getPlayer(playerId);
            if (current != null) {
                submitPlayer(current, false, "inventory-drag-unique");
            }
            submitReference(top, "inventory-drag-container-unique");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        submitPlayer(player, false, "inventory-close-unique");
        Inventory top = event.getView().getTopInventory();
        if (top != player.getInventory()) {
            submitInventory(top, "inventory-close-container-unique");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        submitPlayer(event.getPlayer(), true, "player-quit-unique");
    }

    private void submitReference(InventoryReference reference, String source) {
        if (reference == null) {
            return;
        }
        Inventory inventory = reference.resolve(plugin);
        if (inventory != null) {
            submitInventory(inventory, source);
        }
    }

    private void submitPlayer(Player player, boolean lastConfirmed, String source) {
        ScanLimit limit = new ScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new HashMap<>();
        String key = "player:" + player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        collectArray(inventory.getStorageContents(), LocationDescriptor.Type.PLAYER_INVENTORY,
                key, SLOT_PREFIX, observations, limit);
        collectArray(inventory.getArmorContents(), LocationDescriptor.Type.PLAYER_INVENTORY,
                key, "armor:", observations, limit);
        collectItem(inventory.getItemInOffHand(), LocationDescriptor.Type.PLAYER_INVENTORY,
                key, "offhand", observations, 0, limit);
        collectItem(player.getItemOnCursor(), LocationDescriptor.Type.PLAYER_INVENTORY,
                key, "cursor", observations, 0, limit);
        collectArray(player.getEnderChest().getContents(),
                LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                key, SLOT_PREFIX, observations, limit);
        submitUnique(observations, lastConfirmed, source);
    }

    private void submitInventory(Inventory inventory, String source) {
        InventoryReference reference = InventoryReference.capture(inventory);
        if (reference == null) {
            return;
        }
        ScanLimit limit = new ScanLimit(MAX_ITEMS_PER_SCAN);
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new HashMap<>();
        collectArray(inventory.getContents(), reference.type(), reference.key(),
                SLOT_PREFIX, observations, limit);
        submitUnique(observations, false, source);
    }

    private void submitUnique(
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            boolean lastConfirmed,
            String source) {
        observations.forEach((identity, locations) -> {
            if (locations.size() == 1) {
                LocationDescriptor location = locations.getFirst();
                coordinator.submit(new TrackingObservationUseCase.Request(
                        identity,
                        location,
                        TrackingObservationUseCase.Presence.PRESENT,
                        TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                        source));
                if (lastConfirmed) {
                    coordinator.submit(new TrackingObservationUseCase.Request(
                            identity,
                            location,
                            TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                            TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                            source));
                }
                return;
            }
            locations.forEach(location -> coordinator.submit(
                    new TrackingObservationUseCase.Request(
                            identity,
                            location,
                            TrackingObservationUseCase.Presence.PRESENT,
                            TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                            source)));
        });
    }

    private void collectArray(
            ItemStack[] contents,
            LocationDescriptor.Type type,
            String key,
            String prefix,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            ScanLimit limit) {
        for (int index = 0; index < contents.length && limit.hasRemaining(); index++) {
            collectItem(contents[index], type, key, prefix + index,
                    observations, 0, limit);
        }
    }

    private void collectItem(
            ItemStack item,
            LocationDescriptor.Type type,
            String key,
            String path,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            ScanLimit limit) {
        if (item == null || item.getType().isAir() || !limit.hasRemaining()) {
            return;
        }
        limit.consume();
        LoreItemIdentity identity = trackedIdentity(item);
        if (identity != null) {
            observations.computeIfAbsent(identity, ignored -> new ArrayList<>())
                    .add(new LocationDescriptor(type, key, path));
        }
        if (depth >= MAX_NESTING_DEPTH || !limit.hasRemaining()) {
            return;
        }
        if (item.getItemMeta() instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof ShulkerBox shulker) {
            collectNested(shulker.getInventory().getContents(), key,
                    path + "/shulker:", observations, depth, limit);
        }
        if (item.getItemMeta() instanceof BundleMeta bundle) {
            collectNested(bundle.getItems().toArray(ItemStack[]::new), key,
                    path + "/bundle:", observations, depth, limit);
        }
    }

    private void collectNested(
            ItemStack[] contents,
            String key,
            String prefix,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            ScanLimit limit) {
        for (int index = 0; index < contents.length && limit.hasRemaining(); index++) {
            collectItem(contents[index], LocationDescriptor.Type.NESTED_CONTAINER,
                    key, prefix + index, observations, depth + 1, limit);
        }
    }

    private LoreItemIdentity trackedIdentity(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        return result instanceof ItemIdentityReadResult.Tracked tracked
                ? tracked.identity()
                : null;
    }

    private void scheduleNextTick(Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE,
                    "Could not schedule unique lore-item tracking during shutdown.", exception);
        }
    }

    @Override
    public void close() {
        closed = true;
        coordinator.close();
        HandlerList.unregisterAll(this);
    }

    private enum InventoryKind {
        PLAYER_MAIN,
        PLAYER_ENDER,
        BLOCK,
        ENTITY
    }

    private record InventoryReference(
            InventoryKind kind,
            UUID holderId,
            UUID worldId,
            int x,
            int y,
            int z,
            LocationDescriptor.Type type,
            String key) {
        private static InventoryReference capture(Inventory inventory) {
            if (inventory == null) {
                return null;
            }
            InventoryHolder holder = inventory.getHolder();
            if (holder instanceof Player player) {
                boolean main = inventory instanceof PlayerInventory;
                return new InventoryReference(
                        main ? InventoryKind.PLAYER_MAIN : InventoryKind.PLAYER_ENDER,
                        player.getUniqueId(), null, 0, 0, 0,
                        main ? LocationDescriptor.Type.PLAYER_INVENTORY
                                : LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                        "player:" + player.getUniqueId());
            }
            Location location = inventory.getLocation();
            if (location != null && location.getWorld() != null) {
                return new InventoryReference(
                        InventoryKind.BLOCK,
                        null,
                        location.getWorld().getUID(),
                        location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        blockKey(location));
            }
            if (holder instanceof Entity entity) {
                return new InventoryReference(
                        InventoryKind.ENTITY,
                        entity.getUniqueId(),
                        null, 0, 0, 0,
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        entity.getWorld().getKey() + ":entity:" + entity.getUniqueId());
            }
            return null;
        }

        private Inventory resolve(Plugin plugin) {
            return switch (kind) {
                case PLAYER_MAIN -> {
                    Player player = plugin.getServer().getPlayer(holderId);
                    yield player == null ? null : player.getInventory();
                }
                case PLAYER_ENDER -> {
                    Player player = plugin.getServer().getPlayer(holderId);
                    yield player == null ? null : player.getEnderChest();
                }
                case BLOCK -> {
                    World world = plugin.getServer().getWorld(worldId);
                    if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
                        yield null;
                    }
                    BlockState state = world.getBlockAt(x, y, z).getState();
                    yield state instanceof Container container
                            ? container.getInventory()
                            : null;
                }
                case ENTITY -> {
                    Entity entity = plugin.getServer().getEntity(holderId);
                    yield entity instanceof InventoryHolder inventoryHolder
                            ? inventoryHolder.getInventory()
                            : null;
                }
            };
        }

        private static String blockKey(Location location) {
            return Objects.requireNonNull(location.getWorld(), "location world")
                            .getKey().toString()
                    + ':' + location.getBlockX()
                    + ':' + location.getBlockY()
                    + ':' + location.getBlockZ();
        }
    }

    private static final class ScanLimit {
        private int remaining;

        private ScanLimit(int remaining) {
            this.remaining = remaining;
        }

        private boolean hasRemaining() {
            return remaining > 0;
        }

        private void consume() {
            if (remaining > 0) {
                remaining--;
            }
        }
    }
}
