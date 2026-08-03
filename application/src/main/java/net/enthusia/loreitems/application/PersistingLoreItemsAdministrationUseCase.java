package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.LoreInstanceId;

public final class PersistingLoreItemsAdministrationUseCase
        implements LoreItemsAdministrationUseCase {
    private static final String INSTANCE_AGGREGATE_TYPE = "lore_instance";

    private final AnomalyRepository anomalyRepository;
    private final AuditRepository auditRepository;
    private final DirectDeliveryRepository deliveryRepository;
    private final PendingMutationRepository mutationRepository;

    public PersistingLoreItemsAdministrationUseCase(
            AnomalyRepository anomalyRepository,
            AuditRepository auditRepository,
            DirectDeliveryRepository deliveryRepository,
            PendingMutationRepository mutationRepository) {
        this.anomalyRepository = Objects.requireNonNull(
                anomalyRepository, "anomalyRepository");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
        this.deliveryRepository = Objects.requireNonNull(
                deliveryRepository, "deliveryRepository");
        this.mutationRepository = Objects.requireNonNull(
                mutationRepository, "mutationRepository");
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listActiveAnomalies(PageRequest request) {
        return anomalyRepository.listActive(Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listWarningAnomalies(PageRequest request) {
        return anomalyRepository.listActiveWarnings(
                Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<Page<InstanceAnomaly>> listInstanceAnomalies(
            LoreInstanceId instanceId, PageRequest request) {
        return anomalyRepository.listByInstance(
                Objects.requireNonNull(instanceId, "instanceId"),
                Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<Page<AuditEventRecord>> listInstanceAudit(
            LoreInstanceId instanceId, PageRequest request) {
        Objects.requireNonNull(instanceId, "instanceId");
        return auditRepository.listByAggregate(
                INSTANCE_AGGREGATE_TYPE,
                instanceId.value().toString(),
                Objects.requireNonNull(request, "request"));
    }

    @Override
    public CompletionStage<RecoveryPage> listRecovery(PageRequest request) {
        Objects.requireNonNull(request, "request");
        CompletionStage<Page<DirectDeliveryRecord>> deliveries =
                deliveryRepository.listNonTerminal(request);
        CompletionStage<Page<PendingMutationRecord>> mutations =
                mutationRepository.listNonTerminal(request);
        return deliveries.thenCombine(mutations, RecoveryPage::new);
    }
}
