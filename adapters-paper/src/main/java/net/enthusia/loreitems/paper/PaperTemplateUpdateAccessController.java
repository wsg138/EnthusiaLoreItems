package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private final PaperTemplateUpdateAccessRegistry accessRegistry;
    private final PaperTemplateUpdateCoordinator coordinator;
    private final PaperTemplateUpdateScanBacklog scanBacklog;
    private final PaperTemplateUpdateRetryBacklog retryBacklog;
    private final boolean ownsCoordinator;
    private final PaperLoadedContainerWalker loadedContainerWalker;

    private boolean saturated;
    private boolean loadedContainerSweep;
    private boolean loadedContainerRescanRequired;
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
        this(plugin, useCase, operator, budget, scanner, new PaperLoadedContainerWalker());
    }

    PaperTemplateUpdateAccessController(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            int budget,
            PaperTemplateUpdateScanner scanner,
            PaperLoadedContainerWalker loadedContainerWalker) {
        this(
                plugin,
                new PaperTemplateUpdateCoordinator(
                        plugin,
                        Objects.requireNonNull(useCase, "useCase"),
                        Objects.requireNonNull(operator, "operator"),
                        budget),
                new PaperTemplateUpdateAccessRegistry(),
                budget,
                scanner,
                loadedContainerWalker,
                true);
    }

    PaperTemplateUpdateAccessController(
            Plugin plugin,
            PaperTemplateUpdateCoordinator coordinator,
            PaperTemplateUpdateAccessRegistry accessRegistry,
            int budget) {
        this(
                plugin,
                coordinator,
                accessRegistry,
                budget,
                new PaperTemplateUpdateScanner(),
                new PaperLoadedContainerWalker(),
                false);
    }

    private PaperTemplateUpdateAccessController(
            Plugin plugin,
            PaperTemplateUpdateCoordinator coordinator,
            PaperTemplateUpdateAccessRegistry accessRegistry,
            int budget,
            PaperTemplateUpdateScanner scanner,
            PaperLoadedContainerWalker loadedContainerWalker,
            boolean ownsCoordinator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.accessRegistry = Objects.requireNonNull(accessRegistry, "accessRegistry");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.loadedContainerWalker = Objects.requireNonNull(
                loadedContainerWalker, "loadedContainerWalker");
        if (budget < MIN_BUDGET) {
            throw new IllegalArgumentException("budget must be positive");
        }
        this.budget = budget;
        this.ownsCoordinator = ownsCoordinator;
        int backlogCapacity = PaperTemplateUpdateScanBacklog.capacityForBudget(budget);
        this.scanBacklog = new PaperTemplateUpdateScanBacklog(backlogCapacity);
        this.retryBacklog = new PaperTemplateUpdateRetryBacklog(backlogCapacity);
    }

    void enqueuePlayer(Player player) {
        Objects.requireNonNull(player, "player");
        enqueue(new PaperInventoryReference.PlayerMain(player.getUniqueId()));
        enqueue(new PaperInventoryReference.PlayerEnder(player.getUniqueId()));
    }

    void wakeAccessible() {
        if (closed) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            enqueuePlayer(player);
            PaperInventoryReference.capture(player.getOpenInventory().getTopInventory())
                    .filter(reference -> !(reference instanceof PaperInventoryReference.PlayerMain)
                            && !(reference instanceof PaperInventoryReference.PlayerEnder))
                    .ifPresent(this::enqueue);
        }
        if (loadedContainerSweep) {
            loadedContainerRescanRequired = true;
        } else {
            beginLoadedContainerSweep();
        }
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
        for (int count = 0; count < budget && drainOne(); count++) {
            // Work is deliberately bounded by the configured per-tick budget.
        }
        finishDrain();
    }

    private boolean drainOne() {
        PaperInventoryReference reference = scanBacklog.poll();
        if (reference != null) {
            scan(reference);
            return true;
        }
        if (!loadedContainerSweep) {
            return false;
        }
        PaperLoadedContainerWalker.Step step = loadedContainerWalker.visitNext(plugin);
        if (step.containerReference() != null) {
            enqueue(step.containerReference());
        }
        if (step.sweepComplete()) {
            finishLoadedContainerSweep();
        }
        return true;
    }

    private void finishDrain() {
        if (discoveryPending()) {
            return;
        }
        retryRejectedScans();
        if (discoveryPending()) {
            return;
        }
        dispatchUniqueAccessibleItems();
        if (saturated) {
            saturated = false;
            plugin.getLogger().fine("Template-update scan backlog has drained.");
        }
    }

    private boolean discoveryPending() {
        return !scanBacklog.isEmpty() || loadedContainerSweep;
    }


    private void beginLoadedContainerSweep() {
        loadedContainerWalker.clear();
        loadedContainerSweep = true;
        loadedContainerRescanRequired = false;
    }

    private void finishLoadedContainerSweep() {
        if (loadedContainerRescanRequired) {
            beginLoadedContainerSweep();
            return;
        }
        loadedContainerSweep = false;
        loadedContainerWalker.clear();
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
            retryIncomplete(reference);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not scan a naturally accessible inventory for template updates.",
                    exception);
            return;
        }
        if (result.abandoned()) {
            retryIncomplete(reference);
            plugin.getLogger().warning(
                    "A naturally accessible template-update scan exceeded its bounded "
                            + "continuation limits; durable mutations remain pending.");
        } else if (result.continuationRequired()) {
            enqueueContinuation(reference);
        } else {
            accessRegistry.replace(reference, observed);
        }
    }

    private void retryIncomplete(PaperInventoryReference reference) {
        scanner.reset(reference);
        accessRegistry.markIncomplete(reference);
        if (!retryBacklog.offer(reference)) {
            offer(reference);
        }
    }

    private void dispatchUniqueAccessibleItems() {
        PaperTemplateUpdateAccessRegistry.DispatchBatch batch =
                accessRegistry.prepareDispatch(plugin.getServer().getOnlinePlayers());
        if (batch.candidates().isEmpty()) {
            accessRegistry.finishDispatch(batch, Set.of());
            return;
        }
        Set<UUID> rejectedInstances = new HashSet<>();
        Set<PaperInventoryReference> inventoryRetries = new HashSet<>();
        for (PaperTemplateUpdateScanner.Candidate candidate : batch.candidates()) {
            if (!coordinator.submit(candidate)) {
                UUID instanceId = candidate.identity().instanceId().value();
                rejectedInstances.add(instanceId);
                if (candidate.reference()
                        instanceof PaperTemplateUpdateItemReference itemReference) {
                    inventoryRetries.add(itemReference.inventoryReference());
                }
            }
        }
        accessRegistry.finishDispatch(batch, rejectedInstances);
        inventoryRetries.forEach(this::enqueue);
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
        loadedContainerWalker.clear();
        loadedContainerSweep = false;
        scanner.clear();
        accessRegistry.clear();
        if (ownsCoordinator) {
            coordinator.close();
        }
    }

}
