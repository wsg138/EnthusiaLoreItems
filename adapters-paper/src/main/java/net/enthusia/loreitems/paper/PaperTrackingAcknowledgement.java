package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.TrackingObservationUseCase;

/**
 * Queue admission is not a durable acknowledgement. The settlement view is read-only:
 * caller completion of its toCompletableFuture() copy cannot forge an underlying DB result.
 */
public final class PaperTrackingAcknowledgement {
    private final boolean admissionAccepted;
    private final CompletionStage<Settlement> settlementView;
    private final TrackingObservationUseCase.Request observation;
    private final long observationSequence;
    private final Object issuingCoordinator;

    private PaperTrackingAcknowledgement(
            boolean admitted, CompletableFuture<Settlement> future,
            TrackingObservationUseCase.Request request, long sequence, Object issuer) {
        this.admissionAccepted = admitted;
        this.settlementView = Objects.requireNonNull(future, "future").minimalCompletionStage();
        this.observation = Objects.requireNonNull(request, "request");
        this.issuingCoordinator = Objects.requireNonNull(issuer, "issuer");
        this.observationSequence = sequence;
    }

    static PaperTrackingAcknowledgement issued(
            boolean admitted, CompletableFuture<Settlement> future,
            TrackingObservationUseCase.Request request, long sequence, Object issuer) {
        return new PaperTrackingAcknowledgement(admitted, future, request, sequence, issuer);
    }

    boolean belongsTo(Object expectedIssuer) {
        return issuingCoordinator == expectedIssuer;
    }

    public boolean admitted() {
        return admissionAccepted;
    }

    public CompletionStage<Settlement> settlement() {
        return settlementView;
    }

    public TrackingObservationUseCase.Request request() {
        return observation;
    }

    public long sequence() {
        return observationSequence;
    }

    public enum Settlement {
        CONFIRMED, CONFLICT_RECORDED, NOT_RECORDED,
        FAILED, REJECTED, COALESCED_UNVERIFIED
    }
}
