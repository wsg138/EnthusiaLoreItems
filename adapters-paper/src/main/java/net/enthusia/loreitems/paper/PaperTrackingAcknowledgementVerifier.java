package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.TrackingObservationUseCase;

/**
 * Confirms fresh holder-level evidence only after the scanner attests full coverage.
 * Neither a successful individual write nor a coalesced request proves complete coverage.
 */
final class PaperTrackingAcknowledgementVerifier {
    private PaperTrackingAcknowledgementVerifier() {}

    static CompletionStage<Boolean> confirm(
            PaperDurableTrackingRetryLedger unresolved,
            TrackingObservationUseCase.Request holder,
            boolean completeCoverage,
            List<PaperTrackingAcknowledgement> observations) {
        Objects.requireNonNull(unresolved, "unresolved");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(observations, "observations");
        if (!completeCoverage || observations.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<?>[] stages = observations.stream()
                .map(observation -> observation.settlement().toCompletableFuture())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(stages).handle((ignored, failure) ->
                failure == null && confirmReceipts(unresolved, holder, observations));
    }

    private static boolean confirmReceipts(
            PaperDurableTrackingRetryLedger unresolved,
            TrackingObservationUseCase.Request holder,
            List<PaperTrackingAcknowledgement> observations) {
        long earliest = Long.MAX_VALUE;
        for (PaperTrackingAcknowledgement observation : observations) {
            if (!verifiedHolderReceipt(unresolved, holder, observation)) {
                return false;
            }
            earliest = Math.min(earliest, observation.sequence());
        }
        return unresolved.reconcile(holder, earliest);
    }

    private static boolean verifiedHolderReceipt(
            PaperDurableTrackingRetryLedger unresolved,
            TrackingObservationUseCase.Request holder,
            PaperTrackingAcknowledgement observation) {
        TrackingObservationUseCase.Request request = observation.request();
        return observation.admitted()
                && request.presence() == TrackingObservationUseCase.Presence.PRESENT
                && request.mode() == TrackingObservationUseCase.EvidenceMode.RECONCILIATION
                && unresolved.sameHolder(holder, request)
                && observation.settlement().toCompletableFuture().join()
                        == PaperTrackingAcknowledgement.Settlement.CONFIRMED;
    }
}
