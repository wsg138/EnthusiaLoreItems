package net.enthusia.loreitems.paper;

import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.decorate;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.describe;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.evidenceItem;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.item;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.shortId;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.trackingMetricsLore;

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
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

/** Renders immutable tracking administration pages on the Paper thread. */
final class PaperTrackingAdministrationRenderer {
    private static final int SIZE = 54;
    private static final int CONTENT = 45;
    private static final int CONFIRM = 22;
    private static final int CANCEL = 31;

    private final Plugin plugin;

    PaperTrackingAdministrationRenderer(Plugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    void showDefinitions(Player player, int pageNumber, Page<LoreDefinition> page) {
        List<LoreDefinitionId> ids = page.items().stream()
                .map(LoreDefinition::id)
                .toList();
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.definitions(
                pageNumber, page.hasMore(), ids);
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
        decorate(inventory, pageNumber, page.hasMore(), trackingMetricsLore(plugin));
        player.openInventory(inventory);
    }

    void showInstances(
            Player player,
            LoreDefinitionId definitionId,
            int pageNumber,
            Page<LoreInstance> page) {
        List<LoreInstanceId> ids = page.items().stream()
                .map(LoreInstance::id)
                .toList();
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.instances(
                definitionId, pageNumber, page.hasMore(), ids);
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
        decorate(inventory, pageNumber, page.hasMore(), trackingMetricsLore(plugin));
        player.openInventory(inventory);
    }

    void showEvidence(
            Player player,
            LoreInstanceId instanceId,
            int pageNumber,
            EvidenceData data) {
        DuplicateChoice duplicate = activeDuplicate(data.anomalies());
        List<ObservationChoice> choices = observationChoices(data.observations());
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.evidence(
                instanceId,
                pageNumber,
                data.observations().hasMore(),
                choices,
                duplicate);
        Inventory inventory = createInventory(view, "Lore location evidence");
        populateEvidence(inventory, choices, duplicate);
        decorate(
                inventory,
                pageNumber,
                data.observations().hasMore(),
                evidenceStatus(data.current(), duplicate));
        player.openInventory(inventory);
    }

    Inventory confirmationInventory(
            LoreInstanceId instanceId,
            DuplicateChoice duplicate,
            ObservationChoice observation,
            int returnPage) {
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.confirmation(
                instanceId, duplicate, observation, returnPage);
        Inventory inventory = createInventory(view, "Confirm lore location");
        inventory.setItem(
                CONFIRM,
                item(
                        Material.LIME_CONCRETE,
                        "Confirm selected location",
                        List.of(
                                describe(observation.location()),
                                "No physical copy will be deleted.",
                                "A later scan can reopen the conflict.")));
        inventory.setItem(
                CANCEL,
                item(
                        Material.BARRIER,
                        "Cancel",
                        List.of("Return without changing durable state.")));
        return inventory;
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

    private List<ObservationChoice> observationChoices(
            Page<InstanceObservation> observations) {
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
                .map(state -> state.state().name() + " — "
                        + (state.location() == null
                                ? "no location"
                                : describe(state.location())))
                .orElse("No current-state row"));
        status.add(duplicate == null
                ? "No active duplicate resolution is available."
                : "Only evidence from this active conflict is selectable.");
        status.addAll(trackingMetricsLore(plugin));
        return status;
    }

    private static Inventory createInventory(
            PaperTrackingAdministrationView view, String title) {
        Inventory inventory = Bukkit.createInventory(view, SIZE, Component.text(title));
        view.attach(inventory);
        return inventory;
    }
}
