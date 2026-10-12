package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Queue admission and persistence are independent outcomes. A coalesced event is NOT proof
 * that the earlier observation committed, and quiescence is only a drain barrier.
 */
public record PaperTrackingAcknowledgement(
        boolean admitted, CompletionStage<Settlement> settlement) {
    public PaperTrackingAcknowledgement {
        Objects.requireNonNull(settlement, "settlement");
    }

    public enum Settlement {
        CONFIRMED,
        CONFLICT_RECORDED,
        NOT_RECORDED,
        FAILED,
        REJECTED,
        COALESCED_UNVERIFIED
    }
}
