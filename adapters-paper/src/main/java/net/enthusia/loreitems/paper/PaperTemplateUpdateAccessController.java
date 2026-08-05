package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

/** Bounded scan and execution controller for naturally accessible inventory-backed items. */
final class PaperTemplateUpdateAccessController implements AutoCloseable {
    private static final int MIN_BUDGET = 1;

    private final Plugin plugin;
    private final int budget;
    private final PaperTemplateUpdateScanner scanner;
    private final PaperTemplateUpdateAccessRegistry accessRegistry =
            new PaperTemplateUpdateAccessRegistry();
    private final PaperTemplateUpdateCoordinator coordinator;
    private final PaperTemplateUpdateScanBacklog scanBacklog;
    private final PaperTemplateUpdateRetryBacklog retryBacklog;

    private boolean saturated;
    private boolean closed;

    PaperTemplateUpdateAccessController(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            int budget) {
        this(plugin, useCase, operator, budget, new PaperTemplateUpdateScanner());
    }

    PaperTemplateUpdateAccessController(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            int budget,
            PaperTemplateUpdateScanner scanner) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        if (budget < MIN_BUDGET) {
            throw new IllegalArgumentException("budget must be positive");
        }
        this.budget = budget;
        this.coordinator = new PaperTemplateUpdateCoordinator(
                plugin,
                Objects.requireNonNull(useCase, "useCase"),
                Objects.requireNonNull(operator, "operator"),
                budget);
        int backlogCapacity = PaperTemplateUpdateScanBacklog.capacityForBudget(budget);
        this.scanBacklog = new PaperTemplateUpdateScanBacklog(backlogCapacity);
        this.retryBacklog = new PaperTemplateUpdateRetryBacklog(backlogCapacity);
    }

    void enqueuePlayer(Player player) {
        Objects.requireNonNull(player, "player");
        enqueue(new PaperInventoryReference.PlayerMain(player.getUniqueId()));
        enqueue(new PaperInventoryReference.PlayerEnder(player.getUniqueId()));
    }

    void scheduleViewRescan(Player player, Inventory topInventory) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(topInventory, "topInventory");
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
        Objects.requireNonNull(reference, "reference");
        if (closed) {
            return;
        }
        retryBacklog.remove(reference);
        invalidate(reference);
        offer(reference);
    }

    void forget(PaperInventoryReference reference) {
        Objects.requireNonNull(reference, "reference");
        scanBacklog.remove(reference);
        retryBacklog.remove(reference);
        scanner.reset(reference);
        accessRegistry.remove(reference);
    }

    void scheduleNextTick(Runnable action) {
        Objects.requireNonNull(action, "action");
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule natural template-update discovery during shutdown.",
                    exception);
        }
    }

    void drain() {
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
            result = scanner.scan(plugin, inventory.orElseThrow(), observed::add);
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
            scanner.reset(reference);
            accessRegistry.markIncomplete(reference);
            retryBacklog.offer(reference);
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
        PaperTemplateUpdateCandidateDispatcher.dispatch(
                accessRegistry.drainUnique(plugin.getServer().getOnlinePlayers()),
                coordinator::submit,
                this::enqueue);
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
        scanBacklog.clear();
        retryBacklog.clear();
        scanner.clear();
        accessRegistry.clear();
        coordinator.close();
    }
}
