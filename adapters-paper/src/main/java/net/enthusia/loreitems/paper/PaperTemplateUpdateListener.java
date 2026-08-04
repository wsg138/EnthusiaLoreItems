package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Natural-access discovery for player and inventory-backed template updates. */
final class PaperTemplateUpdateListener implements Listener, AutoCloseable {
    private static final int MIN_BUDGET = 1;
    private static final int SCAN_QUEUE_MULTIPLIER = 32;
    private static final int MIN_SCAN_QUEUE_CAPACITY = 512;
    private static final int MAX_SCAN_QUEUE_CAPACITY = 4_096;

    private final Plugin plugin;
    private final IntSupplier budgetSupplier;
    private final PaperTemplateUpdateScanner scanner = new PaperTemplateUpdateScanner();
    private final PaperTemplateUpdateAccessRegistry accessRegistry =
            new PaperTemplateUpdateAccessRegistry();
    private final PaperTemplateUpdateCoordinator coordinator;
    private final PaperTemplateUpdateScanBacklog scanBacklog;
    private final PaperTemplateUpdateRetryBacklog retryBacklog;

    private BukkitTask scanTask;
    private boolean saturated;
    private boolean closed;

    PaperTemplateUpdateListener(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            IntSupplier budgetSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.budgetSupplier = Objects.requireNonNull(budgetSupplier, "budgetSupplier");
        int budget = currentBudget();
        this.coordinator = new PaperTemplateUpdateCoordinator(
                plugin,
                Objects.requireNonNull(useCase, "useCase"),
                Objects.requireNonNull(operator, "operator"),
                budget);
        int backlogCapacity = maxQueuedScans(budget);
        this.scanBacklog = new PaperTemplateUpdateScanBacklog(backlogCapacity);
        this.retryBacklog = new PaperTemplateUpdateRetryBacklog(backlogCapacity);
    }

    void start() {
        if (closed || scanTask != null) {
            throw new IllegalStateException("Template-update listener cannot be started");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getOnlinePlayers().forEach(this::enqueuePlayer);
        try {
            scanTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::drain, 1L, 1L);
        } catch (RuntimeException exception) {
            HandlerList.unregisterAll(this);
            throw exception;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        enqueuePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerReferences references = playerReferences(event.getPlayer());
        forget(references.main());
        forget(references.ender());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        enqueue(new PaperInventoryReference.PlayerMain(
                event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleViewRescan(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleViewRescan(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        enqueuePlayer(player);
        PaperInventoryReference.capture(event.getView().getTopInventory())
                .ifPresent(this::enqueue);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Optional<PaperInventoryReference> source =
                PaperInventoryReference.capture(event.getSource());
        Optional<PaperInventoryReference> destination =
                PaperInventoryReference.capture(event.getDestination());
        source.ifPresent(this::invalidate);
        destination.ifPresent(this::invalidate);
        scheduleNextTick(() -> {
            source.ifPresent(this::enqueue);
            destination.ifPresent(this::enqueue);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Optional<PaperInventoryReference> inventory =
                PaperInventoryReference.capture(event.getInventory());
        inventory.ifPresent(this::invalidate);
        scheduleNextTick(() -> inventory.ifPresent(this::enqueue));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            PaperInventoryReference.PlayerMain main =
                    new PaperInventoryReference.PlayerMain(player.getUniqueId());
            invalidate(main);
            scheduleNextTick(() -> enqueue(main));
        }
    }

    private void scheduleViewRescan(Player player, Inventory topInventory) {
        PaperInventoryReference.PlayerMain main =
                new PaperInventoryReference.PlayerMain(player.getUniqueId());
        Optional<PaperInventoryReference> top = PaperInventoryReference.capture(topInventory);
        invalidate(main);
        top.ifPresent(this::invalidate);
        scheduleNextTick(() -> {
            enqueue(main);
            top.ifPresent(this::enqueue);
        });
    }

    private void enqueuePlayer(Player player) {
        PlayerReferences references = playerReferences(player);
        enqueue(references.main());
        enqueue(references.ender());
    }

    private static PlayerReferences playerReferences(Player player) {
        return new PlayerReferences(
                new PaperInventoryReference.PlayerMain(player.getUniqueId()),
                new PaperInventoryReference.PlayerEnder(player.getUniqueId()));
    }

    private void invalidate(PaperInventoryReference reference) {
        scanner.reset(reference);
        accessRegistry.invalidate(reference);
    }

    private void enqueue(PaperInventoryReference reference) {
        if (closed) {
            return;
        }
        retryBacklog.remove(reference);
        invalidate(reference);
        offer(reference);
    }

    private void enqueueContinuation(PaperInventoryReference reference) {
        if (!closed) {
            offer(reference);
        }
    }

    private void offer(PaperInventoryReference reference) {
        PaperTemplateUpdateScanBacklog.OfferResult result = scanBacklog.offer(reference);
        switch (result) {
            case READY, ALREADY_QUEUED -> {
                // The reference is retained by the bounded backlog.
            }
            case DEFERRED -> reportSaturation();
            case REJECTED -> {
                scanner.reset(reference);
                accessRegistry.markIncomplete(reference);
                retryBacklog.offer(reference);
                reportSaturation();
            }
            default -> throw new IllegalStateException(
                    "Unsupported template-update backlog result: " + result);
        }
    }

    private void forget(PaperInventoryReference reference) {
        scanBacklog.remove(reference);
        retryBacklog.remove(reference);
        scanner.reset(reference);
        accessRegistry.remove(reference);
    }

    private void drain() {
        int budget = currentBudget();
        for (int count = 0; count < budget; count++) {
            PaperInventoryReference reference = scanBacklog.poll();
            if (reference == null) {
                break;
            }
            scan(reference);
        }
        if (scanBacklog.isEmpty()) {
            retryRejectedScans(budget);
        }
        if (scanBacklog.isEmpty()) {
            dispatchUniqueAccessibleItems();
            if (saturated) {
                saturated = false;
                plugin.getLogger().fine("Template-update scan backlog has drained.");
            }
        }
    }

    private void retryRejectedScans(int budget) {
        for (int count = 0; count < budget; count++) {
            PaperInventoryReference reference = retryBacklog.poll();
            if (reference == null) {
                return;
            }
            enqueue(reference);
        }
    }

    private void scan(PaperInventoryReference reference) {
        Optional<Inventory> inventory = reference.resolve(plugin);
        if (inventory.isEmpty()) {
            scanner.reset(reference);
            accessRegistry.remove(reference);
            return;
        }
        List<PaperTemplateUpdateScanner.Candidate> observed = new ArrayList<>();
        PaperTemplateUpdateScanner.ScanResult result;
        try {
            result = scanner.scan(
                    plugin,
                    inventory.orElseThrow(),
                    observed::add);
        } catch (RuntimeException exception) {
            scanner.reset(reference);
            accessRegistry.markIncomplete(reference);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not scan a naturally accessible inventory for template updates.",
                    exception);
            return;
        }
        if (result.abandoned()) {
            accessRegistry.markIncomplete(reference);
            plugin.getLogger().warning(
                    "A naturally accessible template-update scan exceeded its bounded "
                            + "continuation limits; durable mutations remain pending.");
        } else if (result.continuationRequired()) {
            enqueueContinuation(reference);
        } else {
            accessRegistry.replace(reference, observed);
        }
    }

    private void dispatchUniqueAccessibleItems() {
        dispatchCandidates(
                accessRegistry.drainUnique(plugin.getServer().getOnlinePlayers()),
                coordinator::submit,
                this::enqueue);
    }

    static void dispatchCandidates(
            List<PaperTemplateUpdateScanner.Candidate> candidates,
            Predicate<PaperTemplateUpdateScanner.Candidate> submitter,
            Consumer<PaperInventoryReference> retrySink) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(submitter, "submitter");
        Objects.requireNonNull(retrySink, "retrySink");
        for (PaperTemplateUpdateScanner.Candidate candidate : candidates) {
            if (!submitter.test(candidate)) {
                retrySink.accept(candidate.reference().inventoryReference());
            }
        }
    }

    private int currentBudget() {
        int value = budgetSupplier.getAsInt();
        if (value < MIN_BUDGET) {
            throw new IllegalStateException("Configured template-update budget must be positive");
        }
        return value;
    }

    private static int maxQueuedScans(int budget) {
        long scaled = (long) budget * SCAN_QUEUE_MULTIPLIER;
        return (int) Math.min(
                MAX_SCAN_QUEUE_CAPACITY,
                Math.max(MIN_SCAN_QUEUE_CAPACITY, scaled));
    }

    private void reportSaturation() {
        if (!saturated) {
            saturated = true;
            plugin.getLogger().warning(
                    "Template-update scan backlog is saturated; durable mutations remain pending.");
        }
    }

    private void scheduleNextTick(Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule natural template-update discovery during shutdown.",
                    exception);
        }
    }

    @Override
    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        BukkitTask task = scanTask;
        if (task != null) {
            task.cancel();
        }
        scanBacklog.clear();
        retryBacklog.clear();
        scanner.clear();
        accessRegistry.clear();
        coordinator.close();
    }

    private record PlayerReferences(
            PaperInventoryReference.PlayerMain main,
            PaperInventoryReference.PlayerEnder ender) {
        private PlayerReferences {
            Objects.requireNonNull(main, "main");
            Objects.requireNonNull(ender, "ender");
        }
    }
}

/** Two-tier bounded FIFO that retains transient scan bursts without unbounded allocation. */
final class PaperTemplateUpdateScanBacklog {
    private static final int MIN_TIER_CAPACITY = 1;

    enum OfferResult {
        READY,
        DEFERRED,
        ALREADY_QUEUED,
        REJECTED
    }

    private final int tierCapacity;
    private final Queue<PaperInventoryReference> ready = new ArrayDeque<>();
    private final Queue<PaperInventoryReference> deferred = new ArrayDeque<>();
    private final Set<PaperInventoryReference> queued = new HashSet<>();

    PaperTemplateUpdateScanBacklog(int tierCapacity) {
        if (tierCapacity < MIN_TIER_CAPACITY) {
            throw new IllegalArgumentException("tierCapacity must be positive");
        }
        this.tierCapacity = tierCapacity;
    }

    OfferResult offer(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (!queued.add(reference)) {
            return OfferResult.ALREADY_QUEUED;
        }
        if (ready.size() < tierCapacity) {
            ready.add(reference);
            return OfferResult.READY;
        }
        if (deferred.size() < tierCapacity) {
            deferred.add(reference);
            return OfferResult.DEFERRED;
        }
        queued.remove(reference);
        return OfferResult.REJECTED;
    }

    PaperInventoryReference poll() {
        PaperInventoryReference reference = ready.poll();
        if (reference == null) {
            return null;
        }
        queued.remove(reference);
        promoteDeferred();
        return reference;
    }

    void remove(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        boolean removed = ready.remove(reference);
        removed = deferred.remove(reference) || removed;
        queued.remove(reference);
        if (removed) {
            promoteDeferred();
        }
    }

    boolean isEmpty() {
        return ready.isEmpty() && deferred.isEmpty();
    }

    void clear() {
        ready.clear();
        deferred.clear();
        queued.clear();
    }

    private void promoteDeferred() {
        while (ready.size() < tierCapacity && !deferred.isEmpty()) {
            ready.add(deferred.remove());
        }
    }
}

/** Deduplicated references rejected only because the bounded scan backlog was full. */
final class PaperTemplateUpdateRetryBacklog {
    private static final int MIN_CAPACITY = 1;

    private final int capacity;
    private final Queue<PaperInventoryReference> queued = new ArrayDeque<>();
    private final Set<PaperInventoryReference> references = new HashSet<>();

    PaperTemplateUpdateRetryBacklog(int capacity) {
        if (capacity < MIN_CAPACITY) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    boolean offer(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (references.contains(reference)) {
            return false;
        }
        if (queued.size() >= capacity || !references.add(reference)) {
            return false;
        }
        queued.add(reference);
        return true;
    }

    PaperInventoryReference poll() {
        PaperInventoryReference reference = queued.poll();
        if (reference != null) {
            references.remove(reference);
        }
        return reference;
    }

    void remove(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (references.remove(reference)) {
            queued.remove(reference);
        }
    }

    void clear() {
        queued.clear();
        references.clear();
    }
}
