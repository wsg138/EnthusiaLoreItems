package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Event-driven physical tracking with bounded natural-access reconciliation. */
public final class PaperPhysicalTrackingListener implements Listener, AutoCloseable {
    private static final int MIN_BUDGET = 1;
    private static final int QUEUE_MULTIPLIER = 32;
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final int MAX_NESTING_DEPTH = 8;
    private static final long CHUNK_SEED_PERIOD_TICKS = 100L;
    private static final String SLOT_PREFIX = "slot:";

    private final Plugin plugin;
    private final IntSupplier budgetSupplier;
    private final MetricsPort metrics;
    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    private final PaperTrackingCoordinator coordinator;
    private final Queue<ScanRequest> scans = new ArrayDeque<>();
    private final Queue<ChunkReference> loadedChunks = new ArrayDeque<>();
    private final Set<UUID> deathDrops = new HashSet<>();

    private BukkitTask scanTask;
    private BukkitTask seedTask;
    private boolean closed;

    public PaperPhysicalTrackingListener(
            Plugin plugin,
            Supplier<TrackingObservationUseCase> useCaseSupplier,
            IntSupplier budgetSupplier,
            MetricsPort metrics) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.budgetSupplier = Objects.requireNonNull(budgetSupplier, "budgetSupplier");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.coordinator = new PaperTrackingCoordinator(
                plugin, useCaseSupplier, budgetSupplier, metrics);
        currentBudget();
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Physical tracking listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getOnlinePlayers().forEach(player ->
                enqueue(ScanRequest.player(player.getUniqueId(), "tracking-start")));
        refreshLoadedChunkReferences();
        scanTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::drain, 1L, 1L);
        seedTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::seedLoadedChunks, 1L, CHUNK_SEED_PERIOD_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        enqueue(ScanRequest.player(event.getPlayer().getUniqueId(), "player-join"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        scanPlayer(
                event.getPlayer(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                "player-quit");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        submitItem(
                event.getNewItemStack(),
                playerLocation(event.getPlayer().getUniqueId(), SLOT_PREFIX + event.getSlot()),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "inventory-slot-change");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        scanPlayer(player, TrackingObservationUseCase.Presence.PRESENT, "inventory-close-player");
        Inventory top = event.getView().getTopInventory();
        if (top != player.getInventory()) {
            scanInventory(
                    top,
                    InventoryReference.locationType(top),
                    InventoryReference.locationKey(top),
                    TrackingObservationUseCase.Presence.PRESENT,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    "inventory-close-container");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        InventoryReference source = InventoryReference.capture(event.getSource());
        InventoryReference destination = InventoryReference.capture(event.getDestination());
        LoreItemIdentity identity = trackedIdentity(event.getItem());
        scheduleNextTick(() -> {
            scanReference(source, "inventory-move-source");
            Inventory inventory = destination == null ? null : destination.resolve(plugin);
            if (inventory != null && identity != null) {
                submitMatchingIdentity(inventory, identity, "inventory-move-destination");
            }
            scanReference(destination, "inventory-move-destination");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        submitItem(
                item.getItemStack(),
                droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "hopper-pickup-source");
        InventoryReference destination = InventoryReference.capture(event.getInventory());
        scheduleNextTick(() -> scanReference(destination, "hopper-pickup-destination"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        submitItem(
                item.getItemStack(),
                droppedLocation(item),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "player-drop");
        enqueue(ScanRequest.player(event.getPlayer().getUniqueId(), "player-drop-player"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        submitItem(
                item.getItemStack(),
                droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "player-pickup-source");
        UUID playerId = player.getUniqueId();
        scheduleNextTick(() -> {
            Player current = plugin.getServer().getPlayer(playerId);
            if (current != null) {
                scanPlayer(current, TrackingObservationUseCase.Presence.PRESENT,
                        "player-pickup-destination");
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        LoreItemIdentity identity = trackedIdentity(item.getItemStack());
        TrackingObservationUseCase.EvidenceMode mode = identity != null
                        && deathDrops.remove(identity.instanceId().value())
                ? TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                : TrackingObservationUseCase.EvidenceMode.RECONCILIATION;
        submitItem(
                item.getItemStack(),
                droppedLocation(item),
                TrackingObservationUseCase.Presence.PRESENT,
                mode,
                "item-spawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        int remaining = MAX_ITEMS_PER_SCAN;
        for (ItemStack item : event.getDrops()) {
            if (remaining-- <= 0) {
                break;
            }
            LoreItemIdentity identity = trackedIdentity(item);
            if (identity != null) {
                deathDrops.add(identity.instanceId().value());
            }
        }
        scanPlayer(
                event.getEntity(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                "player-death");
        scheduleNextTick(deathDrops::clear);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getState() instanceof Container container) {
            scanInventory(
                    container.getInventory(),
                    LocationDescriptor.Type.BLOCK_CONTAINER,
                    blockKey(event.getBlock().getLocation()),
                    TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    "container-break");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        enqueue(ScanRequest.chunk(ChunkReference.capture(event.getChunk()),
                TrackingObservationUseCase.Presence.PRESENT, "chunk-load"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        scanChunk(event.getChunk(), TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                "chunk-unload");
    }

    private void drain() {
        int budget = currentBudget();
        for (int count = 0; count < budget; count++) {
            ScanRequest request = scans.poll();
            if (request == null) {
                break;
            }
            request.run(plugin, this);
        }
        metrics.setGauge("tracking.scan_backlog", scans.size());
    }

    private void seedLoadedChunks() {
        if (loadedChunks.isEmpty()) {
            refreshLoadedChunkReferences();
        }
        int budget = currentBudget();
        for (int count = 0; count < budget; count++) {
            ChunkReference reference = loadedChunks.poll();
            if (reference == null) {
                break;
            }
            enqueue(ScanRequest.chunk(reference,
                    TrackingObservationUseCase.Presence.PRESENT,
                    "periodic-loaded-chunk"));
        }
    }

    private void refreshLoadedChunkReferences() {
        loadedChunks.clear();
        int maximum = maxQueuedScans();
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (loadedChunks.size() >= maximum) {
                    return;
                }
                loadedChunks.add(ChunkReference.capture(chunk));
            }
        }
    }

    private void enqueue(ScanRequest request) {
        if (closed) {
            return;
        }
        if (scans.size() >= maxQueuedScans()) {
            metrics.increment("tracking.rejected");
            plugin.getLogger().warning(
                    "Lore-item scan backlog is full; previous durable evidence was preserved.");
            return;
        }
        scans.add(request);
    }

    private void scanReference(InventoryReference reference, String source) {
        if (reference == null) {
            return;
        }
        Inventory inventory = reference.resolve(plugin);
        if (inventory != null) {
            scanInventory(
                    inventory,
                    reference.type(),
                    reference.key(),
                    TrackingObservationUseCase.Presence.PRESENT,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    source);
        }
    }

    private void scanPlayer(
            Player player,
            TrackingObservationUseCase.Presence presence,
            String source) {
        ScanLimit limit = new ScanLimit(MAX_ITEMS_PER_SCAN);
        String key = "player:" + player.getUniqueId();
        scanInventory(player.getInventory(), LocationDescriptor.Type.PLAYER_INVENTORY, key,
                presence, TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                source, limit);
        scanItem(player.getItemOnCursor(), LocationDescriptor.Type.PLAYER_INVENTORY, key,
                "cursor", presence, TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                source, 0, limit);
        scanInventory(player.getEnderChest(), LocationDescriptor.Type.PLAYER_ENDER_CHEST, key,
                presence, TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                source + "-ender", limit);
    }

    private void scanChunk(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source) {
        ScanLimit limit = new ScanLimit(MAX_ITEMS_PER_SCAN);
        for (Entity entity : chunk.getEntities()) {
            if (!limit.hasRemaining()) {
                break;
            }
            if (entity instanceof Item item) {
                submitItem(item.getItemStack(), droppedLocation(item), presence,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source + "-item");
                limit.consume();
            }
        }
        for (BlockState state : chunk.getTileEntities()) {
            if (!limit.hasRemaining()) {
                break;
            }
            if (state instanceof Container container) {
                scanInventory(container.getInventory(), LocationDescriptor.Type.BLOCK_CONTAINER,
                        blockKey(state.getLocation()), presence,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source + "-container", limit);
            }
        }
        if (!limit.hasRemaining()) {
            metrics.increment("tracking.rejected");
            plugin.getLogger().warning(
                    "Lore-item chunk scan reached its bounded item limit for " + source + '.');
        }
    }

    private void scanInventory(
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source) {
        scanInventory(inventory, type, key, presence, mode, source,
                new ScanLimit(MAX_ITEMS_PER_SCAN));
    }

    private void scanInventory(
            Inventory inventory,
            LocationDescriptor.Type type,
            String key,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source,
            ScanLimit limit) {
        if (type == null || key == null) {
            return;
        }
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && limit.hasRemaining(); slot++) {
            scanItem(contents[slot], type, key, SLOT_PREFIX + slot, presence, mode,
                    source, 0, limit);
        }
    }

    private void scanItem(
            ItemStack item,
            LocationDescriptor.Type type,
            String key,
            String path,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source,
            int depth,
            ScanLimit limit) {
        if (item == null || item.getType().isAir() || !limit.hasRemaining()) {
            return;
        }
        limit.consume();
        submitItem(item, new LocationDescriptor(type, key, path), presence, mode, source);
        if (depth >= MAX_NESTING_DEPTH || !limit.hasRemaining()) {
            return;
        }
        if (item.getItemMeta() instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof ShulkerBox shulker) {
            ItemStack[] nested = shulker.getInventory().getContents();
            for (int slot = 0; slot < nested.length && limit.hasRemaining(); slot++) {
                scanItem(nested[slot], LocationDescriptor.Type.NESTED_CONTAINER, key,
                        path + "/shulker:" + slot, presence, mode, source, depth + 1, limit);
            }
        }
        if (item.getItemMeta() instanceof BundleMeta bundle) {
            List<ItemStack> nested = bundle.getItems();
            for (int index = 0; index < nested.size() && limit.hasRemaining(); index++) {
                scanItem(nested.get(index), LocationDescriptor.Type.NESTED_CONTAINER, key,
                        path + "/bundle:" + index, presence, mode, source, depth + 1, limit);
            }
        }
    }

    private void submitMatchingIdentity(
            Inventory inventory,
            LoreItemIdentity identity,
            String source) {
        InventoryReference reference = InventoryReference.capture(inventory);
        if (reference == null) {
            return;
        }
        ScanLimit limit = new ScanLimit(MAX_ITEMS_PER_SCAN);
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && limit.hasRemaining(); slot++) {
            ItemStack item = contents[slot];
            limit.consume();
            if (identity.equals(trackedIdentity(item))) {
                coordinator.submit(new TrackingObservationUseCase.Request(
                        identity,
                        new LocationDescriptor(reference.type(), reference.key(), SLOT_PREFIX + slot),
                        TrackingObservationUseCase.Presence.PRESENT,
                        TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                        source));
                return;
            }
        }
    }

    private void submitItem(
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
                    "Could not schedule lore-item tracking during shutdown.", exception);
        }
    }

    private int currentBudget() {
        int value = budgetSupplier.getAsInt();
        if (value < MIN_BUDGET) {
            throw new IllegalStateException("Configured tracking budget must be positive");
        }
        return value;
    }

    private int maxQueuedScans() {
        return Math.multiplyExact(currentBudget(), QUEUE_MULTIPLIER);
    }

    private static LocationDescriptor playerLocation(UUID playerId, String path) {
        return new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_INVENTORY, "player:" + playerId, path);
    }

    private static LocationDescriptor droppedLocation(Item item) {
        Location location = item.getLocation();
        return new LocationDescriptor(
                LocationDescriptor.Type.DROPPED_ITEM,
                item.getWorld().getKey() + ":entity:" + item.getUniqueId() + ':'
                        + location.getBlockX() + ':' + location.getBlockY() + ':'
                        + location.getBlockZ(),
                "item-entity");
    }

    private static String blockKey(Location location) {
        return Objects.requireNonNull(location.getWorld(), "location world").getKey() + ':'
                + location.getBlockX() + ':' + location.getBlockY() + ':'
                + location.getBlockZ();
    }

    @Override
    public void close() {
        closed = true;
        if (scanTask != null) {
            scanTask.cancel();
        }
        if (seedTask != null) {
            seedTask.cancel();
        }
        scans.clear();
        loadedChunks.clear();
        deathDrops.clear();
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
                        InventoryKind.BLOCK, null, location.getWorld().getUID(),
                        location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                        LocationDescriptor.Type.BLOCK_CONTAINER, blockKey(location));
            }
            if (holder instanceof Entity entity) {
                return new InventoryReference(
                        InventoryKind.ENTITY, entity.getUniqueId(), null, 0, 0, 0,
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        entity.getWorld().getKey() + ":entity:" + entity.getUniqueId());
            }
            return null;
        }

        private static LocationDescriptor.Type locationType(Inventory inventory) {
            InventoryReference reference = capture(inventory);
            return reference == null ? null : reference.type();
        }

        private static String locationKey(Inventory inventory) {
            InventoryReference reference = capture(inventory);
            return reference == null ? null : reference.key();
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
                    yield state instanceof Container container ? container.getInventory() : null;
                }
                case ENTITY -> {
                    Entity entity = plugin.getServer().getEntity(holderId);
                    yield entity instanceof InventoryHolder holder ? holder.getInventory() : null;
                }
            };
        }
    }

    private record ChunkReference(UUID worldId, int x, int z) {
        private static ChunkReference capture(Chunk chunk) {
            return new ChunkReference(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    private record ScanRequest(
            UUID playerId,
            ChunkReference chunk,
            TrackingObservationUseCase.Presence presence,
            String source) {
        private static ScanRequest player(UUID playerId, String source) {
            return new ScanRequest(playerId, null,
                    TrackingObservationUseCase.Presence.PRESENT, source);
        }

        private static ScanRequest chunk(
                ChunkReference chunk,
                TrackingObservationUseCase.Presence presence,
                String source) {
            return new ScanRequest(null, chunk, presence, source);
        }

        private void run(Plugin plugin, PaperPhysicalTrackingListener listener) {
            if (playerId != null) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    listener.scanPlayer(player, presence, source);
                }
                return;
            }
            World world = plugin.getServer().getWorld(chunk.worldId());
            if (world != null && world.isChunkLoaded(chunk.x(), chunk.z())) {
                listener.scanChunk(world.getChunkAt(chunk.x(), chunk.z()), presence, source);
            }
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
