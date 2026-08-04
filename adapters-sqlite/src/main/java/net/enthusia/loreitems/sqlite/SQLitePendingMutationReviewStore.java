package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.application.PendingMutationReviewStore;
import net.enthusia.loreitems.domain.PendingMutationState;

public final class SQLitePendingMutationReviewStore implements PendingMutationReviewStore {
    private static final String MUTATION_AGGREGATE_TYPE = "pending_mutation";
    private static final String RETRY_MUTATION_SQL =
            "UPDATE pending_mutations SET state = 'PENDING', claim_token = NULL, "
                    + "claim_expires_at = NULL, next_attempt_at = ?, updated_at = ? "
                    + "WHERE mutation_id = ? AND mutation_type = ? "
                    + "AND state = 'REVIEW_REQUIRED'";
    private static final String CANCEL_MUTATION_SQL =
            "UPDATE pending_mutations SET state = 'CANCELLED', claim_token = NULL, "
                    + "claim_expires_at = NULL, next_attempt_at = NULL, updated_at = ? "
                    + "WHERE mutation_id = ? AND mutation_type = ? "
                    + "AND state = 'REVIEW_REQUIRED'";
    private static final int SINGLE_UPDATED_ROW = 1;

    private final SQLiteStorageRuntime storage;

    public SQLitePendingMutationReviewStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Status> resolve(
            UUID mutationId,
            String expectedMutationType,
            Resolution resolution,
            AuditEventRecord auditEvent,
            Instant now) {
        Objects.requireNonNull(mutationId, "mutationId");
        String normalizedType = normalizeMutationType(expectedMutationType);
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(now, "now");
        validateAuditEvent(mutationId, resolution, auditEvent, now);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> resolveInTransaction(
                        transaction,
                        mutationId,
                        normalizedType,
                        resolution,
                        auditEvent,
                        now.toEpochMilli())));
    }

    private static Status resolveInTransaction(
            Connection connection,
            UUID mutationId,
            String expectedMutationType,
            Resolution resolution,
            AuditEventRecord auditEvent,
            long now) throws SQLException {
        Optional<MutationState> current = findMutationState(connection, mutationId);
        if (current.isEmpty()) {
            return Status.NOT_FOUND;
        }
        MutationState mutation = current.orElseThrow();
        if (!mutation.mutationType().equals(expectedMutationType)) {
            return Status.TYPE_MISMATCH;
        }
        if (mutation.state() != PendingMutationState.REVIEW_REQUIRED) {
            return Status.NOT_REVIEW_REQUIRED;
        }

        PendingMutationState.REVIEW_REQUIRED.transitionTo(resolution.targetState());
        if (!updateMutation(
                connection,
                mutationId,
                expectedMutationType,
                resolution.targetState(),
                now)) {
            return Status.NOT_REVIEW_REQUIRED;
        }
        SQLiteAuditRepository.appendInTransaction(connection, auditEvent);
        return resolution.successStatus();
    }

    private static Optional<MutationState> findMutationState(
            Connection connection, UUID mutationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT mutation_type, state FROM pending_mutations WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MutationState(
                        resultSet.getString("mutation_type"),
                        PendingMutationState.valueOf(resultSet.getString("state"))));
            }
        }
    }

    private static boolean updateMutation(
            Connection connection,
            UUID mutationId,
            String mutationType,
            PendingMutationState targetState,
            long now) throws SQLException {
        if (targetState == PendingMutationState.PENDING) {
            return retryMutation(connection, mutationId, mutationType, now);
        }
        return cancelMutation(connection, mutationId, mutationType, now);
    }

    private static boolean retryMutation(
            Connection connection,
            UUID mutationId,
            String mutationType,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RETRY_MUTATION_SQL)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, mutationId.toString());
            statement.setString(4, mutationType);
            return statement.executeUpdate() == SINGLE_UPDATED_ROW;
        }
    }

    private static boolean cancelMutation(
            Connection connection,
            UUID mutationId,
            String mutationType,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CANCEL_MUTATION_SQL)) {
            statement.setLong(1, now);
            statement.setString(2, mutationId.toString());
            statement.setString(3, mutationType);
            return statement.executeUpdate() == SINGLE_UPDATED_ROW;
        }
    }

    private static String normalizeMutationType(String mutationType) {
        Objects.requireNonNull(mutationType, "expectedMutationType");
        String normalized = mutationType.strip();
        if (normalized.isEmpty()
                || normalized.length() > PendingMutationRecord.MAX_MUTATION_TYPE_LENGTH) {
            throw new IllegalArgumentException("Invalid mutation type");
        }
        return normalized;
    }

    private static void validateAuditEvent(
            UUID mutationId,
            Resolution resolution,
            AuditEventRecord auditEvent,
            Instant now) {
        Objects.requireNonNull(auditEvent, "auditEvent");
        if (auditEvent.auditId() != 0L
                || !MUTATION_AGGREGATE_TYPE.equals(auditEvent.aggregateType())
                || !mutationId.toString().equals(auditEvent.aggregateId())
                || !resolution.auditEventType().equals(auditEvent.eventType())
                || auditEvent.occurredAtEpochMillis() != now.toEpochMilli()) {
            throw new IllegalArgumentException(
                    "Audit event does not describe this mutation review resolution");
        }
    }

    private record MutationState(String mutationType, PendingMutationState state) {
        private MutationState {
            Objects.requireNonNull(mutationType, "mutationType");
            Objects.requireNonNull(state, "state");
        }
    }
}
