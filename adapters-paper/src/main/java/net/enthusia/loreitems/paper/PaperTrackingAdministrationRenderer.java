package net.enthusia.loreitems.paper;

import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.decorate;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.decorateNested;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.describe;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.evidenceItem;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.item;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.shortId;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.trackingMetricsLore;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

/** Renders immutable tracking administration pages on the Paper thread. */
final class PaperTrackingAdministrationRenderer {
    private static final int SIZE = 54;
    private static final int CONTENT = 45;
    private static final int CONFIRMATION_SIZE = 27;
    private static final int CANCEL = 11;
    private static final int CONTEXT = 13;
    private static final int CONFIRM = 15;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Plugin plugin;

    PaperTrackingAdministrationRenderer(Plugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        new PaperDestructiveAdministrationGuiBridge(plugin);
    }

    void showDefinitions(Player player, int pageNumber, Page<LoreDefinition> page) {
        List<LoreDefinitionId> ids = page.items().stream().map(LoreDefinition::id).toList();
        PaperTrackingAdministrationView view =
                PaperTrackingAdministrationView.definitions(pageNumber, page.hasMore(), ids);
        Inventory inventory = createInventory(view, "Lore definitions");
        for (int index = 0; index < page.items().size() && index < CONTENT; index++) {
            LoreDefinition definition = page.items().get(index);
            inventory.setItem(
                    index,
                    item(
                            Material.BOOK,
                            definition.displayName(),
                            PaperGuiStyle.Tone.INFO,
                            List.of(
                                    "Key: " + definition.key().value(),
                                    "Revision: " + definition.currentRevision().value(),
                                    "Status: " + (definition.active() ? "Active" : "Deleted"),
                                    "",
                                    "Click to manage this template.")));
        }
        if (ids.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.PAPER,
                            "No definitions on this page",
                            PaperGuiStyle.Tone.INFO,
                            List.of("Use the page controls below to continue browsing.")));
        }
        decorate(inventory, pageNumber, page.hasMore(), trackingMetricsLore(plugin));
        player.openInventory(inventory);
    }

    void showInstances(
            Player player,
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int pageNumber,
            Page<LoreInstance> page) {
        List<LoreInstanceId> ids = page.items().stream().map(LoreInstance::id).toList();
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.instances(
                definitionId, definitionPageNumber, pageNumber, page.hasMore(), ids);
        Inventory inventory = createInventory(view, "Lore instances");
        boolean canRemove =
                player.hasPermission(LoreItemsDestructiveCommandExecutor.REMOVE_PERMISSION);
        for (int index = 0; index < page.items().size() && index < CONTENT; index++) {
            LoreInstance instance = page.items().get(index);
            inventory.setItem(
                    index,
                    item(
                            Material.NETHER_STAR,
                            "Instance " + shortId(instance.id().value()),
                            PaperGuiStyle.Tone.INFO,
                            instanceLore(instance, canRemove)));
        }
        if (ids.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.PAPER,
                            "No tracked instances",
                            PaperGuiStyle.Tone.INFO,
                            List.of("This definition has no instances on this page.")));
        }
        decorateNested(
                inventory,
                pageNumber,
                page.hasMore(),
                "Back to template",
                List.of("Return to template management."),
                trackingMetricsLore(plugin));
        player.openInventory(inventory);
    }

    void showEvidence(
            Player player,
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int instancePageNumber,
            LoreInstanceId instanceId,
            int pageNumber,
            EvidenceData data) {
        DuplicateChoice duplicate = activeDuplicate(data.anomalies());
        List<ObservationChoice> choices = observationChoices(data.observations());
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.evidence(
                definitionId,
                definitionPageNumber,
                instancePageNumber,
                instanceId,
                pageNumber,
                data.observations().hasMore(),
                choices,
                duplicate);
        Inventory inventory = createInventory(view, "Lore location evidence");
        populateEvidence(inventory, choices, duplicate);
        if (choices.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.MAP,
                            "No location observations",
                            PaperGuiStyle.Tone.INFO,
                            List.of("No location evidence is available on this page.")));
        }
        decorateNested(
                inventory,
                pageNumber,
                data.observations().hasMore(),
                "Back to instances",
                List.of("Return to instance page " + instancePageNumber + '.'),
                evidenceStatus(data.current(), duplicate));
        player.openInventory(inventory);
    }

    Inventory confirmationInventory(
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int instancePageNumber,
            LoreInstanceId instanceId,
            DuplicateChoice duplicate,
            ObservationChoice observation,
            int returnPage) {
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.confirmation(
                definitionId,
                definitionPageNumber,
                instancePageNumber,
                instanceId,
                duplicate,
                observation,
                returnPage);
        Inventory inventory = createInventory(view, "Confirm lore location", CONFIRMATION_SIZE);
        inventory.setItem(
                CANCEL,
                item(
                        Material.BARRIER,
                        "Cancel",
                        PaperGuiStyle.Tone.NAVIGATION,
                        List.of("Return to the evidence page without changing anything.")));
        inventory.setItem(
                CONTEXT,
                item(
                        Material.MAP,
                        "Selected location",
                        PaperGuiStyle.Tone.METADATA,
                        List.of(
                                describe(observation.location()),
                                "Confidence: " + observation.confidence().name(),
                                "Source: " + observation.source(),
                                "",
                                "Instance: " + shortId(instanceId.value()),
                                "No physical copy will be deleted.")));
        inventory.setItem(
                CONFIRM,
                item(
                        Material.LIME_CONCRETE,
                        "Confirm this location",
                        PaperGuiStyle.Tone.POSITIVE,
                        List.of(
                                "Use this observation to resolve the active conflict.",
                                "A later scan can reopen it if copies conflict again.")));
        return inventory;
    }

    private static List<String> instanceLore(LoreInstance instance, boolean canRemove) {
        List<String> lore = new ArrayList<>(List.of(
                "Lifecycle: " + instance.lifecycle().name(),
                "Applied revision: " + instance.appliedRevision().value(),
                "Desired revision: " + instance.desiredRevision().value(),
                "Created: " + formatTime(instance.createdAtEpochMillis())));
        if (instance.terminalAtEpochMillis() != null) {
            lore.add("Terminal: " + formatTime(instance.terminalAtEpochMillis()));
        }
        lore.add("");
        lore.add("Left-click to inspect location evidence.");
        if (canRemove) {
            lore.add("Right-click to preview physical removal.");
        }
        return lore;
    }

    private DuplicateChoice activeDuplicate(Page<InstanceAnomaly> anomalies) {
        return anomalies.items().stream()
                .filter(anomaly -> anomaly.type() == InstanceAnomaly.Type.DUPLICATE_INSTANCE)
                .filter(anomaly -> anomaly.status() == InstanceAnomaly.Status.OPEN
                        || anomaly.status() == InstanceAnomaly.Status.ACKNOWLEDGED)
                .findFirst()
                .map(anomaly -> new DuplicateChoice(
                        anomaly.anomalyId(),
                        anomaly.stateRevision(),
                        anomaly.firstSeenAtEpochMillis()))
                .orElse(null);
    }

    private List<ObservationChoice> observationChoices(Page<InstanceObservation> observations) {
        return observations.items().stream()
                .map(observation -> new ObservationChoice(
                        observation.observationId(),
                        observation.location(),
                        observation.confidence(),
                        observation.source(),
                        observation.observedAtEpochMillis()))
                .toList();
    }

    private void populateEvidence(
            Inventory inventory,
            List<ObservationChoice> choices,
            DuplicateChoice duplicate) {
        for (int index = 0; index < choices.size() && index < CONTENT; index++) {
            inventory.setItem(index, evidenceItem(choices.get(index), duplicate));
        }
    }

    private List<String> evidenceStatus(
            Optional<InstanceCurrentState> current, DuplicateChoice duplicate) {
        List<String> status = new ArrayList<>();
        status.add(current
                .map(state -> "Current: " + state.state().name() + " — "
                        + (state.location() == null
                                ? "No confirmed location"
                                : describe(state.location())))
                .orElse("Current: No current-state row"));
        status.add(duplicate == null
                ? "Conflict: No active duplicate resolution"
                : "Conflict: Active — selectable observations are marked");
        status.add("");
        status.addAll(trackingMetricsLore(plugin));
        return status;
    }

    private static String formatTime(long epochMillis) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static Inventory createInventory(
            PaperTrackingAdministrationView view, String title) {
        return createInventory(view, title, SIZE);
    }

    private static Inventory createInventory(
            PaperTrackingAdministrationView view, String title, int size) {
        Inventory inventory = Bukkit.createInventory(view, size, PaperGuiStyle.inventoryTitle(title));
        view.attach(inventory);
        return inventory;
    }
}
