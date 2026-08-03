package net.enthusia.loreitems.application;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.LoreInstanceId;

public interface AnomalyRepository {
    CompletionStage<Void> create(InstanceAnomaly anomaly);

    CompletionStage<Optional<InstanceAnomaly>> findById(UUID anomalyId);

    CompletionStage<Page<InstanceAnomaly>> listActive(PageRequest request);

    CompletionStage<Page<InstanceAnomaly>> listByInstance(
            LoreInstanceId instanceId, PageRequest request);

    CompletionStage<Boolean> refresh(
            UUID anomalyId,
            long expectedStateRevision,
            String detail,
            long observedAtEpochMillis);

    CompletionStage<Boolean> acknowledge(
            UUID anomalyId,
            long expectedStateRevision,
            String actorId,
            long acknowledgedAtEpochMillis);

    CompletionStage<Boolean> resolve(
            UUID anomalyId,
            long expectedStateRevision,
            String resolutionDetail,
            long resolvedAtEpochMillis);
}
