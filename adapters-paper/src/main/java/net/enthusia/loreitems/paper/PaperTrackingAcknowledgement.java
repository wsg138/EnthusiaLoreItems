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
    private final boolean admitted;
    private final CompletionStage<Settlement> settlement;
    private final TrackingObservationUseCase.Request request;
    private final long sequence;
    private final Object issuer;

    private PaperTrackingAcknowledgement(
            boolean admitted, CompletableFuture<Settlement> future,
            TrackingObservationUseCase.Request request, long sequence, Object issuer) {
        this.admitted = admitted;
        this.settlement = Objects.requireNonNull(future, "future").minimalCompletionStage();
        this.request = Objects.requireNonNull(request, "request");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.sequence = sequence;
    }

    static PaperTrackingAcknowledgement issued(
            boolean admitted, CompletableFuture<Settlement> future,
            TrackingObservationUseCase.Request request, long sequence, Object issuer) {
        return new PaperTrackingAcknowledgement(admitted, future, request, sequence, issuer);
    }

    boolean belongsTo(Object expectedIssuer) {
        return issuer == expectedIssuer;
    }

    public boolean admitted() {
        return admitted;
    }

    public CompletionStage<Settlement> settlement() {
        return settlement;
    }

    public TrackingObservationUseCase.Request request() {
        return request;
    }

    public long sequence() {
        return sequence;
    }

    public enum Settlement {
        CONFIRMED, CONFLICT_RECORDED, NOT_RECORDED,
        FAILED, REJECTED, COALESCED_UNVERIFIED
    }
}
