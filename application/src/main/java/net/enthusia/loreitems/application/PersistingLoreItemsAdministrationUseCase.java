package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreInstanceId;

public final class PersistingLoreItemsAdministrationUseCase
        implements LoreItemsAdministrationUseCase {
    private static final String INSTANCE_AGGREGATE_TYPE = "lore_instance";
    private static final String REQUEST_ARGUMENT = "request";
    private static final String INSTANCE_ID_ARGUMENT = "instanceId";

    private final AnomalyRepository anomalyRepository;
    private final AuditRepository auditRepository;
    private final CurrentStateRepository currentStateRepository;
    private final ObservationRepository observationRepository;
    private final DirectDeliveryRepository deliveryRepository;
    private final PendingMutationRepository mutationRepository;

    public PersistingLoreItemsAdministrationUseCase(
            AnomalyRepository anomalyRepository,
            AuditRepository auditRepository,
            CurrentStateRepository currentStateRepository,
            ObservationRepository observationRepository,
            DirectDeliveryRepository deliveryRepository,
            PendingMutationRepository mutationRepository) {
        this.anomalyRepository = Objects.requireNonNull(
                anomalyRepository, "anomalyRepository");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
        this.currentStateRepository = Objects.requireNonNull(
                currentStateRepository, "currentStateRepository");
        this.observationRepository = Objects.requireNonNull(
                observationRepository, "observationRepository");
        this.deliveryRepository = Objects.requireNonNull(
                deliveryRepository, "deliveryRepository");
        this.mutationRepository = Objects.requireNonNull(
                mutationRepository, "mutationRepository");
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listActiveAnomalies(PageRequest request) {
        return anomalyRepository.listActive(
                Objects.requireNonNull(request, REQUEST_ARGUMENT));
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listWarningAnomalies(PageRequest request) {
        return anomalyRepository.listActiveWarnings(
                Objects.requireNonNull(request, REQUEST_ARGUMENT));
    }

    @Override
    public CompletionStage<Optional<InstanceCurrentState>> findCurrentState(
            LoreInstanceId instanceId) {
        return currentStateRepository.findByInstance(
                Objects.requireNonNull(instanceId, INSTANCE_ID_ARGUMENT));
    }

    @Override
    public CompletionStage<Page<InstanceObservation>> listInstanceObservations(
            LoreInstanceId instanceId, PageRequest request) {
        return observationRepository.listByInstance(
                Objects.requireNonNull(instanceId, INSTANCE_ID_ARGUMENT),
                Objects.requireNonNull(request, REQUEST_ARGUMENT));
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listInstanceAnomalies(
            LoreInstanceId instanceId, PageRequest request) {
        return anomalyRepository.listByInstance(
                Objects.requireNonNull(instanceId, INSTANCE_ID_ARGUMENT),
                Objects.requireNonNull(request, REQUEST_ARGUMENT));
    }

    @Override
    public CompletionStage<Page<AuditEventRecord>> listInstanceAudit(
            LoreInstanceId instanceId, PageRequest request) {
        Objects.requireNonNull(instanceId, INSTANCE_ID_ARGUMENT);
        return auditRepository.listByAggregate(
                INSTANCE_AGGREGATE_TYPE,
                instanceId.value().toString(),
                Objects.requireNonNull(request, REQUEST_ARGUMENT));
    }

    @Override
    public CompletionStage<RecoveryPage> listRecovery(PageRequest request) {
        Objects.requireNonNull(request, REQUEST_ARGUMENT);
        CompletionStage<Page<DirectDeliveryRecord>> deliveries =
                deliveryRepository.listNonTerminal(request);
        CompletionStage<Page<PendingMutationRecord>> mutations =
                mutationRepository.listNonTerminal(request);
        return deliveries.thenCombine(mutations, RecoveryPage::new);
    }
}
