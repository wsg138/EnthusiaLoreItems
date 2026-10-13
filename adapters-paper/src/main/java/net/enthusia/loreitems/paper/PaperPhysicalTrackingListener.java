package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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
    private static final long TRACKED_RECONCILIATION_TICKS = 1_200L;
    private static final long MAX_DRAIN_NANOS = 2_000_000L;
    private static final long METRICS_UPDATE_TICKS = 20L;
    private static final long BLOCK_REFRESH_INTERVAL_TICKS = 2L;
    private static final long ON_INTERVAL = 0L;

    private final Plugin plugin;
    private final IntSupplier budgetSupplier;
    private final MetricsPort metrics;
    private final PaperPhysicalScanTelemetry scanTelemetry;
    private final PaperTrackingCoordinator coordinator;
    private final PaperPhysicalInventoryScanner scanner;
    private final PaperPhysicalEntityScanner entityScanner;
    private final PaperPhysicalChunkScanner chunkScanner;
    private final PaperHopperMoveObservation hopperMoves;
    private final PaperDeferredMainThreadActions deferredActions;
    private final PaperBlockInventoryTracking blockInventoryTracking;
    private final PaperPhysicalScanQueue scans = new PaperPhysicalScanQueue();
    private final PaperPhysicalScanAdmission scanAdmission;
    private final PaperPeriodicChunkScanKeys periodicChunkKeys = new PaperPeriodicChunkScanKeys();
    private final PaperChunkLoadLedger chunkLedger = new PaperChunkLoadLedger();
    private final PaperLoadedTrackedContainerIndex trackedContainers =
            new PaperLoadedTrackedContainerIndex();
    private final Set<PaperLoadedTrackedContainerIndex.BlockKey> pendingBlockRefresh =
            new LinkedHashSet<>();
    private final ArrayDeque<PaperTrackingScanRequest.ChunkReference> startupChunks = new ArrayDeque<>();
    private final Set<UUID> deferredOnlinePlayers = new LinkedHashSet<>();
    private final PaperPhysicalItemEvents itemEvents;
    private final PaperPlayerScanCoalescer pendingPlayerScans;

    private BukkitTask scanTask;
    private long tick;
    private boolean closed;

    public PaperPhysicalTrackingListener(
            Plugin plugin,
            Supplier<TrackingObservationUseCase> useCaseSupplier,
            IntSupplier budgetSupplier,
            MetricsPort metrics) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.budgetSupplier = Objects.requireNonNull(budgetSupplier, "budgetSupplier");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scanTelemetry = new PaperPhysicalScanTelemetry(metrics);
        this.scanAdmission = new PaperPhysicalScanAdmission(
                scans, periodicChunkKeys, scanTelemetry);
        this.coordinator = new PaperTrackingCoordinator(
                plugin, useCaseSupplier, budgetSupplier, metrics);
        this.scanner = new PaperPhysicalInventoryScanner(coordinator);
        this.entityScanner = new PaperPhysicalEntityScanner(scanner);
        this.chunkScanner = new PaperPhysicalChunkScanner(
                scanner, entityScanner, metrics, plugin.getLogger(), trackedContainers,
                coordinator);
        this.deferredActions = new PaperDeferredMainThreadActions(
                plugin,
                "Could not schedule lore-item tracking during shutdown.");
        this.itemEvents = new PaperPhysicalItemEvents(
                plugin, scanner, deferredActions, this::schedulePlayerUnique);
        this.hopperMoves = new PaperHopperMoveObservation(
                scanner, deferredActions, metrics,
                itemEvents::scanReference, itemEvents::submitMatchingIdentities);
        this.pendingPlayerScans = new PaperPlayerScanCoalescer(
                deferredActions, metrics, (id, source) -> scanPlayer(id, true, source));
        this.blockInventoryTracking = new PaperBlockInventoryTracking(
                plugin, scanner, deferredActions, MAX_ITEMS_PER_SCAN);
        currentBudget();
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Physical tracking listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getOnlinePlayers().forEach(player ->
                enqueue(PaperTrackingScanRequest.player(
                        player.getUniqueId(), true, "tracking-start")));
        captureStartupChunks();
        scanTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::drain, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        enqueue(PaperTrackingScanRequest.player(
                event.getPlayer().getUniqueId(), true, "player-join"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        schedulePlayerUnique(event.getPlayer().getUniqueId(), "player-respawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        schedulePlayerUnique(event.getPlayer().getUniqueId(), "inventory-slot-change");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        hopperMoves.observe(event);
        if (scanner.hasIdentityEvidence(event.getItem())) {
            trackInventoryChange(event.getSource());
            trackInventoryChange(event.getDestination());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerClick(InventoryClickEvent event) {
        if (scanner.hasIdentityEvidence(event.getCurrentItem())
                || scanner.hasIdentityEvidence(event.getCursor())) {
            trackInventoryChange(event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onContainerDrag(InventoryDragEvent event) {
        if (scanner.hasIdentityEvidence(event.getOldCursor())
                || event.getNewItems().values().stream().anyMatch(scanner::hasIdentityEvidence)) {
            trackInventoryChange(event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onContainerClose(InventoryCloseEvent event) {
        PaperPhysicalChunkScanner.anchor(event.getView().getTopInventory())
                .filter(trackedContainers::containsBlock)
                .ifPresent(key -> {
                    chunkScanner.invalidateContents(key);
                    pendingBlockRefresh.add(key);
                });
    }

    private void trackInventoryChange(org.bukkit.inventory.Inventory inventory) {
        PaperPhysicalChunkScanner.anchor(inventory).ifPresent(key -> {
            chunkScanner.invalidateContents(key);
            pendingBlockRefresh.add(key);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        itemEvents.onInventoryPickup(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        itemEvents.onDrop(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        itemEvents.onPickup(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        itemEvents.onItemSpawn(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        itemEvents.onDeath(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        blockInventoryTracking.onBlockBreak(event);
        invalidateBlock(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        blockInventoryTracking.onBlockPlace(event);
        invalidateBlock(event.getBlock());
    }

    private void invalidateBlock(org.bukkit.block.Block block) {
        var key = new PaperLoadedTrackedContainerIndex.BlockKey(
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        chunkScanner.invalidateContents(key);
        pendingBlockRefresh.addAll(trackedContainers.blockChanged(key));
        pendingBlockRefresh.add(key);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpecialBlockInventoryInteract(PlayerInteractEvent event) {
        blockInventoryTracking.onSpecialBlockInventoryInteract(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        var key = PaperTrackingScanRequest.ChunkReference.capture(event.getChunk());
        chunkScanner.invalidateLifecycleCache();
        observeNaturalChunkLoad(key, "chunk-load");
        trackedContainers.chunkLoaded(key, this::naturallyLoaded);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        var key = PaperTrackingScanRequest.ChunkReference.capture(event.getChunk());
        chunkLedger.unload(key);
        chunkScanner.invalidateLifecycleCache();
        trackedContainers.chunkUnloaded(key);
        // Do not traverse tile entities or the adjacent chest half while unloading.
        // Immutable, ACK-fenced slot obligations drain later; no unload-time inventory reads.
        metrics.increment("tracking.scan_unload_deferred");
    }

    private boolean naturallyLoaded(PaperTrackingScanRequest.ChunkReference key) {
        World world = plugin.getServer().getWorld(key.worldId());
        return world != null && world.isChunkLoaded(key.x(), key.z());
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
        entityScanner.scan(entities, presence, source, limit);
        if (!limit.hasRemaining()) {
            metrics.increment("tracking.scan_truncated");
            plugin.getLogger().fine(
                    "Lore-item entity lifecycle scan reached its bounded item limit for " + source + '.');
        }
    }

    void schedulePlayerUnique(UUID playerId, String source) {
        if (!closed) {
            pendingPlayerScans.schedule(playerId, source);
        }
    }

    int pendingUniquePlayerScans() {
        return pendingPlayerScans.size();
    }

    void drain() {
        long start = System.nanoTime();
        tick++;
        seedLoadedChunks();
        restoreRejectedScans();
        if (System.nanoTime() - start < MAX_DRAIN_NANOS) {
            if (trackedContainers.drainUnloaded(1, coordinator::submitAcknowledged) > 0) {
                metrics.increment("tracking.scan_last_confirmed_submitted");
            }
        }
        drainQueued(start, drainTrackedWork(start));
        if (tick % METRICS_UPDATE_TICKS == ON_INTERVAL) updateIndexMetrics();
        scanTelemetry.drained(scans.isEmpty(), plugin.getLogger());
        scanTelemetry.updateDepth(scans.size(), periodicChunkKeys.size());
        metrics.setGauge("tracking.scan_drain_peak_nanos", System.nanoTime() - start);
    }

    private void drainQueued(long start, int reserved) {
        for (int n = reserved, budget = currentBudget(); n < budget; n++) {
            PaperTrackingScanRequest request = scans.poll();
            if (request == null) break;
            periodicChunkKeys.release(request);
            PaperChunkLoadLedger.Pending pending = token(request);
            if (pending != null && !chunkLedger.begin(pending)) continue;
            boolean complete = scanTelemetry.execute(request, plugin, this);
            if (pending != null) settleScan(pending, complete);
            if (System.nanoTime() - start >= MAX_DRAIN_NANOS) break;
        }
    }

    private void settleScan(PaperChunkLoadLedger.Pending pending, boolean complete) {
        chunkLedger.complete(pending, complete, naturallyLoaded(pending.key()), tick);
    }

    private void updateIndexMetrics() {
        metrics.setGauge("tracking.scan_dirty_locations", chunkLedger.dirtyCount());
        metrics.setGauge("tracking.scan_known_loaded", trackedContainers.size());
        metrics.setGauge("tracking.scan_index_unresolved", trackedContainers.unresolvedCount());
        metrics.setGauge("tracking.scan_index_pending", trackedContainers.pendingCount());
        metrics.setGauge("tracking.scan_index_peak_pending", trackedContainers.peakPending());
        metrics.setGauge("tracking.scan_block_refresh_pending", pendingBlockRefresh.size());
        metrics.setGauge("tracking.scan_last_confirmed_pending", trackedContainers.pendingUnloadCount());
        metrics.setGauge("tracking.scan_last_confirmed_failed", trackedContainers.failedTransitions());
    }

    private int drainTrackedWork(long start) {
        if (System.nanoTime() - start >= MAX_DRAIN_NANOS) return 0;
        if (tick % BLOCK_REFRESH_INTERVAL_TICKS == ON_INTERVAL && !pendingBlockRefresh.isEmpty()) {
            var iterator = pendingBlockRefresh.iterator();
            var key = iterator.next();
            iterator.remove();
            chunkScanner.scanBlock(plugin, key, tick);
            metrics.increment("tracking.scan_block_refresh");
            return 1;
        }
        if (tick % METRICS_UPDATE_TICKS != ON_INTERVAL) return 0;
        var next = trackedContainers.nextWork(
                tick, TRACKED_RECONCILIATION_TICKS, this::naturallyLoaded);
        if (next == null) return 0;
        if (!chunkScanner.scanTracked(plugin, next, tick)) {
            metrics.increment("tracking.scan_index_unresolved_work");
        }
        metrics.increment("tracking.scan_index_work");
        return 1;
    }

    private void captureStartupChunks() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                startupChunks.addLast(PaperTrackingScanRequest.ChunkReference.capture(chunk));
            }
        }
    }

    private void seedLoadedChunks() {
        for (int n = 0, budget = currentBudget(); n < budget && !startupChunks.isEmpty(); n++) {
            PaperTrackingScanRequest.ChunkReference key = startupChunks.removeFirst();
            World world = plugin.getServer().getWorld(key.worldId());
            if (world != null && world.isChunkLoaded(key.x(), key.z())) {
                observeNaturalChunkLoad(key, "startup-loaded-chunk");
            }
        }
    }

    void observeNaturalChunkLoad(PaperTrackingScanRequest.ChunkReference key, String source) {
        long generation = chunkLedger.observeLoad(key);
        if (generation == 0) {
            metrics.increment("tracking.scan_chunk_coalesced");
            return;
        }
        enqueueNatural(new PaperChunkLoadLedger.Pending(key, generation), source);
    }

    /** Worker 3 index hook: only a known tracked loaded chunk is periodically rechecked. */
    void markKnownTrackedChunk(UUID worldId, int x, int z) {
        chunkLedger.observeTracked(PaperChunkLoadLedger.key(worldId, x, z));
    }

    void forgetKnownTrackedChunk(UUID worldId, int x, int z) {
        chunkLedger.forgetTracked(PaperChunkLoadLedger.key(worldId, x, z));
    }

    private void enqueueNatural(PaperChunkLoadLedger.Pending pending, String source) {
        PaperTrackingScanRequest request = PaperTrackingScanRequest.natural(
                pending.key(), pending.generation(), source);
        if (enqueue(request)) chunkLedger.queued(pending);
        else {
            chunkLedger.markDirty(pending);
            metrics.increment("tracking.scan_dirty_admission");
        }
    }

    private void restoreRejectedScans() {
        recoverDeferredPlayers();
        recoverDirtyChunks();
    }

    private void recoverDeferredPlayers() {
        int capacity = maxQueuedScans();
        int slots = Math.max(0, Math.min(currentBudget(), capacity - scans.size()));
        for (int index = 0; index < slots && !deferredOnlinePlayers.isEmpty(); index++) {
            UUID playerId = deferredOnlinePlayers.iterator().next();
            deferredOnlinePlayers.remove(playerId);
            if (plugin.getServer().getPlayer(playerId) != null
                    && !enqueue(PaperTrackingScanRequest.player(
                            playerId, true, "player-scan-retry"))) {
                deferredOnlinePlayers.add(playerId);
                return;
            }
        }
    }

    private void recoverDirtyChunks() {
        int capacity = maxQueuedScans();
        int slots = Math.max(0, Math.min(currentBudget(), capacity - scans.size()));
        for (int index = 0; index < slots; index++) {
            PaperChunkLoadLedger.Pending pending = chunkLedger.nextDirty(tick);
            if (pending == null) break;
            World world = plugin.getServer().getWorld(pending.key().worldId());
            if (world == null || !world.isChunkLoaded(pending.key().x(), pending.key().z())) {
                chunkLedger.complete(pending, false, false, tick);
                continue;
            }
            enqueueNatural(pending, "chunk-load-retry");
        }
    }

    private static PaperChunkLoadLedger.Pending token(PaperTrackingScanRequest request) {
        return request.generation() > 0L && request.chunk() != null
                ? new PaperChunkLoadLedger.Pending(request.chunk(), request.generation()) : null;
    }

    private boolean cannotMakeRoom(PaperTrackingScanRequest request, int maximum) {
        return scans.size() > maximum || !scanAdmission.makeRoomFor(request);
    }

    private void reportRejectedAdmission(PaperTrackingScanRequest request) {
        periodicChunkKeys.release(request);
        scanTelemetry.reject(request);
        scanTelemetry.saturated(plugin.getLogger());
        if (request.playerId() != null
                && plugin.getServer().getPlayer(request.playerId()) != null) {
            deferredOnlinePlayers.add(request.playerId());
        }
    }

    boolean enqueue(PaperTrackingScanRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed || !periodicChunkKeys.tryRegister(request, metrics)) return false;
        int maximum = maxQueuedScans();
        if (scans.size() >= maximum && cannotMakeRoom(request, maximum)) {
            reportRejectedAdmission(request);
            return false;
        }
        scans.add(request);
        scanTelemetry.updateDepth(scans.size(), periodicChunkKeys.size());
        if (scans.size() == maximum) scanTelemetry.saturated(plugin.getLogger());
        return true;
    }

    int queuedScanCount() {
        return scans.size();
    }

    int queuedPeriodicChunkCount() {
        return periodicChunkKeys.size();
    }

    int dirtyChunkCount() {
        return chunkLedger.dirtyCount();
    }

    boolean scanPlayer(UUID playerId, boolean unique, String source) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            return false;
        }
        if (unique) {
            scanner.scanPlayerUnique(player, source);
        } else {
            scanner.scanPlayer(
                    player,
                    TrackingObservationUseCase.Presence.PRESENT,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    source);
        }
        return true;
    }

    boolean scanChunk(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source) {
        return chunkScanner.scanChunk(chunk, presence, source, tick);
    }

    private void scheduleNextTick(Runnable action) {
        deferredActions.schedule(action);
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

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        HandlerList.unregisterAll(this);
        if (scanTask != null) {
            scanTask.cancel();
        }
        startupChunks.clear();
        chunkLedger.clear();
        trackedContainers.clear();
        pendingBlockRefresh.clear();
        deferredOnlinePlayers.clear();
        deferredActions.close();
        pendingPlayerScans.clear();
        scans.clear();
        periodicChunkKeys.clear();
        scanTelemetry.updateDepth(scans.size(), periodicChunkKeys.size());
        itemEvents.clear();
        scanTelemetry.reset();
        coordinator.close();
    }
}
