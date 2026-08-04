package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingMetricsSource;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/** Paginated staff browser and explicit duplicate-location confirmation flow. */
public final class PaperTrackingAdministrationGui implements Listener {
    private static final int SIZE = 54;
    private static final int CONTENT = 45;
    private static final int PREVIOUS = 45;
    private static final int STATUS = 49;
    private static final int NEXT = 53;
    private static final int CONFIRM = 22;
    private static final int CANCEL = 31;
    private static final int MAX_QUERIES = 32;

    private final Plugin plugin;
    private final IntSupplier pageSizeSupplier;
    private final Semaphore queryCapacity = new Semaphore(MAX_QUERIES);

    public PaperTrackingAdministrationGui(Plugin plugin, IntSupplier pageSizeSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.pageSizeSupplier = Objects.requireNonNull(pageSizeSupplier, "pageSizeSupplier");
        currentPageSize();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void openDefinitions(UUID playerId, int pageNumber) {
        runMain(() -> openDefinitionsMain(playerId, pageNumber));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof View view)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.hasPermission(LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        switch (view.screen) {
            case DEFINITIONS -> clickDefinitions(player, view, slot);
            case INSTANCES -> clickInstances(player, view, slot);
            case EVIDENCE -> clickEvidence(player, view, slot);
            case CONFIRMATION -> clickConfirmation(player, view, slot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof View)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    private void openDefinitionsMain(UUID playerId, int pageNumber) {
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            messagePlayer(playerId, "Lore-item administration is unavailable.");
            return;
        }
        PageRequest request = pageRequest(pageNumber);
        submit(
                playerId,
                "definition browser",
                () -> useCase.listDefinitions(request),
                page -> showDefinitions(playerId, pageNumber, page));
    }

    private void clickDefinitions(Player player, View view, int slot) {
        if (slot == PREVIOUS && view.pageNumber > 1) {
            openDefinitionsMain(player.getUniqueId(), view.pageNumber - 1);
        } else if (slot == NEXT && view.hasMore) {
            openDefinitionsMain(player.getUniqueId(), view.pageNumber + 1);
        } else if (slot < view.definitionIds.size()) {
            openInstances(player.getUniqueId(), view.definitionIds.get(slot), 1);
        }
    }

    private void openInstances(
            UUID playerId, LoreDefinitionId definitionId, int pageNumber) {
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            messagePlayer(playerId, "Lore-item administration is unavailable.");
            return;
        }
        submit(
                playerId,
                "instance browser",
                () -> useCase.listInstances(definitionId, pageRequest(pageNumber)),
                page -> showInstances(playerId, definitionId, pageNumber, page));
    }

    private void clickInstances(Player player, View view, int slot) {
        if (slot == PREVIOUS) {
            if (view.pageNumber > 1) {
                openInstances(player.getUniqueId(), view.definitionId, view.pageNumber - 1);
            } else {
                openDefinitionsMain(player.getUniqueId(), 1);
            }
        } else if (slot == NEXT && view.hasMore) {
            openInstances(player.getUniqueId(), view.definitionId, view.pageNumber + 1);
        } else if (slot < view.instanceIds.size()) {
            openEvidence(player.getUniqueId(), view.instanceIds.get(slot), 1);
        }
    }

    private void openEvidence(UUID playerId, LoreInstanceId instanceId, int pageNumber) {
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            messagePlayer(playerId, "Lore-item administration is unavailable.");
            return;
        }
        if (!queryCapacity.tryAcquire()) {
            messagePlayer(playerId, "Too many lore-item administration queries are active.");
            return;
        }
        PageRequest request = pageRequest(pageNumber);
        try {
            CompletionStage<Optional<InstanceCurrentState>> current = Objects.requireNonNull(
                    useCase.findCurrentState(instanceId), "current-state query stage");
            CompletionStage<Page<InstanceObservation>> observations = Objects.requireNonNull(
                    useCase.listInstanceObservations(instanceId, request),
                    "observation query stage");
            CompletionStage<Page<InstanceAnomaly>> anomalies = Objects.requireNonNull(
                    useCase.listInstanceAnomalies(instanceId, PageRequest.first(CONTENT)),
                    "anomaly query stage");
            current.thenCombine(observations, StateEvidence::new)
                    .thenCombine(
                            anomalies,
                            (state, anomalyPage) -> new EvidenceData(
                                    state.current, state.observations, anomalyPage))
                    .whenComplete((data, failure) -> {
                        queryCapacity.release();
                        if (failure != null) {
                            handleFailure(playerId, "instance evidence", failure);
                        } else {
                            scheduleNextTick(() -> showEvidence(
                                    playerId, instanceId, pageNumber, data));
                        }
                    });
        } catch (RuntimeException exception) {
            queryCapacity.release();
            handleFailure(playerId, "instance evidence", exception);
        }
    }

    private void clickEvidence(Player player, View view, int slot) {
        if (slot == PREVIOUS) {
            if (view.pageNumber > 1) {
                openEvidence(player.getUniqueId(), view.instanceId, view.pageNumber - 1);
            } else {
                openDefinitionsMain(player.getUniqueId(), 1);
            }
            return;
        }
        if (slot == NEXT && view.hasMore) {
            openEvidence(player.getUniqueId(), view.instanceId, view.pageNumber + 1);
            return;
        }
        if (slot >= view.observations.size() || view.duplicate == null) {
            return;
        }
        ObservationChoice selected = view.observations.get(slot);
        if (selectable(selected, view.duplicate)) {
            showConfirmation(
                    player, view.instanceId, view.duplicate, selected, view.pageNumber);
        }
    }

    private void showConfirmation(
            Player player,
            LoreInstanceId instanceId,
            DuplicateChoice duplicate,
            ObservationChoice observation,
            int returnPage) {
        View view = View.confirmation(instanceId, duplicate, observation, returnPage);
        Inventory inventory = createInventory(view, "Confirm lore location");
        inventory.setItem(
                CONFIRM,
                item(
                        Material.LIME_CONCRETE,
                        "Confirm selected location",
                        List.of(
                                describe(observation.location),
                                "No physical copy will be deleted.",
                                "A later scan can reopen the conflict.")));
        inventory.setItem(
                CANCEL,
                item(
                        Material.BARRIER,
                        "Cancel",
                        List.of("Return without changing durable state.")));
        UUID playerId = player.getUniqueId();
        scheduleNextTick(() -> {
            Player target = authorizedPlayer(playerId);
            if (target != null) {
                target.openInventory(inventory);
            }
        });
    }

    private void clickConfirmation(Player player, View view, int slot) {
        UUID playerId = player.getUniqueId();
        if (slot == CANCEL) {
            openEvidence(playerId, view.instanceId, view.pageNumber);
            return;
        }
        if (slot != CONFIRM) {
            return;
        }
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            player.sendMessage("Lore-item administration is unavailable.");
            return;
        }
        LoreItemsAdministrationUseCase.DuplicateResolutionRequest request;
        try {
            request = new LoreItemsAdministrationUseCase.DuplicateResolutionRequest(
                    view.duplicate.anomalyId,
                    view.duplicate.stateRevision,
                    view.selectedObservation.observationId,
                    "player:" + playerId);
        } catch (IllegalArgumentException exception) {
            player.sendMessage("The selected duplicate evidence is no longer valid.");
            return;
        }
        scheduleNextTick(() -> {
            Player target = authorizedPlayer(playerId);
            if (target != null) {
                target.closeInventory();
            }
        });
        submit(
                playerId,
                "duplicate resolution",
                () -> useCase.resolveDuplicate(request),
                result -> {
                    messagePlayer(playerId, result.detail());
                    openEvidence(playerId, view.instanceId, view.pageNumber);
                });
    }

    private void showDefinitions(
            UUID playerId, int pageNumber, Page<LoreDefinition> page) {
        Player player = authorizedPlayer(playerId);
        if (player == null) {
            return;
        }
        List<LoreDefinitionId> ids = page.items().stream()
                .map(LoreDefinition::id)
                .toList();
        View view = View.definitions(pageNumber, page.hasMore(), ids);
        Inventory inventory = createInventory(view, "Lore definitions");
        for (int index = 0; index < page.items().size() && index < CONTENT; index++) {
            LoreDefinition definition = page.items().get(index);
            inventory.setItem(
                    index,
                    item(
                            Material.BOOK,
                            definition.displayName(),
                            List.of(
                                    "Key: " + definition.key().value(),
                                    "Revision: " + definition.currentRevision().value(),
                                    "Click to browse instances.")));
        }
        decorate(inventory, pageNumber, page.hasMore(), trackingMetricsLore());
        player.openInventory(inventory);
    }

    private void showInstances(
            UUID playerId,
            LoreDefinitionId definitionId,
            int pageNumber,
            Page<LoreInstance> page) {
        Player player = authorizedPlayer(playerId);
        if (player == null) {
            return;
        }
        List<LoreInstanceId> ids = page.items().stream()
                .map(LoreInstance::id)
                .toList();
        View view = View.instances(definitionId, pageNumber, page.hasMore(), ids);
        Inventory inventory = createInventory(view, "Lore instances");
        for (int index = 0; index < page.items().size() && index < CONTENT; index++) {
            LoreInstance instance = page.items().get(index);
            inventory.setItem(
                    index,
                    item(
                            Material.NETHER_STAR,
                            shortId(instance.id().value()),
                            List.of(
                                    "Lifecycle: " + instance.lifecycle().name(),
                                    "Applied revision: " + instance.appliedRevision().value(),
                                    "Desired revision: " + instance.desiredRevision().value(),
                                    "Click to inspect evidence.")));
        }
        decorate(inventory, pageNumber, page.hasMore(), trackingMetricsLore());
        player.openInventory(inventory);
    }

    private void showEvidence(
            UUID playerId,
            LoreInstanceId instanceId,
            int pageNumber,
            EvidenceData data) {
        Player player = authorizedPlayer(playerId);
        if (player == null || data == null) {
            return;
        }
        DuplicateChoice duplicate = data.anomalies.items().stream()
                .filter(anomaly -> anomaly.type() == InstanceAnomaly.Type.DUPLICATE_INSTANCE)
                .filter(anomaly -> anomaly.status() == InstanceAnomaly.Status.OPEN
                        || anomaly.status() == InstanceAnomaly.Status.ACKNOWLEDGED)
                .findFirst()
                .map(anomaly -> new DuplicateChoice(
                        anomaly.anomalyId(),
                        anomaly.stateRevision(),
                        anomaly.firstSeenAtEpochMillis()))
                .orElse(null);
        List<ObservationChoice> choices = data.observations.items().stream()
                .map(observation -> new ObservationChoice(
                        observation.observationId(),
                        observation.location(),
                        observation.confidence(),
                        observation.source(),
                        observation.observedAtEpochMillis()))
                .toList();
        View view = View.evidence(
                instanceId,
                pageNumber,
                data.observations.hasMore(),
                choices,
                duplicate);
        Inventory inventory = createInventory(view, "Lore location evidence");
        for (int index = 0; index < choices.size() && index < CONTENT; index++) {
            ObservationChoice choice = choices.get(index);
            List<String> lore = new ArrayList<>();
            lore.add(describe(choice.location));
            lore.add("Confidence: " + choice.confidence.name());
            lore.add("Source: " + choice.source);
            if (selectable(choice, duplicate)) {
                lore.add("Click to choose, then confirm.");
            }
            Material material = choice.confidence == InstanceObservation.Confidence.CONFLICTING
                    ? Material.REDSTONE
                    : Material.COMPASS;
            inventory.setItem(
                    index,
                    item(material, "Observation " + choice.observationId, lore));
        }
        List<String> status = new ArrayList<>();
        status.add(data.current
                .map(current -> current.state().name() + " — "
                        + (current.location() == null
                                ? "no location"
                                : describe(current.location())))
                .orElse("No current-state row"));
        status.add(duplicate == null
                ? "No active duplicate resolution is available."
                : "Only evidence from this active conflict is selectable.");
        status.addAll(trackingMetricsLore());
        decorate(inventory, pageNumber, data.observations.hasMore(), status);
        player.openInventory(inventory);
    }

    private Inventory createInventory(View view, String title) {
        Inventory inventory = Bukkit.createInventory(view, SIZE, Component.text(title));
        view.attach(inventory);
        return inventory;
    }

    private void decorate(
            Inventory inventory,
            int pageNumber,
            boolean hasMore,
            List<String> statusLore) {
        if (pageNumber > 1) {
            inventory.setItem(
                    PREVIOUS,
                    item(
                            Material.ARROW,
                            "Previous page",
                            List.of("Page " + (pageNumber - 1))));
        }
        if (hasMore) {
            inventory.setItem(
                    NEXT,
                    item(
                            Material.ARROW,
                            "Next page",
                            List.of("Page " + (pageNumber + 1))));
        }
        inventory.setItem(STATUS, item(Material.CLOCK, "Tracking status", statusLore));
    }

    private List<String> trackingMetricsLore() {
        AnomalyWarningSink sink = plugin.getServer().getServicesManager()
                .load(AnomalyWarningSink.class);
        if (!(sink instanceof TrackingMetricsSource source)) {
            return List.of("Tracking metrics unavailable.");
        }
        TrackingMetrics.Snapshot snapshot = source.trackingMetrics();
        return List.of(
                "Persistence queued: " + snapshot.queued(),
                "Persistence in flight: " + snapshot.inFlight(),
                "Scan backlog: " + snapshot.scanBacklog(),
                "Truncated bounded scans: " + snapshot.scanTruncated(),
                "Accepted/completed: " + snapshot.accepted() + '/' + snapshot.completed(),
                "Rejected/failed/conflicts: " + snapshot.rejected() + '/'
                        + snapshot.failed() + '/' + snapshot.conflicts());
    }

    private LoreItemsAdministrationUseCase resolveUseCase() {
        return plugin.getServer().getServicesManager()
                .load(LoreItemsAdministrationUseCase.class);
    }

    private <T> void submit(
            UUID playerId,
            String operation,
            Supplier<CompletionStage<T>> query,
            Consumer<T> success) {
        if (!queryCapacity.tryAcquire()) {
            messagePlayer(playerId, "Too many lore-item administration queries are active.");
            return;
        }
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(query.get(), operation + " returned null");
        } catch (RuntimeException exception) {
            queryCapacity.release();
            handleFailure(playerId, operation, exception);
            return;
        }
        stage.whenComplete((value, failure) -> {
            queryCapacity.release();
            if (failure != null) {
                handleFailure(playerId, operation, failure);
            } else if (value == null) {
                handleFailure(
                        playerId,
                        operation,
                        new IllegalStateException(operation + " returned no result"));
            } else {
                scheduleNextTick(() -> success.accept(value));
            }
        });
    }

    private void handleFailure(UUID playerId, String operation, Throwable failure) {
        plugin.getLogger().log(
                Level.SEVERE,
                "Could not complete lore-item " + operation + '.',
                unwrap(failure));
        messagePlayer(playerId, "The lore-item " + operation + " failed.");
    }

    private void messagePlayer(UUID playerId, String message) {
        runMain(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        });
    }

    private void runMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        scheduleNextTick(action);
    }

    private void scheduleNextTick(Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule lore-item GUI work during shutdown.",
                    exception);
        }
    }

    private Player authorizedPlayer(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        return player != null
                        && player.hasPermission(
                                LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)
                ? player
                : null;
    }

    private PageRequest pageRequest(int pageNumber) {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        int pageSize = currentPageSize();
        return new PageRequest(Math.multiplyExact(pageNumber - 1, pageSize), pageSize);
    }

    private int currentPageSize() {
        int pageSize = Math.min(CONTENT, pageSizeSupplier.getAsInt());
        if (pageSize < 1) {
            throw new IllegalStateException("Configured GUI page size must be positive");
        }
        return pageSize;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(lore.stream().map(Component::text).toList());
        if (!item.setItemMeta(meta)) {
            throw new IllegalStateException("Could not apply lore-item administration metadata");
        }
        return item;
    }

    private static boolean selectable(
            ObservationChoice observation, DuplicateChoice duplicate) {
        return duplicate != null
                && observation.observedAt >= duplicate.firstSeenAt
                && observation.confidence == InstanceObservation.Confidence.CONFLICTING
                && switch (observation.location.type()) {
                    case PLAYER_INVENTORY,
                            PLAYER_ENDER_CHEST,
                            BLOCK_CONTAINER,
                            DROPPED_ITEM,
                            ITEM_FRAME,
                            ARMOR_STAND,
                            NESTED_CONTAINER -> true;
                    default -> false;
                };
    }

    private static String describe(LocationDescriptor location) {
        return location.type().name() + ':' + location.locationKey()
                + (location.containerPath() == null ? "" : ':' + location.containerPath());
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    private enum Screen {
        DEFINITIONS,
        INSTANCES,
        EVIDENCE,
        CONFIRMATION
    }

    private static final class View implements InventoryHolder {
        private final Screen screen;
        private final int pageNumber;
        private final boolean hasMore;
        private final LoreDefinitionId definitionId;
        private final LoreInstanceId instanceId;
        private final List<LoreDefinitionId> definitionIds;
        private final List<LoreInstanceId> instanceIds;
        private final List<ObservationChoice> observations;
        private final DuplicateChoice duplicate;
        private final ObservationChoice selectedObservation;
        private Inventory inventory;

        private View(
                Screen screen,
                int pageNumber,
                boolean hasMore,
                LoreDefinitionId definitionId,
                LoreInstanceId instanceId,
                List<LoreDefinitionId> definitionIds,
                List<LoreInstanceId> instanceIds,
                List<ObservationChoice> observations,
                DuplicateChoice duplicate,
                ObservationChoice selectedObservation) {
            this.screen = Objects.requireNonNull(screen, "screen");
            this.pageNumber = pageNumber;
            this.hasMore = hasMore;
            this.definitionId = definitionId;
            this.instanceId = instanceId;
            this.definitionIds = List.copyOf(definitionIds);
            this.instanceIds = List.copyOf(instanceIds);
            this.observations = List.copyOf(observations);
            this.duplicate = duplicate;
            this.selectedObservation = selectedObservation;
        }

        private static View definitions(
                int pageNumber,
                boolean hasMore,
                List<LoreDefinitionId> definitionIds) {
            return new View(
                    Screen.DEFINITIONS,
                    pageNumber,
                    hasMore,
                    null,
                    null,
                    definitionIds,
                    List.of(),
                    List.of(),
                    null,
                    null);
        }

        private static View instances(
                LoreDefinitionId definitionId,
                int pageNumber,
                boolean hasMore,
                List<LoreInstanceId> instanceIds) {
            return new View(
                    Screen.INSTANCES,
                    pageNumber,
                    hasMore,
                    Objects.requireNonNull(definitionId, "definitionId"),
                    null,
                    List.of(),
                    instanceIds,
                    List.of(),
                    null,
                    null);
        }

        private static View evidence(
                LoreInstanceId instanceId,
                int pageNumber,
                boolean hasMore,
                List<ObservationChoice> observations,
                DuplicateChoice duplicate) {
            return new View(
                    Screen.EVIDENCE,
                    pageNumber,
                    hasMore,
                    null,
                    Objects.requireNonNull(instanceId, "instanceId"),
                    List.of(),
                    List.of(),
                    observations,
                    duplicate,
                    null);
        }

        private static View confirmation(
                LoreInstanceId instanceId,
                DuplicateChoice duplicate,
                ObservationChoice selectedObservation,
                int returnPage) {
            return new View(
                    Screen.CONFIRMATION,
                    returnPage,
                    false,
                    null,
                    Objects.requireNonNull(instanceId, "instanceId"),
                    List.of(),
                    List.of(),
                    List.of(),
                    Objects.requireNonNull(duplicate, "duplicate"),
                    Objects.requireNonNull(selectedObservation, "selectedObservation"));
        }

        private void attach(Inventory inventory) {
            if (this.inventory != null) {
                throw new IllegalStateException("View inventory is already attached");
            }
            this.inventory = Objects.requireNonNull(inventory, "inventory");
        }

        @Override
        public Inventory getInventory() {
            return Objects.requireNonNull(inventory, "View inventory is not attached");
        }
    }

    private record ObservationChoice(
            long observationId,
            LocationDescriptor location,
            InstanceObservation.Confidence confidence,
            String source,
            long observedAt) {}

    private record DuplicateChoice(
            UUID anomalyId, long stateRevision, long firstSeenAt) {}

    private record StateEvidence(
            Optional<InstanceCurrentState> current,
            Page<InstanceObservation> observations) {}

    private record EvidenceData(
            Optional<InstanceCurrentState> current,
            Page<InstanceObservation> observations,
            Page<InstanceAnomaly> anomalies) {}
}
