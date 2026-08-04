package net.enthusia.loreitems.application;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.PendingMutationState;

public interface PendingMutationReviewStore {
    CompletionStage<Status> resolve(
            UUID mutationId,
            String expectedMutationType,
            Resolution resolution,
            AuditEventRecord auditEvent,
            Instant now);

    enum Resolution {
        RETRY(PendingMutationState.PENDING, Status.RETRIED, "mutation_review_retried"),
        CANCEL(PendingMutationState.CANCELLED, Status.CANCELLED, "mutation_review_cancelled");

        private final PendingMutationState targetState;
        private final Status successStatus;
        private final String auditEventType;

        Resolution(
                PendingMutationState targetState,
                Status successStatus,
                String auditEventType) {
            this.targetState = targetState;
            this.successStatus = successStatus;
            this.auditEventType = auditEventType;
        }

        public PendingMutationState targetState() {
            return targetState;
        }

        public Status successStatus() {
            return successStatus;
        }

        public String auditEventType() {
            return auditEventType;
        }
    }

    enum Status {
        RETRIED,
        CANCELLED,
        NOT_FOUND,
        TYPE_MISMATCH,
        NOT_REVIEW_REQUIRED
    }
}
