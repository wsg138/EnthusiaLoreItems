package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreInstanceId;

/** Composes the three bounded reads needed by one location-evidence screen. */
final class PaperTrackingEvidenceQuery {
    private PaperTrackingEvidenceQuery() {}

    static CompletionStage<EvidenceData> load(
            LoreItemsAdministrationUseCase useCase,
            LoreInstanceId instanceId,
            PageRequest observationRequest,
            int anomalyLimit) {
        Objects.requireNonNull(useCase, "useCase");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(observationRequest, "observationRequest");

        CompletionStage<Optional<InstanceCurrentState>> current = Objects.requireNonNull(
                useCase.findCurrentState(instanceId), "current-state query stage");
        CompletionStage<Page<InstanceObservation>> observations = Objects.requireNonNull(
                useCase.listInstanceObservations(instanceId, observationRequest),
                "observation query stage");
        CompletionStage<Page<InstanceAnomaly>> anomalies = Objects.requireNonNull(
                useCase.listInstanceAnomalies(instanceId, PageRequest.first(anomalyLimit)),
                "anomaly query stage");

        return current.thenCombine(observations, StateEvidence::new)
                .thenCombine(
                        anomalies,
                        (state, anomalyPage) -> new EvidenceData(
                                state.current(), state.observations(), anomalyPage));
    }
}
