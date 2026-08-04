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
import java.util.function.IntSupplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
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
    private final Queue<PaperInventoryReference> scans = new ArrayDeque<>();
    private final Set<PaperInventoryReference> queuedReferences = new HashSet<>();

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
        UUIDPair references = playerReferences(event.getPlayer());
        forget(references.main());
        forget(references.ender());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        enqueue(new PaperInventoryReference.PlayerMain(
                event.getPlayer().getUniqueId()));
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
        scheduleNextTick(() -> {
            source.ifPresent(this::enqueue);
            destination.ifPresent(this::enqueue);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Optional<PaperInventoryReference> inventory =
                PaperInventoryReference.capture(event.getInventory());
        scheduleNextTick(() -> inventory.ifPresent(this::enqueue));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            scheduleNextTick(() -> enqueue(
                    new PaperInventoryReference.PlayerMain(player.getUniqueId())));
        }
    }

    private void enqueuePlayer(Player player) {
        UUIDPair references = playerReferences(player);
        enqueue(references.main());
        enqueue(references.ender());
    }

    private static UUIDPair playerReferences(Player player) {
        return new UUIDPair(
                new PaperInventoryReference.PlayerMain(player.getUniqueId()),
                new PaperInventoryReference.PlayerEnder(player.getUniqueId()));
    }

    private void enqueue(PaperInventoryReference reference) {
        if (closed) {
            return;
        }
        scanner.reset(reference);
        accessRegistry.invalidate(reference);
        if (!queuedReferences.add(reference)) {
            return;
        }
        queue(reference, true);
    }

    private void enqueueContinuation(PaperInventoryReference reference) {
        if (closed || !queuedReferences.add(reference)) {
            return;
        }
        queue(reference, false);
    }

    private void queue(PaperInventoryReference reference, boolean contentChanged) {
        if (scans.size() >= maxQueuedScans()) {
            queuedReferences.remove(reference);
            scanner.reset(reference);
            accessRegistry.markIncomplete(reference);
            reportSaturation();
            return;
        }
        scans.add(reference);
        if (contentChanged) {
            accessRegistry.invalidate(reference);
        }
        if (scans.size() == maxQueuedScans()) {
            reportSaturation();
        }
    }

    private void forget(PaperInventoryReference reference) {
        queuedReferences.remove(reference);
        scans.remove(reference);
        scanner.reset(reference);
        accessRegistry.remove(reference);
    }

    private void drain() {
        int budget = currentBudget();
        for (int count = 0; count < budget; count++) {
            PaperInventoryReference reference = scans.poll();
            if (reference == null) {
                break;
            }
            queuedReferences.remove(reference);
            scan(reference);
        }
        if (scans.isEmpty()) {
            dispatchUniqueAccessibleItems();
            if (saturated) {
                saturated = false;
                plugin.getLogger().fine("Template-update scan backlog has drained.");
            }
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
        for (PaperTemplateUpdateScanner.Candidate candidate : accessRegistry.drainUnique(
                plugin.getServer().getOnlinePlayers())) {
            coordinator.submit(candidate);
        }
    }

    private int currentBudget() {
        int value = budgetSupplier.getAsInt();
        if (value < MIN_BUDGET) {
            throw new IllegalStateException("Configured template-update budget must be positive");
        }
        return value;
    }

    private int maxQueuedScans() {
        long scaled = (long) currentBudget() * SCAN_QUEUE_MULTIPLIER;
        return (int) Math.min(
                MAX_SCAN_QUEUE_CAPACITY,
                Math.max(MIN_SCAN_QUEUE_CAPACITY, scaled));
    }

    private void reportSaturation() {
        if (!saturated) {
            saturated = true;
            plugin.getLogger().warning(
                    "Template-update scan backlog is full; durable mutations remain pending.");
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
        scans.clear();
        queuedReferences.clear();
        scanner.clear();
        accessRegistry.clear();
        coordinator.close();
    }

    private record UUIDPair(
            PaperInventoryReference.PlayerMain main,
            PaperInventoryReference.PlayerEnder ender) {
        private UUIDPair {
            Objects.requireNonNull(main, "main");
            Objects.requireNonNull(ender, "ender");
        }
    }
}
