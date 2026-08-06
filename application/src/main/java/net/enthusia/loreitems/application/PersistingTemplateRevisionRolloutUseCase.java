package net.enthusia.loreitems.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
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
                auditDetail(request, targetRevision),
                createdAt);
        return store.startConfirmed(new TemplateRevisionConfirmation(
                request.confirmationId(),
                revision,
                request.expectedCurrentRevision(),
                request.beforeTemplate(),
                auditEvent,
                request.actorId(),
                request.initialBatchLimit()));
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
        Objects.requireNonNull(request, "request");
        requireFirstPage(request);
        return store.listIncomplete(request);
    }

    private static void requireFirstPage(PageRequest request) {
        if (request.offset() != 0) {
            throw new IllegalArgumentException(
                    "Incomplete rollouts must be polled from the first page");
        }
    }

    private static void requireBoundedLimit(int limit) {
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
    }

    private static String auditDetail(
            TemplateRevisionRolloutRequest request, TemplateRevision targetRevision) {
        return "{\"confirmationId\":\"" + request.confirmationId()
                + "\",\"previousRevision\":" + request.expectedCurrentRevision().value()
                + ",\"targetRevision\":" + targetRevision.value()
                + ",\"beforeCodec\":" + request.beforeTemplate().codecVersion()
                + ",\"afterCodec\":" + request.template().codecVersion()
                + ",\"beforeBytes\":" + request.beforeTemplate().payload().length
                + ",\"afterBytes\":" + request.template().payload().length
                + ",\"beforeSha256\":\"" + sha256(request.beforeTemplate().payload())
                + "\",\"afterSha256\":\"" + sha256(request.template().payload())
                + "\"}";
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
