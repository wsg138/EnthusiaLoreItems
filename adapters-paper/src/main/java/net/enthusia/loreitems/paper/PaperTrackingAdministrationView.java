package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Immutable navigation state attached to one administration inventory. */
final class PaperTrackingAdministrationView implements InventoryHolder {
    final Screen screen;
    final int pageNumber;
    final boolean hasMore;
    final LoreDefinitionId definitionId;
    final LoreInstanceId instanceId;
    final List<LoreDefinitionId> definitionIds;
    final List<LoreInstanceId> instanceIds;
    final List<ObservationChoice> observations;
    final DuplicateChoice duplicate;
    final ObservationChoice selectedObservation;

    private Inventory inventory;

    private PaperTrackingAdministrationView(ViewState state) {
        screen = Objects.requireNonNull(state.screen(), "screen");
        pageNumber = state.pageNumber();
        hasMore = state.hasMore();
        definitionId = state.definitionId();
        instanceId = state.instanceId();
        definitionIds = List.copyOf(state.definitionIds());
        instanceIds = List.copyOf(state.instanceIds());
        observations = List.copyOf(state.observations());
        duplicate = state.duplicate();
        selectedObservation = state.selectedObservation();
    }

    static PaperTrackingAdministrationView definitions(
            int pageNumber,
            boolean hasMore,
            List<LoreDefinitionId> definitionIds) {
        return new PaperTrackingAdministrationView(new ViewState(
                Screen.DEFINITIONS,
                pageNumber,
                hasMore,
                null,
                null,
                definitionIds,
                List.of(),
                List.of(),
                null,
                null));
    }

    static PaperTrackingAdministrationView instances(
            LoreDefinitionId definitionId,
            int pageNumber,
            boolean hasMore,
            List<LoreInstanceId> instanceIds) {
        return new PaperTrackingAdministrationView(new ViewState(
                Screen.INSTANCES,
                pageNumber,
                hasMore,
                Objects.requireNonNull(definitionId, "definitionId"),
                null,
                List.of(),
                instanceIds,
                List.of(),
                null,
                null));
    }

    static PaperTrackingAdministrationView evidence(
            LoreInstanceId instanceId,
            int pageNumber,
            boolean hasMore,
            List<ObservationChoice> observations,
            DuplicateChoice duplicate) {
        return new PaperTrackingAdministrationView(new ViewState(
                Screen.EVIDENCE,
                pageNumber,
                hasMore,
                null,
                Objects.requireNonNull(instanceId, "instanceId"),
                List.of(),
                List.of(),
                observations,
                duplicate,
                null));
    }

    static PaperTrackingAdministrationView confirmation(
            LoreInstanceId instanceId,
            DuplicateChoice duplicate,
            ObservationChoice selectedObservation,
            int returnPage) {
        return new PaperTrackingAdministrationView(new ViewState(
                Screen.CONFIRMATION,
                returnPage,
                false,
                null,
                Objects.requireNonNull(instanceId, "instanceId"),
                List.of(),
                List.of(),
                List.of(),
                Objects.requireNonNull(duplicate, "duplicate"),
                Objects.requireNonNull(selectedObservation, "selectedObservation")));
    }

    void attach(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("View inventory is already attached");
        }
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public Inventory getInventory() {
        return Objects.requireNonNull(inventory, "View inventory is not attached");
    }

    enum Screen {
        DEFINITIONS,
        INSTANCES,
        EVIDENCE,
        CONFIRMATION
    }

    private record ViewState(
            Screen screen,
            int pageNumber,
            boolean hasMore,
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId,
            List<LoreDefinitionId> definitionIds,
            List<LoreInstanceId> instanceIds,
            List<ObservationChoice> observations,
            DuplicateChoice duplicate,
            ObservationChoice selectedObservation) {}
}

record ObservationChoice(
        long observationId,
        LocationDescriptor location,
        InstanceObservation.Confidence confidence,
        String source,
        long observedAt) {}

record DuplicateChoice(UUID anomalyId, long stateRevision, long firstSeenAt) {}

record StateEvidence(
        Optional<InstanceCurrentState> current,
        Page<InstanceObservation> observations) {}

record EvidenceData(
        Optional<InstanceCurrentState> current,
        Page<InstanceObservation> observations,
        Page<InstanceAnomaly> anomalies) {}
