package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.ArrayDeque;
import java.util.HashSet;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Natural-access discovery for player and inventory-backed template updates. */
final class PaperTemplateUpdateListener implements Listener, AutoCloseable {
    private static final int MIN_BUDGET = 1;
    private static final int SCAN_QUEUE_MULTIPLIER = 32;

    private final Plugin plugin;
    private final IntSupplier budgetSupplier;
    private final PaperTemplateUpdateScanner scanner = new PaperTemplateUpdateScanner();
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
        enqueue(new PaperInventoryReference.PlayerMain(player.getUniqueId()));
        enqueue(new PaperInventoryReference.PlayerEnder(player.getUniqueId()));
    }

    private void enqueue(PaperInventoryReference reference) {
        if (closed || !queuedReferences.add(reference)) {
            return;
        }
        if (scans.size() >= maxQueuedScans()) {
            queuedReferences.remove(reference);
            reportSaturation();
            return;
        }
        scans.add(reference);
        if (scans.size() == maxQueuedScans()) {
            reportSaturation();
        }
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
        if (scans.isEmpty() && saturated) {
            saturated = false;
            plugin.getLogger().fine("Template-update scan backlog has drained.");
        }
    }

    private void scan(PaperInventoryReference reference) {
        Optional<Inventory> inventory = reference.resolve(plugin);
        if (inventory.isEmpty()) {
            return;
        }
        PaperTemplateUpdateScanner.ScanResult result = scanner.scan(
                inventory.orElseThrow(), coordinator::submit);
        if (result.truncated()) {
            plugin.getLogger().fine(
                    "A naturally accessible template-update scan reached its bounded item limit.");
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
        return Math.multiplyExact(currentBudget(), SCAN_QUEUE_MULTIPLIER);
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
        coordinator.close();
    }
}
