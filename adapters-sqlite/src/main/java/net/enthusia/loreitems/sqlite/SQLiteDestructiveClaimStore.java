package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Observation;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.PrepareResult;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.PreparedRemoval;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

final class SQLiteDestructiveClaimStore {
    private static final int SINGLE_ROW = 1;
    private static final long MIN_LEASE_MILLIS = 1L;
    private final SQLiteStorageRuntime storage;

    SQLiteDestructiveClaimStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    CompletionStage<PrepareResult> prepareRemoval(
            Observation observation,
            String claimToken,
            Instant now,
            Duration lease) {
        Objects.requireNonNull(observation, "observation");
        String normalizedToken = normalizeClaimToken(claimToken);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        long leaseMillis = lease.toMillis();
        if (leaseMillis < MIN_LEASE_MILLIS) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> prepareRemoval(
                        transaction,
                        observation,
                        normalizedToken,
                        now.toEpochMilli(),
                        leaseMillis)));
    }

    private static PrepareResult prepareRemoval(
            Connection connection,
            Observation observation,
            String claimToken,
            long now,
            long leaseMillis) throws SQLException {
        Candidate target = resolveCandidate(connection, observation, now);
        if (target == null || target.operationState() != DestructiveOperationState.ACTIVE) {
            return PrepareResult.noPendingWork();
        }
        String mismatch = mismatchDetail(target, observation);
        if (mismatch != null) {
            requireUnclaimedReview(connection, target, mismatch, now);
            return PrepareResult.reviewRequired(mismatch);
        }
        long expiresAt = Math.addExact(now, leaseMillis);
        if (!claim(connection, target, observation, claimToken, expiresAt, now)) {
            return PrepareResult.noPendingWork();
        }
        return PrepareResult.prepared(preparedRemoval(
                target, observation, claimToken, expiresAt));
    }

    private static Candidate resolveCandidate(
            Connection connection,
            Observation observation,
            long now) throws SQLException {
        Candidate target = findPendingCandidate(connection, observation.identity());
        if (target == null) {
            target = reopenLateTarget(connection, observation, now);
        }
        if (target == null) {
            target = createLateDeletionTarget(connection, observation, now);
        }
        return target;
    }

    private static PreparedRemoval preparedRemoval(
            Candidate target,
            Observation observation,
            String claimToken,
            long expiresAt) {
        return new PreparedRemoval(
                target.operationId(),
                target.operationType(),
                target.definitionId(),
                target.instanceId(),
                target.expectedAppliedRevision(),
                observation.identity(),
                observation.locationType(),
                observation.locationKey(),
                observation.containerPath(),
                observation.fingerprint(),
                claimToken,
                expiresAt);
    }

    private static Candidate findPendingCandidate(
            Connection connection,
            LoreItemIdentity identity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT target.*, operation.operation_type, operation.state operation_state "
                        + "FROM destructive_targets target JOIN destructive_operations operation "
                        + "ON operation.operation_id = target.operation_id "
                        + "WHERE target.instance_id = ? AND target.definition_id = ? "
                        + "AND target.state = 'PENDING' "
                        + "ORDER BY operation.accepted_at, operation.operation_id LIMIT 1")) {
            statement.setString(1, identity.instanceId().value().toString());
            statement.setString(2, identity.definitionId().value().toString());
            return readCandidate(statement);
        }
    }

    private static Candidate reopenLateTarget(
            Connection connection,
            Observation observation,
            long now) throws SQLException {
        Candidate completed = findCompletedReopenableTarget(connection, observation.identity());
        if (completed == null || !reopenTarget(connection, completed, now)) {
            return null;
        }
        reopenParent(connection, completed.operationId(), now);
        SQLiteDestructiveControlStore.appendAudit(
                connection,
                completed.operationId(),
                "destructive_late_copy_reopened",
                "SYSTEM",
                instanceDetail(completed.instanceId()),
                now);
        return findPendingCandidate(connection, observation.identity());
    }

    private static Candidate findCompletedReopenableTarget(
            Connection connection,
            LoreItemIdentity identity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT target.*, operation.operation_type, operation.state operation_state "
                        + "FROM destructive_targets target JOIN destructive_operations operation "
                        + "ON operation.operation_id = target.operation_id "
                        + "WHERE target.instance_id = ? AND target.definition_id = ? "
                        + "AND target.state = 'COMPLETED' "
                        + "AND operation.operation_type IN ('PURGE_DEFINITION', 'DELETE_DEFINITION') "
                        + "AND operation.state <> 'ABORTED' "
                        + "ORDER BY operation.accepted_at DESC, operation.operation_id DESC LIMIT 1")) {
            statement.setString(1, identity.instanceId().value().toString());
            statement.setString(2, identity.definitionId().value().toString());
            return readCandidate(statement);
        }
    }

    private static boolean reopenTarget(
            Connection connection,
            Candidate completed,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'PENDING', effect_state = 'UNKNOWN', "
                        + "claim_token = NULL, claim_expires_at = NULL, expected_fingerprint = NULL, "
                        + "before_fingerprint = NULL, after_fingerprint = NULL, last_error = ?, "
                        + "updated_at = ? WHERE operation_id = ? AND instance_id = ? "
                        + "AND state = 'COMPLETED'")) {
            statement.setString(1, "A naturally encountered late copy reopened completed purge/delete work.");
            statement.setLong(2, now);
            statement.setString(3, completed.operationId().toString());
            statement.setString(4, completed.instanceId().value().toString());
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static Candidate createLateDeletionTarget(
            Connection connection,
            Observation observation,
            long now) throws SQLException {
        DeletionOperation deletion = findDeletionOperation(
                connection, observation.identity().definitionId());
        if (deletion == null || deletion.state() == DestructiveOperationState.ABORTED) {
            return null;
        }
        ensureLateInstance(connection, observation.identity(), now);
        if (insertLateTarget(connection, deletion.operationId(), observation, now)) {
            incrementTargetCountAndReopen(connection, deletion.operationId(), now);
            SQLiteDestructiveControlStore.appendAudit(
                    connection,
                    deletion.operationId(),
                    "destructive_late_delete_target_created",
                    "SYSTEM",
                    instanceDetail(observation.identity().instanceId()),
                    now);
        }
        return findPendingCandidate(connection, observation.identity());
    }

    private static DeletionOperation findDeletionOperation(
            Connection connection,
            LoreDefinitionId definitionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT operation.operation_id, operation.state "
                        + "FROM destructive_operations operation JOIN deleted_definition_markers marker "
                        + "ON marker.definition_id = operation.definition_id "
                        + "WHERE operation.definition_id = ? "
                        + "AND operation.operation_type = 'DELETE_DEFINITION' "
                        + "ORDER BY operation.accepted_at DESC, operation.operation_id DESC LIMIT 1")) {
            statement.setString(1, definitionId.value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new DeletionOperation(
                                UUID.fromString(resultSet.getString("operation_id")),
                                DestructiveOperationState.valueOf(resultSet.getString("state")))
                        : null;
            }
        }
    }

    private static void ensureLateInstance(
            Connection connection,
            LoreItemIdentity identity,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at, terminal_at) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, NULL)")) {
            statement.setString(1, identity.instanceId().value().toString());
            statement.setString(2, identity.definitionId().value().toString());
            statement.setLong(3, identity.appliedRevision().value());
            statement.setLong(4, identity.appliedRevision().value());
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static boolean insertLateTarget(
            Connection connection,
            UUID operationId,
            Observation observation,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO destructive_targets(operation_id, instance_id, definition_id, "
                        + "expected_applied_revision, expected_location_type, expected_location_key, "
                        + "expected_container_path, expected_fingerprint, state, effect_state, "
                        + "claim_token, claim_expires_at, attempt_count, before_fingerprint, "
                        + "after_fingerprint, last_error, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'PENDING', 'UNKNOWN', NULL, NULL, 0, "
                        + "NULL, NULL, ?, ?, ?)")) {
            bindLateTarget(statement, operationId, observation, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static void bindLateTarget(
            PreparedStatement statement,
            UUID operationId,
            Observation observation,
            long now) throws SQLException {
        statement.setString(1, operationId.toString());
        statement.setString(2, observation.identity().instanceId().value().toString());
        statement.setString(3, observation.identity().definitionId().value().toString());
        statement.setLong(4, observation.identity().appliedRevision().value());
        statement.setString(5, observation.locationType());
        statement.setString(6, observation.locationKey());
        SQLiteDestructiveRows.setNullableString(statement, 7, observation.containerPath());
        statement.setString(8, "A late physical copy appeared after full-definition deletion.");
        statement.setLong(9, now);
        statement.setLong(10, now);
    }

    private static void incrementTargetCountAndReopen(
            Connection connection,
            UUID operationId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_operations SET target_count = target_count + 1, "
                        + "state = CASE WHEN state = 'PAUSED' THEN 'PAUSED' ELSE 'ACTIVE' END, "
                        + "updated_at = ?, terminal_at = NULL "
                        + "WHERE operation_id = ? AND state IN ('ACTIVE', 'PAUSED', 'COMPLETED')")) {
            statement.setLong(1, now);
            statement.setString(2, operationId.toString());
            statement.executeUpdate();
        }
    }

    private static void reopenParent(Connection connection, UUID operationId, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_operations SET state = 'ACTIVE', updated_at = ?, "
                        + "terminal_at = NULL WHERE operation_id = ? AND state = 'COMPLETED'")) {
            statement.setLong(1, now);
            statement.setString(2, operationId.toString());
            statement.executeUpdate();
        }
    }

    private static String mismatchDetail(Candidate target, Observation observation) {
        LoreItemIdentity identity = observation.identity();
        if (!target.definitionId().equals(identity.definitionId())
                || !target.instanceId().equals(identity.instanceId())) {
            return "The encountered physical identity does not match the destructive target.";
        }
        if (!target.expectedAppliedRevision().equals(identity.appliedRevision())) {
            return "The encountered applied revision changed after destructive acceptance.";
        }
        return exactMismatch(target, observation);
    }

    private static String exactMismatch(Candidate target, Observation observation) {
        if (target.operationType() != DestructiveOperationType.EXACT_INSTANCE_REMOVAL) {
            return null;
        }
        if (!Objects.equals(target.expectedLocationType(), observation.locationType())
                || !Objects.equals(target.expectedLocationKey(), observation.locationKey())
                || !Objects.equals(target.expectedContainerPath(), observation.containerPath())) {
            return "The exact-removal target moved after the confirmation snapshot.";
        }
        if (target.expectedFingerprint() != null
                && !target.expectedFingerprint().equals(observation.fingerprint())) {
            return "The exact-removal target fingerprint changed after durable preparation.";
        }
        return null;
    }

    private static boolean claim(
            Connection connection,
            Candidate target,
            Observation observation,
            String claimToken,
            long expiresAt,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'CLAIMED', effect_state = 'UNKNOWN', "
                        + "claim_token = ?, claim_expires_at = ?, attempt_count = attempt_count + 1, "
                        + "expected_fingerprint = COALESCE(expected_fingerprint, ?), "
                        + "before_fingerprint = NULL, after_fingerprint = NULL, last_error = NULL, "
                        + "updated_at = ? WHERE operation_id = ? AND instance_id = ? "
                        + "AND state = 'PENDING' AND EXISTS (SELECT 1 FROM destructive_operations "
                        + "WHERE operation_id = ? AND state = 'ACTIVE')")) {
            statement.setString(1, claimToken);
            statement.setLong(2, expiresAt);
            statement.setString(3, observation.fingerprint());
            statement.setLong(4, now);
            statement.setString(5, target.operationId().toString());
            statement.setString(6, target.instanceId().value().toString());
            statement.setString(7, target.operationId().toString());
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static void requireUnclaimedReview(
            Connection connection,
            Candidate target,
            String detail,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'REVIEW_REQUIRED', "
                        + "effect_state = 'NONE_OBSERVED', claim_token = NULL, "
                        + "claim_expires_at = NULL, last_error = ?, updated_at = ? "
                        + "WHERE operation_id = ? AND instance_id = ? AND state = 'PENDING'")) {
            statement.setString(1, detail);
            statement.setLong(2, now);
            statement.setString(3, target.operationId().toString());
            statement.setString(4, target.instanceId().value().toString());
            statement.executeUpdate();
        }
    }

    private static Candidate readCandidate(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            return new Candidate(
                    UUID.fromString(resultSet.getString("operation_id")),
                    DestructiveOperationType.valueOf(resultSet.getString("operation_type")),
                    DestructiveOperationState.valueOf(resultSet.getString("operation_state")),
                    SQLiteDestructiveRows.definitionId(resultSet.getString("definition_id")),
                    SQLiteDestructiveRows.instanceId(resultSet.getString("instance_id")),
                    new TemplateRevision(resultSet.getLong("expected_applied_revision")),
                    resultSet.getString("expected_location_type"),
                    resultSet.getString("expected_location_key"),
                    resultSet.getString("expected_container_path"),
                    resultSet.getString("expected_fingerprint"));
        }
    }

    private static String normalizeClaimToken(String value) {
        Objects.requireNonNull(value, "claimToken");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException("Invalid claimToken");
        }
        return normalized;
    }

    private static String instanceDetail(LoreInstanceId instanceId) {
        return "{\"instanceId\":\"" + instanceId.value() + "\"}";
    }

    private record Candidate(
            UUID operationId,
            DestructiveOperationType operationType,
            DestructiveOperationState operationState,
            LoreDefinitionId definitionId,
            LoreInstanceId instanceId,
            TemplateRevision expectedAppliedRevision,
            String expectedLocationType,
            String expectedLocationKey,
            String expectedContainerPath,
            String expectedFingerprint) {}

    private record DeletionOperation(UUID operationId, DestructiveOperationState state) {}
}
