package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.LoreInstanceId;

public interface LoreItemsAdministrationUseCase {
    CompletionStage<Page<InstanceAnomaly>> listActiveAnomalies(PageRequest request);

    CompletionStage<Page<InstanceAnomaly>> listWarningAnomalies(PageRequest request);

    CompletionStage<Page<InstanceAnomaly>> listInstanceAnomalies(
            LoreInstanceId instanceId, PageRequest request);

    CompletionStage<Page<AuditEventRecord>> listInstanceAudit(
            LoreInstanceId instanceId, PageRequest request);

    CompletionStage<RecoveryPage> listRecovery(PageRequest request);

    record RecoveryPage(
            Page<DirectDeliveryRecord> deliveries,
            Page<PendingMutationRecord> mutations) {
        public RecoveryPage {
            Objects.requireNonNull(deliveries, "deliveries");
            Objects.requireNonNull(mutations, "mutations");
        }

        public boolean hasMore() {
            return deliveries.hasMore() || mutations.hasMore();
        }
    }
}
