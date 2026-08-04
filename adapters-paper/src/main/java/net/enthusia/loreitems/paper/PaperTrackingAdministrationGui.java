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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/** Paginated staff browser and explicit duplicate-location confirmation flow. */
public final class PaperTrackingAdministrationGui implements Listener {
    private static final int INVENTORY_SIZE = 54;
    private static final int CONTENT_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int STATUS_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int CONFIRM_SLOT = 22;
    private static final int CANCEL_SLOT = 31;
    private static final int MAX_CONCURRENT_QUERIES = 32;

    private final Plugin plugin;
    private final IntSupplier pageSizeSupplier;
    private final Semaphore queryCapacity = new Semaphore(MAX_CONCURRENT_QUERIES);

    public PaperTrackingAdministrationGui(Plugin plugin, IntSupplier pageSizeSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.pageSizeSupplier = Objects.requireNonNull(pageSizeSupplier, "pageSizeSupplier");
        currentPageSize();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void openDefinitions(UUID playerId, int pageNumber) {
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            notifyPlayer(playerId, "Lore-item administration is unavailable while storage initializes.");
            return;
        }
        PageRequest request = pageRequest(pageNumber);
        submit(
                playerId,
                "definition browser",
                () -> useCase.listDefinitions(request),
                page -> openDefinitionView(playerId, pageNumber, page));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof TrackingView view)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.hasPermission(LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            return;
        }
        switch (view) {
            case DefinitionView definitions -> handleDefinitionClick(player, definitions, slot);
            case InstanceView instances -> handleInstanceClick(player, instances, slot);
            case EvidenceView evidence -> handleEvidenceClick(player, evidence, slot);
            case ConfirmationView confirmation ->
                    handleConfirmationClick(player, confirmation, slot);
        }
    }

    private void handleDefinitionClick(Player player, DefinitionView view, int slot) {
        if (slot == PREVIOUS_SLOT && view.pageNumber() > 1) {
            openDefinitions(player.getUniqueId(), view.pageNumber() - 1);
        } else if (slot == NEXT_SLOT && view.hasMore()) {
            openDefinitions(player.getUniqueId(), view.pageNumber() + 1);
        } else if (slot < view.definitionIds().size()) {
            openInstances(player.getUniqueId(), view.definitionIds().get(slot), 1);
        }
    }

    private void openInstances(UUID playerId, LoreDefinitionId definitionId, int pageNumber) {
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            notifyPlayer(playerId, "Lore-item administration is unavailable.");
            return;
        }
        submit(
                playerId,
                "instance browser",
                () -> useCase.listInstances(definitionId, pageRequest(pageNumber)),
                page -> openInstanceView(playerId, definitionId, pageNumber, page));
    }

    private void handleInstanceClick(Player player, InstanceView view, int slot) {
        if (slot == PREVIOUS_SLOT) {
            if (view.pageNumber() > 1) {
                openInstances(player.getUniqueId(), view.definitionId(), view.pageNumber() - 1);
            } else {
                openDefinitions(player.getUniqueId(), 1);
            }
        } else if (slot == NEXT_SLOT && view.hasMore()) {
            openInstances(player.getUniqueId(), view.definitionId(), view.pageNumber() + 1);
        } else if (slot < view.instanceIds().size()) {
            openEvidence(player.getUniqueId(), view.instanceIds().get(slot), 1);
        }
    }

    private void openEvidence(UUID playerId, LoreInstanceId instanceId, int pageNumber) {
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            notifyPlayer(playerId, "Lore-item administration is unavailable.");
            return;
        }
        PageRequest request = pageRequest(pageNumber);
        CompletionStage<Optional<InstanceCurrentState>> current;
        CompletionStage<Page<InstanceObservation>> observations;
        CompletionStage<Page<InstanceAnomaly>> anomalies;
        try {
            current = Objects.requireNonNull(
                    useCase.findCurrentState(instanceId), "current-state stage");
            observations = Objects.requireNonNull(
                    useCase.listInstanceObservations(instanceId, request), "observation stage");
            anomalies = Objects.requireNonNull(
                    useCase.listInstanceAnomalies(instanceId, PageRequest.first(CONTENT_SLOTS)),
                    "anomaly stage");
        } catch (RuntimeException exception) {
            handleFailure(playerId, "instance evidence", exception);
            return;
        }
        if (!queryCapacity.tryAcquire()) {
            notifyPlayer(playerId, "Too many lore-item administration queries are active.");
            return;
        }
        current.thenCombine(observations, StateEvidence::new)
                .thenCombine(anomalies, (state, anomalyPage) ->
                        new EvidenceData(state.current(), state.observations(), anomalyPage))
                .whenComplete((data, failure) -> {
                    queryCapacity.release();
                    if (failure != null) {
                        handleFailure(playerId, "instance evidence", failure);
                    } else {
                        schedule(() -> openEvidenceView(playerId, instanceId, pageNumber, data));
                    }
                });
    }

    private void handleEvidenceClick(Player player, EvidenceView view, int slot) {
        if (slot == PREVIOUS_SLOT) {
            if (view.pageNumber() > 1) {
                openEvidence(player.getUniqueId(), view.instanceId(), view.pageNumber() - 1);
            } else {
                openDefinitions(player.getUniqueId(), 1);
            }
            return;
        }
        if (slot == NEXT_SLOT && view.hasMore()) {
            openEvidence(player.getUniqueId(), view.instanceId(), view.pageNumber() + 1);
            return;
        }
        if (slot >= view.observations().size() || view.duplicateAnomaly() == null) {
            return;
        }
        ObservationChoice choice = view.observations().get(slot);
        if (choice.confidence() != InstanceObservation.Confidence.CONFLICTING
                || !selectable(choice.location().type())) {
            return;
        }
        openConfirmation(
                player,
                view.instanceId(),
                view.duplicateAnomaly(),
                choice,
                view.pageNumber());
    }

    private void openConfirmation(
            Player player,
            LoreInstanceId instanceId,
            DuplicateChoice anomaly,
            ObservationChoice observation,
            int returnPage) {
        ConfirmationView holder = new ConfirmationView(
                instanceId, anomaly, observation, returnPage);
        Inventory inventory = Bukkit.createInventory(
                holder, INVENTORY_SIZE, Component.text("Confirm lore location"));
        inventory.setItem(CONFIRM_SLOT, item(
                Material.LIME_CONCRETE,
                "Confirm selected location",
                List.of(
                        describe(observation.location()),
                        "This does not delete any physical copy.",
                        "A later scan can reopen the conflict.")));
        inventory.setItem(CANCEL_SLOT, item(
                Material.BARRIER,
                "Cancel",
                List.of("Return without changing durable state.")));
        player.openInventory(inventory);
    }

    private void handleConfirmationClick(
            Player player, ConfirmationView view, int slot) {
        if (slot == CANCEL_SLOT) {
            openEvidence(player.getUniqueId(), view.instanceId(), view.returnPage());
            return;
        }
        if (slot != CONFIRM_SLOT) {
            return;
        }
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            player.sendMessage("Lore-item administration is unavailable.");
            return;
        }
        player.closeInventory();
        LoreItemsAdministrationUseCase.DuplicateResolutionRequest request =
                new LoreItemsAdministrationUseCase.DuplicateResolutionRequest(
                        view.anomaly().anomalyId(),
                        view.anomaly().stateRevision(),
                        view.observation().observationId(),
                        "player:" + player.getUniqueId());
        submit(
                player.getUniqueId(),
                "duplicate resolution",
                () -> useCase.resolveDuplicate(request),
                result -> {
                    notifyPlayer(player.getUniqueId(), result.detail());
                    openEvidence(player.getUniqueId(), view.instanceId(), view.returnPage());
                });
    }

    private void openDefinitionView(
            UUID playerId, int pageNumber, Page<LoreDefinition> page) {
        schedule(() -> {
            Player player = authorizedPlayer(playerId);
            if (player == null) {
                return;
            }
            List<LoreDefinitionId> ids = page.items().stream().map(LoreDefinition::id).toList();
            DefinitionView holder = new DefinitionView(pageNumber, page.hasMore(), ids);
            Inventory inventory = Bukkit.createInventory(
                    holder, INVENTORY_SIZE, Component.text("Lore definitions"));
            for (int index = 0; index < page.items().size() && index < CONTENT_SLOTS; index++) {
                LoreDefinition definition = page.items().get(index);
                inventory.setItem(index, item(
                        Material.BOOK,
                        definition.displayName(),
                        List.of(
                                "Key: " + definition.key().value(),
                                "Revision: " + definition.currentRevision().value(),
                                "Click to browse instances.")));
            }
            decorateNavigation(inventory, pageNumber, page.hasMore(), trackingMetricsLore());
            player.openInventory(inventory);
        });
    }

    private void openInstanceView(
            UUID playerId,
            LoreDefinitionId definitionId,
            int pageNumber,
            Page<LoreInstance> page) {
        schedule(() -> {
            Player player = authorizedPlayer(playerId);
            if (player == null) {
                return;
            }
            List<LoreInstanceId> ids = page.items().stream().map(LoreInstance::id).toList();
            InstanceView holder = new InstanceView(
                    definitionId, pageNumber, page.hasMore(), ids);
            Inventory inventory = Bukkit.createInventory(
                    holder, INVENTORY_SIZE, Component.text("Lore instances"));
            for (int index = 0; index < page.items().size() && index < CONTENT_SLOTS; index++) {
                LoreInstance instance = page.items().get(index);
                inventory.setItem(index, item(
                        Material.NETHER_STAR,
                        shortId(instance.id().value()),
                        List.of(
                                "Lifecycle: " + instance.lifecycle().name(),
                                "Applied revision: " + instance.appliedRevision().value(),
                                "Desired revision: " + instance.desiredRevision().value(),
                                "Click to inspect location evidence.")));
            }
            decorateNavigation(inventory, pageNumber, page.hasMore(), trackingMetricsLore());
            player.openInventory(inventory);
        });
    }

    private void openEvidenceView(
            UUID playerId,
            LoreInstanceId instanceId,
            int pageNumber,
            EvidenceData data) {
        Player player = authorizedPlayer(playerId);
        if (player == null) {
            return;
        }
        DuplicateChoice duplicate = data.anomalies().items().stream()
                .filter(anomaly -> anomaly.type() == InstanceAnomaly.Type.DUPLICATE_INSTANCE)
                .filter(anomaly -> anomaly.status() == InstanceAnomaly.Status.OPEN
                        || anomaly.status() == InstanceAnomaly.Status.ACKNOWLEDGED)
                .findFirst()
                .map(anomaly -> new DuplicateChoice(
                        anomaly.anomalyId(), anomaly.stateRevision()))
                .orElse(null);
        List<ObservationChoice> choices = data.observations().items().stream()
                .map(observation -> new ObservationChoice(
                        observation.observationId(),
                        observation.location(),
                        observation.confidence(),
                        observation.source(),
                        observation.observedAtEpochMillis()))
                .toList();
        EvidenceView holder = new EvidenceView(
                instanceId, pageNumber, data.observations().hasMore(), choices, duplicate);
        Inventory inventory = Bukkit.createInventory(
                holder, INVENTORY_SIZE, Component.text("Lore location evidence"));
        for (int index = 0; index < choices.size() && index < CONTENT_SLOTS; index++) {
            ObservationChoice choice = choices.get(index);
            Material material = choice.confidence() == InstanceObservation.Confidence.CONFLICTING
                    ? Material.REDSTONE
                    : Material.COMPASS;
            List<String> lore = new ArrayList<>();
            lore.add(describe(choice.location()));
            lore.add("Confidence: " + choice.confidence().name());
            lore.add("Source: " + choice.source());
            if (duplicate != null
                    && choice.confidence() == InstanceObservation.Confidence.CONFLICTING
                    && selectable(choice.location().type())) {
                lore.add("Click to choose, then confirm.");
            }
            inventory.setItem(index, item(
                    material,
                    "Observation " + choice.observationId(),
                    lore));
        }
        List<String> status = new ArrayList<>();
        status.add(data.current().map(current ->
                        current.state().name() + " — "
                                + (current.location() == null
                                        ? "no location"
                                        : describe(current.location())))
                .orElse("No current-state row"));
        status.add(duplicate == null
                ? "No active duplicate resolution is available."
                : "Conflicting locations can be selected explicitly.");
        status.addAll(trackingMetricsLore());
        decorateNavigation(inventory, pageNumber, data.observations().hasMore(), status);
        player.openInventory(inventory);
    }

    private void decorateNavigation(
            Inventory inventory,
            int pageNumber,
            boolean hasMore,
            List<String> statusLore) {
        if (pageNumber > 1) {
            inventory.setItem(PREVIOUS_SLOT, item(
                    Material.ARROW, "Previous page", List.of("Page " + (pageNumber - 1))));
        }
        if (hasMore) {
            inventory.setItem(NEXT_SLOT, item(
                    Material.ARROW, "Next page", List.of("Page " + (pageNumber + 1))));
        }
        inventory.setItem(STATUS_SLOT, item(
                Material.CLOCK, "Tracking status", statusLore));
    }

    private List<String> trackingMetricsLore() {
        AnomalyWarningSink sink = plugin.getServer()
                .getServicesManager()
                .load(AnomalyWarningSink.class);
        if (!(sink instanceof TrackingMetricsSource source)) {
            return List.of("Tracking metrics unavailable.");
        }
        TrackingMetrics.Snapshot metrics = source.trackingMetrics();
        return List.of(
                "Persistence queued: " + metrics.queued(),
                "Persistence in flight: " + metrics.inFlight(),
                "Scan backlog: " + metrics.scanBacklog(),
                "Accepted/completed: " + metrics.accepted() + '/' + metrics.completed(),
                "Rejected/failed/conflicts: " + metrics.rejected() + '/'
                        + metrics.failed() + '/' + metrics.conflicts());
    }

    private LoreItemsAdministrationUseCase resolveUseCase() {
        return plugin.getServer()
                .getServicesManager()
                .load(LoreItemsAdministrationUseCase.class);
    }

    private <T> void submit(
            UUID playerId,
            String operation,
            Supplier<CompletionStage<T>> query,
            Consumer<T> success) {
        if (!queryCapacity.tryAcquire()) {
            notifyPlayer(playerId, "Too many lore-item administration queries are active.");
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
                handleFailure(playerId, operation,
                        new IllegalStateException(operation + " returned no result"));
            } else {
                success.accept(value);
            }
        });
    }

    private void handleFailure(UUID playerId, String operation, Throwable failure) {
        plugin.getLogger().log(
                Level.SEVERE,
                "Could not complete lore-item " + operation + '.',
                unwrap(failure));
        notifyPlayer(playerId, "The lore-item " + operation + " failed.");
    }

    private void notifyPlayer(UUID playerId, String message) {
        schedule(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        });
    }

    private void schedule(Runnable action) {
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
        int pageSize = Math.min(CONTENT_SLOTS, pageSizeSupplier.getAsInt());
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
        item.setItemMeta(meta);
        return item;
    }

    private static boolean selectable(LocationDescriptor.Type type) {
        return switch (type) {
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

    private sealed interface TrackingView extends InventoryHolder
            permits DefinitionView, InstanceView, EvidenceView, ConfirmationView {
        @Override
        default Inventory getInventory() {
            throw new UnsupportedOperationException("Tracking view holders are metadata only");
        }
    }

    private record DefinitionView(
            int pageNumber,
            boolean hasMore,
            List<LoreDefinitionId> definitionIds) implements TrackingView {
        private DefinitionView {
            definitionIds = List.copyOf(definitionIds);
        }
    }

    private record InstanceView(
            LoreDefinitionId definitionId,
            int pageNumber,
            boolean hasMore,
            List<LoreInstanceId> instanceIds) implements TrackingView {
        private InstanceView {
            Objects.requireNonNull(definitionId, "definitionId");
            instanceIds = List.copyOf(instanceIds);
        }
    }

    private record EvidenceView(
            LoreInstanceId instanceId,
            int pageNumber,
            boolean hasMore,
            List<ObservationChoice> observations,
            DuplicateChoice duplicateAnomaly) implements TrackingView {
        private EvidenceView {
            Objects.requireNonNull(instanceId, "instanceId");
            observations = List.copyOf(observations);
        }
    }

    private record ConfirmationView(
            LoreInstanceId instanceId,
            DuplicateChoice anomaly,
            ObservationChoice observation,
            int returnPage) implements TrackingView {}

    private record ObservationChoice(
            long observationId,
            LocationDescriptor location,
            InstanceObservation.Confidence confidence,
            String source,
            long observedAt) {}

    private record DuplicateChoice(UUID anomalyId, long stateRevision) {}

    private record StateEvidence(
            Optional<InstanceCurrentState> current,
            Page<InstanceObservation> observations) {}

    private record EvidenceData(
            Optional<InstanceCurrentState> current,
            Page<InstanceObservation> observations,
            Page<InstanceAnomaly> anomalies) {}
}
