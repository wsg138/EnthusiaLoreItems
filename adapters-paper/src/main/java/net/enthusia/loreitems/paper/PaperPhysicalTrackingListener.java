package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
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
    private static final long SCAN_DIAGNOSTICS_INTERVAL_TICKS = 20L;
    private static final long MAX_DRAIN_NANOS = 2_000_000L;

    private final Plugin plugin;
    private final IntSupplier budgetSupplier;
    private final MetricsPort metrics;
    private final PaperPhysicalScanTelemetry scanTelemetry;
    private final PaperTrackingCoordinator coordinator;
    private final PaperPhysicalInventoryScanner scanner;
    private final PaperPhysicalEntityScanner entityScanner;
    private final PaperChunkContainerReconciler chunkContainers;
    private final PaperHopperMoveObservation hopperMoves;
    private final PaperDeferredMainThreadActions deferredActions;
    private final PaperBlockInventoryTracking blockInventoryTracking;
    private final PaperPhysicalScanQueue scans = new PaperPhysicalScanQueue();
    private final PaperPhysicalScanAdmission scanAdmission;
    private final PaperPeriodicChunkScanKeys periodicChunkKeys = new PaperPeriodicChunkScanKeys();
    private final PaperChunkLoadLedger chunkLedger = new PaperChunkLoadLedger();
    private final PaperStartupChunkDiscovery startupDiscovery;
    private final Set<UUID> deferredOnlinePlayers = new LinkedHashSet<>();
    private final Set<UUID> deathDrops = new HashSet<>();
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
        this.chunkContainers = new PaperChunkContainerReconciler(scanner);
        this.deferredActions = new PaperDeferredMainThreadActions(
                plugin,
                "Could not schedule lore-item tracking during shutdown.");
        this.hopperMoves = new PaperHopperMoveObservation(
                scanner, deferredActions, metrics,
                this::scanReference, this::submitMatchingIdentities);
        this.pendingPlayerScans = new PaperPlayerScanCoalescer(
                deferredActions, metrics, (id, source) -> scanPlayer(id, true, source));
        this.blockInventoryTracking = new PaperBlockInventoryTracking(
                plugin, scanner, deferredActions, MAX_ITEMS_PER_SCAN);
        this.startupDiscovery = new PaperStartupChunkDiscovery(plugin);
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
        startupDiscovery.capture();
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
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        scanner.scanItemTree(
                stack,
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "hopper-pickup-source",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        Optional<PaperPhysicalInventorySnapshot> destination =
                PaperPhysicalInventorySnapshot.capture(event.getInventory());
        Set<LoreItemIdentity> identities = scanner.trackedIdentities(stack);
        scheduleNextTick(() -> {
            submitMatchingIdentities(destination, identities, "hopper-pickup-destination");
            scanReference(destination, "hopper-pickup-destination");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        scanner.scanItemTree(
                item.getItemStack(),
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "player-drop",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        schedulePlayerUnique(event.getPlayer().getUniqueId(), "player-drop-player");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        scanner.scanItemTree(
                item.getItemStack(),
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "player-pickup-source",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        schedulePlayerUnique(player.getUniqueId(), "player-pickup-destination");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        Set<LoreItemIdentity> identities = scanner.trackedIdentities(item.getItemStack());
        boolean deathDrop = false;
        for (LoreItemIdentity identity : identities) {
            if (deathDrops.remove(identity.instanceId().value())) {
                deathDrop = true;
            }
        }
        TrackingObservationUseCase.EvidenceMode mode = deathDrop
                ? TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                : TrackingObservationUseCase.EvidenceMode.RECONCILIATION;
        scanner.scanItemTree(
                item.getItemStack(),
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.PRESENT,
                mode,
                "item-spawn",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        int remaining = MAX_ITEMS_PER_SCAN;
        for (ItemStack item : event.getDrops()) {
            if (remaining <= 0) {
                break;
            }
            remaining--;
            for (LoreItemIdentity identity : scanner.trackedIdentities(item)) {
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
        blockInventoryTracking.onBlockBreak(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        blockInventoryTracking.onBlockPlace(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpecialBlockInventoryInteract(PlayerInteractEvent event) {
        blockInventoryTracking.onSpecialBlockInventoryInteract(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        observeNaturalChunkLoad(PaperTrackingScanRequest.ChunkReference.capture(event.getChunk()), "chunk-load");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        chunkLedger.unload(PaperTrackingScanRequest.ChunkReference.capture(event.getChunk()));
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
        startupDiscovery.seed(currentBudget(), this::observeNaturalChunkLoad);
        restoreRejectedScans();
        for (int n = 0, budget = currentBudget(); n < budget; n++) {
            PaperTrackingScanRequest request = scans.poll();
            if (request == null) break;
            periodicChunkKeys.release(request);
            PaperChunkLoadLedger.Pending pending = token(request);
            if (pending != null && !chunkLedger.begin(pending)) continue;
            boolean complete = scanTelemetry.execute(request, plugin, this);
            if (pending != null) {
                World world = plugin.getServer().getWorld(pending.key().worldId());
                boolean accessible = world != null && world.isChunkLoaded(pending.key().x(), pending.key().z());
                chunkLedger.complete(pending, complete, accessible, tick);
            }
            if (System.nanoTime() - start >= MAX_DRAIN_NANOS) break;
        }
        if (tick % SCAN_DIAGNOSTICS_INTERVAL_TICKS == 0L) {
            PaperChunkLoadLedger.Pending due = chunkLedger.nextTrackedDue(
                    tick, TRACKED_RECONCILIATION_TICKS);
            if (due != null) enqueueNatural(due, "tracked-loaded-chunk");
            metrics.setGauge("tracking.scan_dirty_locations", chunkLedger.dirtyCount());
            metrics.setGauge("tracking.scan_known_loaded", chunkLedger.loadedCount());
        }
        scanTelemetry.drained(scans.isEmpty(), plugin.getLogger());
        scanTelemetry.updateDepth(scans.size(), periodicChunkKeys.size());
        metrics.setGauge("tracking.scan_drain_peak_nanos", System.nanoTime() - start);
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
        int capacity = maxQueuedScans();
        if (restoreDeferredOnlinePlayers(capacity)) {
            restoreDirtyChunkScans(capacity);
        }
    }

    private boolean restoreDeferredOnlinePlayers(int capacity) {
        int slots = Math.max(0, Math.min(currentBudget(), capacity - scans.size()));
        for (int attempt = 0;
                attempt < slots && !deferredOnlinePlayers.isEmpty();
                attempt++) {
            UUID playerId = deferredOnlinePlayers.iterator().next();
            deferredOnlinePlayers.remove(playerId);
            if (plugin.getServer().getPlayer(playerId) != null
                    && !enqueue(PaperTrackingScanRequest.player(
                            playerId, true, "player-scan-retry"))) {
                deferredOnlinePlayers.add(playerId);
                return false;
            }
        }
        return true;
    }

    private void restoreDirtyChunkScans(int capacity) {
        int slots = Math.max(0, Math.min(currentBudget(), capacity - scans.size()));
        for (int attempt = 0; attempt < slots; attempt++) {
            PaperChunkLoadLedger.Pending pending = chunkLedger.nextDirty(tick);
            if (pending == null) {
                break;
            }
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

    boolean enqueue(PaperTrackingScanRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed || !periodicChunkKeys.tryRegister(request, metrics)) {
            return false;
        }
        int maximum = maxQueuedScans();
        if (!admitOrRejectAtCapacity(request, maximum)) {
            return false;
        }
        scans.add(request);
        scanTelemetry.updateDepth(scans.size(), periodicChunkKeys.size());
        if (scans.size() == maximum) scanTelemetry.saturated(plugin.getLogger());
        return true;
    }

    private boolean admitOrRejectAtCapacity(PaperTrackingScanRequest request, int maximum) {
        if (scans.size() < maximum) {
            return true;
        }
        if (scans.size() == maximum && scanAdmission.makeRoomFor(request)) {
            return true;
        }
        periodicChunkKeys.release(request);
        scanTelemetry.reject(request);
        scanTelemetry.saturated(plugin.getLogger());
        if (request.playerId() != null
                && plugin.getServer().getPlayer(request.playerId()) != null) {
            deferredOnlinePlayers.add(request.playerId());
        }
        return false;
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

    private void submitMatchingIdentities(
            Optional<PaperPhysicalInventorySnapshot> snapshot,
            Set<LoreItemIdentity> identities,
            String source) {
        if (identities.isEmpty()) {
            return;
        }
        snapshot.ifPresent(reference -> reference.resolve(plugin).ifPresent(inventory ->
                scanner.submitMatchingIdentities(
                        inventory,
                        reference.type(),
                        reference.key(),
                        identities,
                        source)));
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
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        entityScanner.scan(List.of(chunk.getEntities()), presence, source, limit);
        chunkContainers.scan(chunk, presence, source, limit);
        if (!limit.hasRemaining()) {
            metrics.increment("tracking.scan_truncated");
            plugin.getLogger().fine(
                    "Lore-item chunk scan reached its bounded item limit for " + source + '.');
            return false;
        }
        return true;
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
        startupDiscovery.clear();
        chunkLedger.clear();
        deferredOnlinePlayers.clear();
        deferredActions.close();
        pendingPlayerScans.clear();
        scans.clear();
        periodicChunkKeys.clear();
        scanTelemetry.updateDepth(scans.size(), periodicChunkKeys.size());
        deathDrops.clear();
        scanTelemetry.reset();
        coordinator.close();
    }
}
