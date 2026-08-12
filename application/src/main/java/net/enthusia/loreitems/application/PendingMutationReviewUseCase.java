package net.enthusia.loreitems.application;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Evidence-required staff resolution for ambiguous durable item mutations. */
public interface PendingMutationReviewUseCase {
    int MAX_ACTOR_ID_LENGTH = 200;
    int MAX_EVIDENCE_DETAIL_LENGTH = 2_000;

    CompletionStage<Result> resolve(Request request);

    record Request(
            UUID mutationId,
            String expectedMutationType,
            Resolution resolution,
            String actorId,
            String evidenceDetail) {
        public Request {
            Objects.requireNonNull(mutationId, "mutationId");
            Objects.requireNonNull(expectedMutationType, "expectedMutationType");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(evidenceDetail, "evidenceDetail");
            expectedMutationType = expectedMutationType.strip().toUpperCase(Locale.ROOT);
            actorId = actorId.strip();
            evidenceDetail = evidenceDetail.strip();
            if (expectedMutationType.isEmpty()
                    || expectedMutationType.length() > PendingMutationRecord.MAX_MUTATION_TYPE_LENGTH) {
                throw new IllegalArgumentException("Invalid mutation type");
            }
            if (actorId.isEmpty() || actorId.length() > MAX_ACTOR_ID_LENGTH) {
                throw new IllegalArgumentException("Invalid mutation-review actor");
            }
            if (evidenceDetail.isEmpty() || evidenceDetail.length() > MAX_EVIDENCE_DETAIL_LENGTH) {
                throw new IllegalArgumentException("Invalid mutation-review evidence detail");
            }
        }
    }

    enum Resolution {
        RETRY,
        CANCEL
    }

    enum Status {
        RETRIED,
        CANCELLED,
        NOT_FOUND,
        TYPE_MISMATCH,
        NOT_REVIEW_REQUIRED
    }

    record Result(Status status, String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty()) {
                throw new IllegalArgumentException("Mutation-review result detail must not be blank");
            }
        }
    }
}
