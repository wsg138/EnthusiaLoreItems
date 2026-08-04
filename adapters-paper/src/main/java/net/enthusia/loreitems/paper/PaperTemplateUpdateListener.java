package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Natural-access discovery for player and inventory-backed template updates. */
final class PaperTemplateUpdateListener implements AutoCloseable {
    private static final int MIN_BUDGET = 1;
    private static final int SCAN_QUEUE_MULTIPLIER = 32;
    private static final int MIN_SCAN_QUEUE_CAPACITY = 512;
    private static final int MAX_SCAN_QUEUE_CAPACITY = 4_096;

    private final Plugin plugin;
    private final int budget;
    private final PaperTemplateUpdateScanner scanner = new PaperTemplateUpdateScanner();
    private final PaperTemplateUpdateAccessRegistry accessRegistry =
            new PaperTemplateUpdateAccessRegistry();
    private final PaperTemplateUpdateCoordinator coordinator;
    private final PaperTemplateUpdateScanBacklog scanBacklog;
    private final PaperTemplateUpdateRetryBacklog retryBacklog;
    private final PaperTemplateUpdateEvents events;

    private BukkitTask scanTask;
    private boolean saturated;
    private boolean closed;

    PaperTemplateUpdateListener(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            int budget) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (budget < MIN_BUDGET) {
            throw new IllegalArgumentException("budget must be positive");
        }
        this.budget = budget;
        this.coordinator = new PaperTemplateUpdateCoordinator(
                plugin,
                Objects.requireNonNull(useCase, "useCase"),
                Objects.requireNonNull(operator, "operator"),
                budget);
        int backlogCapacity = maxQueuedScans(budget);
        this.scanBacklog = new PaperTemplateUpdateScanBacklog(backlogCapacity);
        this.retryBacklog = new PaperTemplateUpdateRetryBacklog(backlogCapacity);
        this.events = new PaperTemplateUpdateEvents(this);
    }

    void start() {
        if (closed || scanTask != null) {
            throw new IllegalStateException("Template-update listener cannot be started");
        }
        plugin.getServer().getPluginManager().registerEvents(events, plugin);
        plugin.getServer().getOnlinePlayers().forEach(this::enqueuePlayer);
        try {
            scanTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::drain, 1L, 1L);
        } catch (RuntimeException exception) {
            HandlerList.unregisterAll(events);
            throw exception;
        }
    }

    void enqueuePlayer(Player player) {
        PlayerReferences references = playerReferences(player);
        enqueue(references.main());
        enqueue(references.ender());
    }

    void scheduleViewRescan(Player player, Inventory topInventory) {
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

    void invalidate(PaperInventoryReference reference) {
        scanner.reset(reference);
        accessRegistry.invalidate(reference);
    }

    void enqueue(PaperInventoryReference reference) {
        if (closed) {
            return;
        }
        retryBacklog.remove(reference);
        invalidate(reference);
        offer(reference);
    }

    void forget(PaperInventoryReference reference) {
        scanBacklog.remove(reference);
        retryBacklog.remove(reference);
        scanner.reset(reference);
        accessRegistry.remove(reference);
    }

    void scheduleNextTick(Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule natural template-update discovery during shutdown.",
                    exception);
        }
    }

    private static PlayerReferences playerReferences(Player player) {
        return new PlayerReferences(
                new PaperInventoryReference.PlayerMain(player.getUniqueId()),
                new PaperInventoryReference.PlayerEnder(player.getUniqueId()));
    }

    private void enqueueContinuation(PaperInventoryReference reference) {
        if (!closed) {
            offer(reference);
        }
    }

    private void offer(PaperInventoryReference reference) {
        PaperTemplateUpdateScanOfferResult result = scanBacklog.offer(reference);
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

    private void drain() {
        for (int count = 0; count < budget; count++) {
            PaperInventoryReference reference = scanBacklog.poll();
            if (reference == null) {
                break;
            }
            scan(reference);
        }
        if (scanBacklog.isEmpty()) {
            retryRejectedScans();
        }
        if (scanBacklog.isEmpty()) {
            dispatchUniqueAccessibleItems();
            if (saturated) {
                saturated = false;
                plugin.getLogger().fine("Template-update scan backlog has drained.");
            }
        }
    }

    private void retryRejectedScans() {
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

    @Override
    public void close() {
        closed = true;
        HandlerList.unregisterAll(events);
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
