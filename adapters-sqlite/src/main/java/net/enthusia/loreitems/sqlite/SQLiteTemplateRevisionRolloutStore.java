package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TemplateRevisionRolloutBatchResult;
import net.enthusia.loreitems.application.TemplateRevisionRolloutBatchStatus;
import net.enthusia.loreitems.application.TemplateRevisionRolloutCandidate;
import net.enthusia.loreitems.application.TemplateRevisionRolloutStore;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class SQLiteTemplateRevisionRolloutStore
        implements TemplateRevisionRolloutStore {
    static final String MUTATION_TYPE = "TEMPLATE_UPDATE";

    private static final int SINGLE_UPDATED_ROW = 1;
    private static final long MIN_TIMESTAMP = 0L;

    private final SQLiteStorageRuntime storage;
    private final Supplier<UUID> mutationIdSupplier;

    public SQLiteTemplateRevisionRolloutStore(SQLiteStorageRuntime storage) {
        this(storage, UUID::randomUUID);
    }

    SQLiteTemplateRevisionRolloutStore(
            SQLiteStorageRuntime storage, Supplier<UUID> mutationIdSupplier) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.mutationIdSupplier = Objects.requireNonNull(
                mutationIdSupplier, "mutationIdSupplier");
    }

    @Override
    public CompletionStage<TemplateRevisionStartResult> start(
            LoreDefinitionRevision newRevision,
            TemplateRevision expectedCurrentRevision,
            AuditEventRecord auditEvent,
            int initialBatchLimit) {
        validateStart(newRevision, expectedCurrentRevision, auditEvent, initialBatchLimit);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> startInTransaction(
                        transaction,
                        newRevision,
                        expectedCurrentRevision,
                        auditEvent,
                        initialBatchLimit)));
    }

    @Override
    public CompletionStage<TemplateRevisionRolloutBatchResult> scheduleNextBatch(
            TemplateRevisionRolloutCandidate candidate,
            long scheduledAtEpochMillis,
            int limit) {
        Objects.requireNonNull(candidate, "candidate");
        requireTimestamp(scheduledAtEpochMillis, "scheduledAtEpochMillis");
        requireBoundedLimit(limit);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> scheduleCandidateInTransaction(
                        transaction, candidate, scheduledAtEpochMillis, limit)));
    }

    @Override
    public CompletionStage<Page<TemplateRevisionRolloutCandidate>> listIncomplete(
            PageRequest request) {
        Objects.requireNonNull(request, "request");
        requireFirstPage(request);
        return storage.execute(connection -> listIncomplete(connection, request));
    }

    private TemplateRevisionStartResult startInTransaction(
            Connection connection,
            LoreDefinitionRevision newRevision,
            TemplateRevision expectedCurrentRevision,
            AuditEventRecord auditEvent,
            int initialBatchLimit) throws SQLException {
        LoreDefinitionId definitionId = newRevision.definitionId();
        Optional<DefinitionState> definitionState = findDefinitionState(connection, definitionId);
        if (definitionState.isEmpty()) {
            return TemplateRevisionStartResult.definitionNotFound(definitionId);
        }
        DefinitionState state = definitionState.orElseThrow();
        if (state.deleted()) {
            return TemplateRevisionStartResult.definitionDeleted(
                    definitionId, state.currentRevision());
        }
        if (!state.currentRevision().equals(expectedCurrentRevision)) {
            return TemplateRevisionStartResult.revisionConflict(
                    definitionId, state.currentRevision());
        }
        if (hasActiveRolloutWork(connection, definitionId, expectedCurrentRevision)) {
            return TemplateRevisionStartResult.rolloutInProgress(
                    definitionId, state.currentRevision());
        }
        if (!appendRevisionInTransaction(
                connection, definitionId, expectedCurrentRevision, newRevision)) {
            DefinitionState current = findDefinitionState(connection, definitionId).orElseThrow();
            return TemplateRevisionStartResult.revisionConflict(
                    definitionId, current.currentRevision());
        }
        TemplateRevisionRolloutBatchResult initialBatch = scheduleBatchInTransaction(
                connection,
                definitionId,
                newRevision.revision(),
                newRevision.createdAtEpochMillis(),
                initialBatchLimit);
        SQLiteAuditRepository.appendInTransaction(connection, auditEvent);
        return TemplateRevisionStartResult.started(
                definitionId, newRevision.revision(), initialBatch);
    }

    private TemplateRevisionRolloutBatchResult scheduleCandidateInTransaction(
            Connection connection,
            TemplateRevisionRolloutCandidate candidate,
            long scheduledAtEpochMillis,
            int limit) throws SQLException {
        Optional<DefinitionState> definitionState =
                findDefinitionState(connection, candidate.definitionId());
        if (definitionState.isEmpty()) {
            return TemplateRevisionRolloutBatchResult.rejected(
                    TemplateRevisionRolloutBatchStatus.DEFINITION_NOT_FOUND);
        }
        DefinitionState state = definitionState.orElseThrow();
        if (state.deleted()) {
            return TemplateRevisionRolloutBatchResult.rejected(
                    TemplateRevisionRolloutBatchStatus.DEFINITION_DELETED);
        }
        if (!state.currentRevision().equals(candidate.targetRevision())) {
            return TemplateRevisionRolloutBatchResult.rejected(
                    TemplateRevisionRolloutBatchStatus.STALE_REVISION);
        }
        return scheduleBatchInTransaction(
                connection,
                candidate.definitionId(),
                candidate.targetRevision(),
                scheduledAtEpochMillis,
                limit);
    }

    private TemplateRevisionRolloutBatchResult scheduleBatchInTransaction(
            Connection connection,
            LoreDefinitionId definitionId,
            TemplateRevision targetRevision,
            long scheduledAtEpochMillis,
            int limit) throws SQLException {
        List<LoreInstanceId> candidates = findCandidateInstances(
                connection, definitionId, targetRevision, limit);
        for (LoreInstanceId instanceId : candidates) {
            advanceDesiredRevision(
                    connection, definitionId, instanceId, targetRevision);
            insertMutation(
                    connection,
                    definitionId,
                    instanceId,
                    targetRevision,
                    scheduledAtEpochMillis);
        }
        boolean hasMore = hasIncompleteInstances(
                connection, definitionId, targetRevision);
        return TemplateRevisionRolloutBatchResult.scheduled(candidates.size(), hasMore);
    }

    private static boolean hasActiveRolloutWork(
            Connection connection,
            LoreDefinitionId definitionId,
            TemplateRevision currentRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 WHERE EXISTS ("
                        + "SELECT 1 FROM lore_instances WHERE definition_id = ? "
                        + "AND lifecycle_state = 'ACTIVE' AND desired_revision < ?"
                        + ") OR EXISTS ("
                        + "SELECT 1 FROM pending_mutations WHERE definition_id = ? "
                        + "AND mutation_type = ? AND state <> 'COMPLETED'"
                        + ")")) {
            statement.setString(1, definitionId.value().toString());
            statement.setLong(2, currentRevision.value());
            statement.setString(3, definitionId.value().toString());
            statement.setString(4, MUTATION_TYPE);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean appendRevisionInTransaction(
            Connection connection,
            LoreDefinitionId definitionId,
            TemplateRevision expectedCurrentRevision,
            LoreDefinitionRevision newRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE lore_definitions SET current_revision = ? "
                        + "WHERE definition_id = ? AND current_revision = ? "
                        + "AND deleted_at IS NULL")) {
            statement.setLong(1, newRevision.revision().value());
            statement.setString(2, definitionId.value().toString());
            statement.setLong(3, expectedCurrentRevision.value());
            if (statement.executeUpdate() != SINGLE_UPDATED_ROW) {
                return false;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                        + "template_blob, created_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, definitionId.value().toString());
            statement.setLong(2, newRevision.revision().value());
            statement.setInt(3, newRevision.codecVersion());
            statement.setBytes(4, newRevision.templateBlob());
            statement.setLong(5, newRevision.createdAtEpochMillis());
            statement.executeUpdate();
        }
        return true;
    }

    private void insertMutation(
            Connection connection,
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId,
            TemplateRevision targetRevision,
            long scheduledAtEpochMillis) throws SQLException {
        UUID mutationId = Objects.requireNonNull(
                mutationIdSupplier.get(), "generated mutation ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pending_mutations(mutation_id, mutation_type, definition_id, "
                        + "instance_id, desired_revision, state, claim_token, claim_expires_at, "
                        + "attempt_count, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING', NULL, NULL, 0, NULL, ?, ?)")) {
            statement.setString(1, mutationId.toString());
            statement.setString(2, MUTATION_TYPE);
            statement.setString(3, definitionId.value().toString());
            statement.setString(4, instanceId.value().toString());
            statement.setLong(5, targetRevision.value());
            statement.setLong(6, scheduledAtEpochMillis);
            statement.setLong(7, scheduledAtEpochMillis);
            statement.executeUpdate();
        }
    }

    private static void advanceDesiredRevision(
            Connection connection,
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId,
            TemplateRevision targetRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE lore_instances SET desired_revision = ? "
                        + "WHERE instance_id = ? AND definition_id = ? "
                        + "AND lifecycle_state = 'ACTIVE' AND desired_revision < ?")) {
            statement.setLong(1, targetRevision.value());
            statement.setString(2, instanceId.value().toString());
            statement.setString(3, definitionId.value().toString());
            statement.setLong(4, targetRevision.value());
            if (statement.executeUpdate() != SINGLE_UPDATED_ROW) {
                throw new SQLException(
                        "Revision rollout candidate changed before it could be scheduled");
            }
        }
    }

    private static List<LoreInstanceId> findCandidateInstances(
            Connection connection,
            LoreDefinitionId definitionId,
            TemplateRevision targetRevision,
            int limit) throws SQLException {
        List<LoreInstanceId> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT instance_id FROM lore_instances "
                        + "WHERE definition_id = ? AND lifecycle_state = 'ACTIVE' "
                        + "AND desired_revision < ? ORDER BY instance_id LIMIT ?")) {
            statement.setString(1, definitionId.value().toString());
            statement.setLong(2, targetRevision.value());
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(new LoreInstanceId(
                            UUID.fromString(resultSet.getString("instance_id"))));
                }
            }
        }
        return candidates;
    }

    private static boolean hasIncompleteInstances(
            Connection connection,
            LoreDefinitionId definitionId,
            TemplateRevision targetRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM lore_instances WHERE definition_id = ? "
                        + "AND lifecycle_state = 'ACTIVE' AND desired_revision < ? LIMIT 1")) {
            statement.setString(1, definitionId.value().toString());
            statement.setLong(2, targetRevision.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static Page<TemplateRevisionRolloutCandidate> listIncomplete(
            Connection connection, PageRequest request) throws SQLException {
        List<TemplateRevisionRolloutCandidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, current_revision FROM lore_definitions definition_row "
                        + "WHERE deleted_at IS NULL AND EXISTS ("
                        + "SELECT 1 FROM lore_instances instance_row "
                        + "WHERE instance_row.definition_id = definition_row.definition_id "
                        + "AND instance_row.lifecycle_state = 'ACTIVE' "
                        + "AND instance_row.desired_revision < definition_row.current_revision) "
                        + "ORDER BY lookup_key, definition_id LIMIT ?")) {
            statement.setInt(1, request.limit() + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(readRolloutCandidate(resultSet));
                }
            }
        }
        boolean hasMore = candidates.size() > request.limit();
        if (hasMore) {
            candidates.remove(candidates.size() - 1);
        }
        return new Page<>(candidates, request.offset(), request.limit(), hasMore);
    }

    private static TemplateRevisionRolloutCandidate readRolloutCandidate(ResultSet resultSet)
            throws SQLException {
        return new TemplateRevisionRolloutCandidate(
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                new TemplateRevision(resultSet.getLong("current_revision")));
    }

    private static Optional<DefinitionState> findDefinitionState(
            Connection connection, LoreDefinitionId definitionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_revision, deleted_at FROM lore_definitions "
                        + "WHERE definition_id = ?")) {
            statement.setString(1, definitionId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                resultSet.getLong("deleted_at");
                boolean deleted = !resultSet.wasNull();
                return Optional.of(new DefinitionState(
                        new TemplateRevision(resultSet.getLong("current_revision")),
                        deleted));
            }
        }
    }

    private static void validateStart(
            LoreDefinitionRevision newRevision,
            TemplateRevision expectedCurrentRevision,
            AuditEventRecord auditEvent,
            int initialBatchLimit) {
        Objects.requireNonNull(newRevision, "newRevision");
        Objects.requireNonNull(expectedCurrentRevision, "expectedCurrentRevision");
        Objects.requireNonNull(auditEvent, "auditEvent");
        requireBoundedLimit(initialBatchLimit);
        if (!newRevision.revision().equals(expectedCurrentRevision.next())) {
            throw new IllegalArgumentException(
                    "New revision must immediately follow the expected revision");
        }
        if (!auditEvent.aggregateId().equals(newRevision.definitionId().value().toString())) {
            throw new IllegalArgumentException(
                    "Audit event belongs to another definition");
        }
        if (auditEvent.occurredAtEpochMillis() != newRevision.createdAtEpochMillis()) {
            throw new IllegalArgumentException(
                    "Revision and audit timestamps must match");
        }
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

    private static void requireTimestamp(long timestamp, String name) {
        if (timestamp < MIN_TIMESTAMP) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private record DefinitionState(
            TemplateRevision currentRevision,
            boolean deleted) {}
}
