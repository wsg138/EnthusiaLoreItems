package net.enthusia.loreitems.application;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class PersistingTemplateRevisionRolloutUseCase
        implements TemplateRevisionRolloutUseCase {
    private static final String AGGREGATE_TYPE = "lore_definition";
    private static final String EVENT_TYPE = "template_revision_started";
    private static final String ACTOR_TYPE = "player";

    private final TemplateRevisionRolloutStore store;
    private final Clock clock;

    public PersistingTemplateRevisionRolloutUseCase(
            TemplateRevisionRolloutStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<TemplateRevisionStartResult> start(
            TemplateRevisionRolloutRequest request) {
        Objects.requireNonNull(request, "request");
        long createdAt = clock.millis();
        TemplateRevision targetRevision = request.expectedCurrentRevision().next();
        LoreDefinitionRevision revision = new LoreDefinitionRevision(
                request.definitionId(),
                targetRevision,
                request.template().codecVersion(),
                request.template().payload(),
                createdAt);
        AuditEventRecord auditEvent = AuditEventRecord.pending(
                AGGREGATE_TYPE,
                request.definitionId().value().toString(),
                EVENT_TYPE,
                ACTOR_TYPE,
                request.actorId().toString(),
                auditDetail(request.expectedCurrentRevision(), targetRevision),
                createdAt);
        return store.start(
                revision,
                request.expectedCurrentRevision(),
                auditEvent,
                request.initialBatchLimit());
    }

    @Override
    public CompletionStage<TemplateRevisionRolloutBatchResult> scheduleNextBatch(
            TemplateRevisionRolloutCandidate candidate, int limit) {
        Objects.requireNonNull(candidate, "candidate");
        requireBoundedLimit(limit);
        return store.scheduleNextBatch(candidate, clock.millis(), limit);
    }

    @Override
    public CompletionStage<Page<TemplateRevisionRolloutCandidate>> listIncomplete(
            PageRequest request) {
        return store.listIncomplete(Objects.requireNonNull(request, "request"));
    }

    private static void requireBoundedLimit(int limit) {
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
    }

    private static String auditDetail(
            TemplateRevision previousRevision, TemplateRevision targetRevision) {
        return "{\"previousRevision\":" + previousRevision.value()
                + ",\"targetRevision\":" + targetRevision.value() + '}';
    }
}
