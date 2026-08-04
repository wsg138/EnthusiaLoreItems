package net.enthusia.loreitems.application;

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

public interface LoreItemsAdministrationUseCase {
    CompletionStage<Page<InstanceAnomaly>> listActiveAnomalies(PageRequest request);

    CompletionStage<Page<InstanceAnomaly>> listWarningAnomalies(PageRequest request);

    CompletionStage<Optional<InstanceCurrentState>> findCurrentState(
            LoreInstanceId instanceId);

    CompletionStage<Page<InstanceObservation>> listInstanceObservations(
            LoreInstanceId instanceId, PageRequest request);

    CompletionStage<Page<InstanceAnomaly>> listInstanceAnomalies(
            LoreInstanceId instanceId, PageRequest request);

    CompletionStage<Page<AuditEventRecord>> listInstanceAudit(
            LoreInstanceId instanceId, PageRequest request);

    CompletionStage<RecoveryPage> listRecovery(PageRequest request);

    CompletionStage<Page<LoreDefinition>> listDefinitions(PageRequest request);

    CompletionStage<Page<LoreInstance>> listInstances(
            LoreDefinitionId definitionId, PageRequest request);

    CompletionStage<DuplicateResolutionResult> resolveDuplicate(
            DuplicateResolutionRequest request);

    record DuplicateResolutionRequest(
            UUID anomalyId,
            long expectedAnomalyRevision,
            long selectedObservationId,
            String actorId) {
        public DuplicateResolutionRequest {
            Objects.requireNonNull(anomalyId, "anomalyId");
            Objects.requireNonNull(actorId, "actorId");
            actorId = actorId.strip();
            if (expectedAnomalyRevision < 0L || selectedObservationId < 1L
                    || actorId.isEmpty() || actorId.length() > InstanceAnomaly.MAX_ACTOR_LENGTH) {
                throw new IllegalArgumentException("Invalid duplicate resolution request");
            }
        }
    }

    record DuplicateResolutionResult(DuplicateResolutionStatus status, String detail) {
        public DuplicateResolutionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty() || detail.length() > InstanceAnomaly.MAX_DETAIL_LENGTH) {
                throw new IllegalArgumentException("Invalid duplicate resolution detail");
            }
        }

        public static DuplicateResolutionResult of(
                DuplicateResolutionStatus status, String detail) {
            return new DuplicateResolutionResult(status, detail);
        }
    }

    enum DuplicateResolutionStatus {
        RESOLVED,
        NOT_FOUND,
        NOT_DUPLICATE,
        INVALID_SELECTION,
        STALE,
        SERVICE_UNAVAILABLE
    }

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
