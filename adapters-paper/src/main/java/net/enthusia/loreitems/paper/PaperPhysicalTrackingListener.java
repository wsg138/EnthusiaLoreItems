package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
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
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Event-driven physical tracking with bounded natural-access reconciliation. */
public final class PaperPhysicalTrackingListener implements Listener, AutoCloseable {
    private static final int MIN_BUDGET = 1;
    private static final int QUEUE_MULTIPLIER = 32;
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final long CHUNK_SEED_PERIOD_TICKS = 100L;
    private static final String SLOT_PREFIX = "slot:";
    private static final Chunk[] EMPTY_CHUNKS = new Chunk[0];
    private static final UUID NO_WORLD = new UUID(0L, 0L);

    private final Plugin plugin;
    private final IntSupplier budgetSupplier;
    private final MetricsPort metrics;
    private final PaperTrackingCoordinator coordinator;
    private final PaperPhysicalInventoryScanner scanner;
    private final PaperDisplayEntityScanner displayScanner;
    private final Queue<ScanRequest> scans = new ArrayDeque<>();
    private final Set<UUID> deathDrops = new HashSet<>();

    private BukkitTask scanTask;
    private BukkitTask seedTask;
    private int worldCursor;
    private int chunkCursor;
    private UUID seededWorldId = NO_WORLD;
    private Chunk[] seededChunks = EMPTY_CHUNKS;
    private boolean scanSaturated;
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
        this.scanner = new PaperPhysicalInventoryScanner(coordinator);
        this.displayScanner = new PaperDisplayEntityScanner(scanner);
        currentBudget();
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Physical tracking listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getOnlinePlayers().forEach(player ->
                enqueue(ScanRequest.player(player.getUniqueId(), true, "tracking-start")));
        seedLoadedChunks();
        scanTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::drain, 1L, 1L);
        seedTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::seedLoadedChunks, 1L, CHUNK_SEED_PERIOD_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        enqueue(ScanRequest.player(event.getPlayer().getUniqueId(), true, "player-join"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        scanner.submitItem(
                event.getNewItemStack(),
                playerLocation(event.getPlayer().getUniqueId(), SLOT_PREFIX + event.getSlot()),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "inventory-slot-change");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Optional<PaperPhysicalInventorySnapshot> source =
                PaperPhysicalInventorySnapshot.capture(event.getSource());
        Optional<PaperPhysicalInventorySnapshot> destination =
                PaperPhysicalInventorySnapshot.capture(event.getDestination());
        LoreItemIdentity identity = scanner.trackedIdentity(event.getItem());
        scheduleNextTick(() -> {
            scanReference(source, "inventory-move-source");
            submitMatchingIdentity(destination, identity, "inventory-move-destination");
            scanReference(destination, "inventory-move-destination");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        scanner.submitItem(
                item.getItemStack(),
                droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "hopper-pickup-source");
        Optional<PaperPhysicalInventorySnapshot> destination =
                PaperPhysicalInventorySnapshot.capture(event.getInventory());
        LoreItemIdentity identity = scanner.trackedIdentity(item.getItemStack());
        scheduleNextTick(() -> {
            submitMatchingIdentity(destination, identity, "hopper-pickup-destination");
            scanReference(destination, "hopper-pickup-destination");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        scanner.submitItem(
                item.getItemStack(),
                droppedLocation(item),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "player-drop");
        schedulePlayerUnique(event.getPlayer().getUniqueId(), "player-drop-player");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        scanner.submitItem(
                item.getItemStack(),
                droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "player-pickup-source");
        schedulePlayerUnique(player.getUniqueId(), "player-pickup-destination");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        LoreItemIdentity identity = scanner.trackedIdentity(item.getItemStack());
        TrackingObservationUseCase.EvidenceMode mode = identity != null
                        && deathDrops.remove(identity.instanceId().value())
                ? TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                : TrackingObservationUseCase.EvidenceMode.RECONCILIATION;
        scanner.submitItem(
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
            if (remaining <= 0) {
                break;
            }
            remaining--;
            LoreItemIdentity identity = scanner.trackedIdentity(item);
            if (identity != null) {
                deathDrops.add(identity.instanceId().value());
            }
        }
        scanner.scanPlayer(
                event.getEntity(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "player-death");
        scheduleNextTick(deathDrops::clear);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getState() instanceof Container container) {
            scanner.scanInventory(
                    container.getInventory(),
                    LocationDescriptor.Type.BLOCK_CONTAINER,
                    PaperInventoryReference.blockKey(event.getBlock().getLocation()),
                    TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    "container-break");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        enqueue(ScanRequest.chunk(
                ChunkReference.capture(event.getChunk()),
                TrackingObservationUseCase.Presence.PRESENT,
                "chunk-load"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        scanChunk(
                event.getChunk(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                "chunk-unload");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        scanLifecycleEntities(
                event.getEntities(),
                TrackingObservationUseCase.Presence.PRESENT,
                "chunk-load-entities");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        scanLifecycleEntities(
                event.getEntities(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                "chunk-unload-entities");
    }

    private void scanLifecycleEntities(
            List<Entity> entities,
            TrackingObservationUseCase.Presence presence,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        for (Entity entity : entities) {
            if (!limit.hasRemaining()) {
                break;
            }
            if (entity instanceof Item item) {
                scanner.submitItem(
                        item.getItemStack(),
                        droppedLocation(item),
                        presence,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source + "-item");
                limit.consume();
            } else {
                displayScanner.scan(entity, presence, source, limit);
            }
        }
        if (!limit.hasRemaining()) {
            metrics.increment("tracking.scan_truncated");
            plugin.getLogger().fine(
                    "Lore-item entity lifecycle scan reached its bounded item limit for " + source + '.');
        }
    }

    private void schedulePlayerUnique(UUID playerId, String source) {
        scheduleNextTick(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                scanner.scanPlayerUnique(player, source);
            }
        });
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
        if (scans.isEmpty() && scanSaturated) {
            scanSaturated = false;
            plugin.getLogger().fine("Lore-item scan backlog has drained.");
        }
        metrics.setGauge("tracking.scan_backlog", scans.size());
    }

    private void seedLoadedChunks() {
        List<World> worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) {
            return;
        }
        int budget = currentBudget();
        int enqueued = 0;
        int attempts = 0;
        int maximumAttempts = Math.addExact(budget, worlds.size());
        while (enqueued < budget && attempts < maximumAttempts) {
            if (worldCursor >= worlds.size()) {
                advanceSeedWorld(worlds.size());
            }
            World world = worlds.get(worldCursor);
            Chunk[] snapshot = loadedChunkSnapshot(world);
            if (chunkCursor >= snapshot.length) {
                advanceSeedWorld(worlds.size());
                attempts++;
                continue;
            }
            enqueue(ScanRequest.chunk(
                    ChunkReference.capture(snapshot[chunkCursor]),
                    TrackingObservationUseCase.Presence.PRESENT,
                    "periodic-loaded-chunk"));
            chunkCursor++;
            enqueued++;
            attempts++;
        }
    }

    private Chunk[] loadedChunkSnapshot(World world) {
        UUID worldId = world.getUID();
        if (!worldId.equals(seededWorldId)) {
            seededWorldId = worldId;
            seededChunks = world.getLoadedChunks();
        }
        return seededChunks;
    }

    private void advanceSeedWorld(int worldCount) {
        worldCursor = (worldCursor + 1) % worldCount;
        chunkCursor = 0;
        seededWorldId = NO_WORLD;
        seededChunks = EMPTY_CHUNKS;
    }

    private void enqueue(ScanRequest request) {
        if (closed) {
            return;
        }
        int maximum = maxQueuedScans();
        if (scans.size() >= maximum) {
            metrics.increment("tracking.rejected");
            reportScanSaturation();
            return;
        }
        scans.add(request);
        if (scans.size() == maximum) {
            reportScanSaturation();
        }
    }

    private void reportScanSaturation() {
        if (!scanSaturated) {
            scanSaturated = true;
            plugin.getLogger().warning(
                    "Lore-item scan backlog is full; previous durable evidence was preserved.");
        }
    }

    private void scanReference(
            Optional<PaperPhysicalInventorySnapshot> snapshot, String source) {
        snapshot.ifPresent(reference -> reference.resolve(plugin).ifPresent(inventory ->
                scanner.scanInventory(
                        inventory,
                        reference.type(),
                        reference.key(),
                        TrackingObservationUseCase.Presence.PRESENT,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source)));
    }

    private void submitMatchingIdentity(
            Optional<PaperPhysicalInventorySnapshot> snapshot,
            LoreItemIdentity identity,
            String source) {
        if (identity == null) {
            return;
        }
        snapshot.ifPresent(reference -> reference.resolve(plugin).ifPresent(inventory ->
                scanner.submitMatchingIdentity(
                        inventory,
                        reference.type(),
                        reference.key(),
                        identity,
                        source)));
    }

    void scanChunk(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        scanChunkEntities(chunk, presence, source, limit);
        scanChunkContainers(chunk, presence, source, limit);
        if (!limit.hasRemaining()) {
            metrics.increment("tracking.scan_truncated");
            plugin.getLogger().fine(
                    "Lore-item chunk scan reached its bounded item limit for " + source + '.');
        }
    }

    private void scanChunkEntities(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        for (Entity entity : chunk.getEntities()) {
            if (!limit.hasRemaining()) {
                return;
            }
            if (entity instanceof Item item) {
                scanner.submitItem(
                        item.getItemStack(),
                        droppedLocation(item),
                        presence,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source + "-item");
                limit.consume();
            } else {
                displayScanner.scan(entity, presence, source, limit);
            }
        }
    }

    private void scanChunkContainers(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        for (BlockState state : chunk.getTileEntities()) {
            if (!limit.hasRemaining()) {
                return;
            }
            if (state instanceof Container container) {
                scanner.scanInventory(
                        container.getInventory(),
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        PaperInventoryReference.blockKey(state.getLocation()),
                        presence,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source + "-container",
                        limit);
            }
        }
    }

    private void scheduleNextTick(Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule lore-item tracking during shutdown.",
                    exception);
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
                LocationDescriptor.Type.PLAYER_INVENTORY,
                "player:" + playerId,
                path);
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
        deathDrops.clear();
        scanSaturated = false;
        coordinator.close();
        HandlerList.unregisterAll(this);
    }

    private record ChunkReference(UUID worldId, int x, int z) {
        private static ChunkReference capture(Chunk chunk) {
            return new ChunkReference(
                    chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    private record ScanRequest(
            UUID playerId,
            boolean unique,
            ChunkReference chunk,
            TrackingObservationUseCase.Presence presence,
            String source) {
        private static ScanRequest player(UUID playerId, boolean unique, String source) {
            return new ScanRequest(
                    playerId,
                    unique,
                    null,
                    TrackingObservationUseCase.Presence.PRESENT,
                    source);
        }

        private static ScanRequest chunk(
                ChunkReference chunk,
                TrackingObservationUseCase.Presence presence,
                String source) {
            return new ScanRequest(null, false, chunk, presence, source);
        }

        private void run(Plugin plugin, PaperPhysicalTrackingListener listener) {
            if (playerId != null) {
                runPlayer(plugin, listener);
                return;
            }
            World world = plugin.getServer().getWorld(chunk.worldId());
            if (world != null && world.isChunkLoaded(chunk.x(), chunk.z())) {
                listener.scanChunk(
                        world.getChunkAt(chunk.x(), chunk.z()),
                        presence,
                        source);
            }
        }

        private void runPlayer(Plugin plugin, PaperPhysicalTrackingListener listener) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            if (unique) {
                listener.scanner.scanPlayerUnique(player, source);
            } else {
                listener.scanner.scanPlayer(
                        player,
                        presence,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source);
            }
        }
    }
}
