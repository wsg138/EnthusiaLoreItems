package net.enthusia.loreitems.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.MutationRecord;

public final class PersistingLoreItemsAdministrationUseCase
        implements LoreItemsAdministrationUseCase {
    private final AnomalyRepository anomalies;
    private final AuditRepository audit;
    private final ObservationRepository observations;
    private final InstanceCurrentStateRepository currentStates;
    private final DirectDeliveryRepository deliveries;
    private final MutationRepository mutations;
    private final Optional<TrackingAdministrationStore> trackingAdministration;
    private final Clock clock;

    public PersistingLoreItemsAdministrationUseCase(
            AnomalyRepository anomalies,
            AuditRepository audit,
            ObservationRepository observations,
            InstanceCurrentStateRepository currentStates,
            DirectDeliveryRepository deliveries,
            MutationRepository mutations,
            Clock clock) {
        this(
                anomalies,
                audit,
                observations,
                currentStates,
                deliveries,
                mutations,
                findTrackingStore(anomalies, observations, currentStates),
                clock);
    }

    public PersistingLoreItemsAdministrationUseCase(
            AnomalyRepository anomalies,
            AuditRepository audit,
            ObservationRepository observations,
            InstanceCurrentStateRepository currentStates,
            DirectDeliveryRepository deliveries,
            MutationRepository mutations,
            TrackingAdministrationStore trackingStore,
            Clock clock) {
        this(
                anomalies,
                audit,
                observations,
                currentStates,
                deliveries,
                mutations,
                Optional.of(Objects.requireNonNull(trackingStore, "trackingStore")),
                clock);
    }

    private PersistingLoreItemsAdministrationUseCase(
            AnomalyRepository anomalies,
            AuditRepository audit,
            ObservationRepository observations,
            InstanceCurrentStateRepository currentStates,
            DirectDeliveryRepository deliveries,
            MutationRepository mutations,
            Optional<TrackingAdministrationStore> trackingAdministration,
            Clock clock) {
        this.anomalies = Objects.requireNonNull(anomalies, "anomalies");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.currentStates = Objects.requireNonNull(currentStates, "currentStates");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.trackingAdministration = Objects.requireNonNull(
                trackingAdministration, "trackingAdministration");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listActiveAnomalies(PageRequest request) {
        return Objects.requireNonNull(anomalies.listActive(request), "active anomaly stage");
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listWarningAnomalies(
            PageRequest request) {
        return Objects.requireNonNull(
                anomalies.listActiveWarnings(request), "warning anomaly stage");
    }

    @Override
    public CompletionStage<Optional<InstanceCurrentState>> findCurrentState(
            LoreInstanceId instanceId) {
        return Objects.requireNonNull(
                currentStates.findByInstance(instanceId), "current state stage");
    }

    @Override
    public CompletionStage<Page<InstanceObservation>> listInstanceObservations(
            LoreInstanceId instanceId, PageRequest request) {
        return Objects.requireNonNull(
                observations.listByInstance(instanceId, request), "observation stage");
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listInstanceAnomalies(
            LoreInstanceId instanceId, PageRequest request) {
        return Objects.requireNonNull(
                anomalies.listByInstance(instanceId, request), "instance anomaly stage");
    }

    @Override
    public CompletionStage<Page<AuditEventRecord>> listInstanceAudit(
            LoreInstanceId instanceId, PageRequest request) {
        return Objects.requireNonNull(
                audit.listByAggregate("lore_instance", instanceId.value().toString(), request),
                "audit stage");
    }

    @Override
    public CompletionStage<Page<DirectDeliveryRecord>> listRecoverableDeliveries(
            PageRequest request) {
        return Objects.requireNonNull(
                deliveries.listByStates(
                        List.of(
                                DirectDeliveryState.PENDING,
                                DirectDeliveryState.CLAIMED,
                                DirectDeliveryState.APPLIED,
                                DirectDeliveryState.REVIEW_REQUIRED),
                        request),
                "delivery recovery stage");
    }

    @Override
    public CompletionStage<Page<MutationRecord>> listRecoverableMutations(
            PageRequest request) {
        return Objects.requireNonNull(
                mutations.listRecoverable(request), "mutation recovery stage");
    }

    @Override
    public CompletionStage<Boolean> acknowledgeAnomaly(
            UUID anomalyId, long expectedStateRevision, String actorId) {
        return Objects.requireNonNull(
                anomalies.acknowledge(
                        anomalyId,
                        expectedStateRevision,
                        actorId,
                        clock.millis()),
                "anomaly acknowledgement stage");
    }

    @Override
    public CompletionStage<Page<LoreDefinition>> listDefinitions(PageRequest request) {
        return trackingStore().listDefinitions(request);
    }

    @Override
    public CompletionStage<Page<LoreInstance>> listInstances(
            LoreDefinitionId definitionId, PageRequest request) {
        return trackingStore().listInstances(definitionId, request);
    }

    @Override
    public CompletionStage<DuplicateResolutionResult> resolveDuplicate(
            DuplicateResolutionRequest request) {
        return trackingStore().resolveDuplicate(request, clock.instant());
    }

    private TrackingAdministrationStore trackingStore() {
        return trackingAdministration.orElseThrow(() -> new IllegalStateException(
                "Tracking administration is unavailable from configured repositories"));
    }

    private static Optional<TrackingAdministrationStore> findTrackingStore(
            AnomalyRepository anomalies,
            ObservationRepository observations,
            InstanceCurrentStateRepository currentStates) {
        if (anomalies instanceof TrackingAdministrationStore tracking) {
            return Optional.of(tracking);
        }
        if (observations instanceof TrackingAdministrationStore tracking) {
            return Optional.of(tracking);
        }
        if (currentStates instanceof TrackingAdministrationStore tracking) {
            return Optional.of(tracking);
        }
        return Optional.empty();
    }
}
