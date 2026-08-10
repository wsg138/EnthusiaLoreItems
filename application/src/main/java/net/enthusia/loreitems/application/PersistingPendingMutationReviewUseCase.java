package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Audit-backed orchestration for resolving fenced REVIEW_REQUIRED mutations. */
public final class PersistingPendingMutationReviewUseCase
        implements PendingMutationReviewUseCase {
    private static final String AGGREGATE_TYPE = "pending_mutation";
    private static final String ACTOR_TYPE = "STAFF";

    private final PendingMutationReviewStore store;
    private final Clock clock;

    public PersistingPendingMutationReviewUseCase(
            PendingMutationReviewStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Result> resolve(Request request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        PendingMutationReviewStore.Resolution resolution = switch (request.resolution()) {
            case RETRY -> PendingMutationReviewStore.Resolution.RETRY;
            case CANCEL -> PendingMutationReviewStore.Resolution.CANCEL;
        };
        AuditEventRecord audit = AuditEventRecord.pending(
                AGGREGATE_TYPE,
                request.mutationId().toString(),
                resolution.auditEventType(),
                ACTOR_TYPE,
                request.actorId(),
                detailJson(request),
                now.toEpochMilli());
        return store.resolve(
                        request.mutationId(),
                        request.expectedMutationType(),
                        resolution,
                        audit,
                        now)
                .thenApply(PersistingPendingMutationReviewUseCase::result);
    }

    private static Result result(PendingMutationReviewStore.Status status) {
        return switch (status) {
            case RETRIED -> new Result(
                    Status.RETRIED,
                    "The reviewed mutation was returned to the pending queue for a bounded retry.");
            case CANCELLED -> new Result(
                    Status.CANCELLED,
                    "The reviewed mutation was cancelled without another physical attempt.");
            case NOT_FOUND -> new Result(Status.NOT_FOUND, "The mutation was not found.");
            case TYPE_MISMATCH -> new Result(
                    Status.TYPE_MISMATCH,
                    "The mutation type did not match the durable review record.");
            case NOT_REVIEW_REQUIRED -> new Result(
                    Status.NOT_REVIEW_REQUIRED,
                    "The mutation is not currently waiting for staff review.");
        };
    }

    private static String detailJson(Request request) {
        return "{\"mutationType\":\"" + escapeJson(request.expectedMutationType())
                + "\",\"resolution\":\"" + request.resolution().name()
                + "\",\"evidence\":\"" + escapeJson(request.evidenceDetail()) + "\"}";
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
