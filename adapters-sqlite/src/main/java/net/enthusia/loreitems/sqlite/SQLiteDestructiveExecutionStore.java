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
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.DestructiveTargetState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

final class SQLiteDestructiveExecutionStore {
    private static final int SINGLE_ROW = 1;
    private static final long MIN_LEASE_MILLIS = 1L;

    private final SQLiteStorageRuntime storage;

    SQLiteDestructiveExecutionStore(SQLiteStorageRuntime storage) {
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

    CompletionStage<Boolean> releaseRemoval(
            PreparedRemoval removal,
            String reason,
            Instant now) {
        Objects.requireNonNull(removal, "removal");
        String normalizedReason = normalizeDetail(reason, "reason");
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> releaseRemoval(
                        transaction, removal, normalizedReason, now.toEpochMilli())));
    }

    CompletionStage<Boolean> completeRemoval(
            PreparedRemoval removal,
            String beforeFingerprint,
            Instant now) {
        Objects.requireNonNull(removal, "removal");
        String normalizedFingerprint = normalizeDetail(
                beforeFingerprint, "beforeFingerprint");
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> completeRemoval(
                        transaction, removal, normalizedFingerprint, now.toEpochMilli())));
    }

    CompletionStage<Boolean> requireRemovalReview(
            PreparedRemoval removal,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail,
            Instant now) {
        Objects.requireNonNull(removal, "removal");
        Objects.requireNonNull(effectState, "effectState");
        if (effectState == DestructiveEffectState.UNKNOWN) {
            throw new IllegalArgumentException(
                    "Review evidence must classify the observed physical effect");
        }
        String normalizedDetail = normalizeDetail(detail, "detail");
        String normalizedBefore = normalizeNullable(beforeFingerprint);
        String normalizedAfter = normalizeNullable(afterFingerprint);
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> requireRemovalReview(
                        transaction,
                        removal,
                        effectState,
                        normalizedBefore,
                        normalizedAfter,
                        normalizedDetail,
                        now.toEpochMilli())));
    }

    CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1 || limit > net.enthusia.loreitems.application.PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
        return storage.execute(connection -> moveExpiredClaimsToReview(
                connection, now.toEpochMilli(), limit));
    }

    private static PrepareResult prepareRemoval(
            Connection connection,
            Observation observation,
            String claimToken,
            long now,
            long leaseMillis) throws SQLException {
        Candidate target = findPendingCandidate(connection, observation.identity());
        if (target == null) {
            target = reopenLateTarget(connection, observation, now);
        }
        if (target == null) {
            target = createLateDeletionTarget(connection, observation, now);
        }
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
        return PrepareResult.prepared(new PreparedRemoval(
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
                expiresAt));
    }

    private static Candidate findPendingCandidate(
            Connection connection,
            LoreItemIdentity identity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT target.*, operation.operation_type, operation.state operation_state "
                        + "FROM destructive_targets target "
                        + "JOIN destructive_operations operation "
                        + "ON operation.operation_id = target.operation_id "
                        + "WHERE target.instance_id = ? AND target.definition_id = ? "
                        + "AND target.state = 'PENDING' "
                        + "ORDER BY operation.accepted_at, operation.operation_id LIMIT 1")) {
            statement.setString(1, identity.instanceId().value().toString());
            statement.setString(2, identity.definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readCandidate(resultSet) : null;
            }
        }
    }

    private static Candidate reopenLateTarget(
            Connection connection,
            Observation observation,
            long now) throws SQLException {
        Candidate completed = findCompletedReopenableTarget(connection, observation.identity());
        if (completed == null) {
            return null;
        }
        try (PreparedStatement target = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'PENDING', effect_state = 'UNKNOWN', "
                        + "claim_token = NULL, claim_expires_at = NULL, expected_fingerprint = NULL, "
                        + "before_fingerprint = NULL, after_fingerprint = NULL, last_error = ?, "
                        + "updated_at = ? WHERE operation_id = ? AND instance_id = ? "
                        + "AND state = 'COMPLETED'")) {
            target.setString(1, "A naturally encountered late copy reopened completed purge/delete work.");
            target.setLong(2, now);
            target.setString(3, completed.operationId().toString());
            target.setString(4, completed.instanceId().value().toString());
            if (target.executeUpdate() != SINGLE_ROW) {
                return null;
            }
        }
        reopenParent(connection, completed.operationId(), now);
        SQLiteDestructiveAdministrationStore.appendAudit(
                connection,
                "destructive_operation",
                completed.operationId().toString(),
                "destructive_late_copy_reopened",
                "SYSTEM",
                "{\"instanceId\":\"" + completed.instanceId().value() + "\"}",
                now);
        return findPendingCandidate(connection, observation.identity());
    }

    private static Candidate findCompletedReopenableTarget(
            Connection connection,
            LoreItemIdentity identity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT target.*, operation.operation_type, operation.state operation_state "
                        + "FROM destructive_targets target "
                        + "JOIN destructive_operations operation "
                        + "ON operation.operation_id = target.operation_id "
                        + "WHERE target.instance_id = ? AND target.definition_id = ? "
                        + "AND target.state = 'COMPLETED' "
                        + "AND operation.operation_type IN ('PURGE_DEFINITION', 'DELETE_DEFINITION') "
                        + "AND operation.state <> 'ABORTED' "
                        + "ORDER BY operation.accepted_at DESC, operation.operation_id DESC LIMIT 1")) {
            statement.setString(1, identity.instanceId().value().toString());
            statement.setString(2, identity.definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readCandidate(resultSet) : null;
            }
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
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO destructive_targets(operation_id, instance_id, definition_id, "
                        + "expected_applied_revision, expected_location_type, expected_location_key, "
                        + "expected_container_path, expected_fingerprint, state, effect_state, "
                        + "claim_token, claim_expires_at, attempt_count, before_fingerprint, "
                        + "after_fingerprint, last_error, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'PENDING', 'UNKNOWN', NULL, NULL, 0, "
                        + "NULL, NULL, ?, ?, ?)")) {
            statement.setString(1, deletion.operationId().toString());
            statement.setString(2, observation.identity().instanceId().value().toString());
            statement.setString(3, observation.identity().definitionId().value().toString());
            statement.setLong(4, observation.identity().appliedRevision().value());
            statement.setString(5, observation.locationType());
            statement.setString(6, observation.locationKey());
            SQLiteDestructiveRows.setNullableString(statement, 7, observation.containerPath());
            statement.setString(8, "A late physical copy appeared after full-definition deletion.");
            statement.setLong(9, now);
            statement.setLong(10, now);
            if (statement.executeUpdate() == SINGLE_ROW) {
                incrementTargetCountAndReopen(connection, deletion.operationId(), now);
                SQLiteDestructiveAdministrationStore.appendAudit(
                        connection,
                        "destructive_operation",
                        deletion.operationId().toString(),
                        "destructive_late_delete_target_created",
                        "SYSTEM",
                        "{\"instanceId\":\""
                                + observation.identity().instanceId().value() + "\"}",
                        now);
            }
        }
        return findPendingCandidate(connection, observation.identity());
    }

    private static DeletionOperation findDeletionOperation(
            Connection connection,
            LoreDefinitionId definitionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT operation.operation_id, operation.state "
                        + "FROM destructive_operations operation "
                        + "JOIN deleted_definition_markers marker "
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

    private static void incrementTargetCountAndReopen(
            Connection connection,
            UUID operationId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_operations SET target_count = target_count + 1, "
                        + "state = 'ACTIVE', updated_at = ?, terminal_at = NULL "
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
        if (target.operationType() == DestructiveOperationType.EXACT_INSTANCE_REMOVAL) {
            if (!Objects.equals(target.expectedLocationType(), observation.locationType())
                    || !Objects.equals(target.expectedLocationKey(), observation.locationKey())
                    || !Objects.equals(target.expectedContainerPath(), observation.containerPath())) {
                return "The exact-removal target moved after the confirmation snapshot.";
            }
            if (target.expectedFingerprint() != null
                    && !target.expectedFingerprint().equals(observation.fingerprint())) {
                return "The exact-removal target fingerprint changed after durable preparation.";
            }
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

    private static boolean releaseRemoval(
            Connection connection,
            PreparedRemoval removal,
            String reason,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'PENDING', "
                        + "effect_state = 'NONE_OBSERVED', claim_token = NULL, "
                        + "claim_expires_at = NULL, before_fingerprint = ?, "
                        + "after_fingerprint = NULL, last_error = ?, updated_at = ? "
                        + "WHERE operation_id = ? AND instance_id = ? AND state = 'CLAIMED' "
                        + "AND claim_token = ? AND claim_expires_at > ?")) {
            statement.setString(1, removal.beforeFingerprint());
            statement.setString(2, reason);
            statement.setLong(3, now);
            statement.setString(4, removal.operationId().toString());
            statement.setString(5, removal.instanceId().value().toString());
            statement.setString(6, removal.claimToken());
            statement.setLong(7, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static boolean completeRemoval(
            Connection connection,
            PreparedRemoval removal,
            String beforeFingerprint,
            long now) throws SQLException {
        if (!removal.beforeFingerprint().equals(beforeFingerprint)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'COMPLETED', "
                        + "effect_state = 'REMOVED_OBSERVED', claim_token = NULL, "
                        + "claim_expires_at = NULL, before_fingerprint = ?, "
                        + "after_fingerprint = NULL, last_error = NULL, updated_at = ? "
                        + "WHERE operation_id = ? AND instance_id = ? AND state = 'CLAIMED' "
                        + "AND claim_token = ? AND claim_expires_at > ? "
                        + "AND expected_fingerprint = ?")) {
            statement.setString(1, beforeFingerprint);
            statement.setLong(2, now);
            statement.setString(3, removal.operationId().toString());
            statement.setString(4, removal.instanceId().value().toString());
            statement.setString(5, removal.claimToken());
            statement.setLong(6, now);
            statement.setString(7, beforeFingerprint);
            if (statement.executeUpdate() != SINGLE_ROW) {
                return false;
            }
        }
        markRemoved(connection, removal.instanceId(), now);
        SQLiteDestructiveAdministrationStore.appendAudit(
                connection,
                "destructive_operation",
                removal.operationId().toString(),
                "destructive_target_removed",
                "SYSTEM",
                "{\"instanceId\":\"" + removal.instanceId().value()
                        + "\",\"beforeFingerprint\":\"" + beforeFingerprint + "\"}",
                now);
        SQLiteDestructiveAdministrationStore.refreshParentTerminalState(
                connection, removal.operationId(), now);
        return true;
    }

    private static boolean requireRemovalReview(
            Connection connection,
            PreparedRemoval removal,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'REVIEW_REQUIRED', effect_state = ?, "
                        + "claim_token = NULL, claim_expires_at = NULL, before_fingerprint = ?, "
                        + "after_fingerprint = ?, last_error = ?, updated_at = ? "
                        + "WHERE operation_id = ? AND instance_id = ? AND state = 'CLAIMED' "
                        + "AND claim_token = ?")) {
            statement.setString(1, effectState.name());
            SQLiteDestructiveRows.setNullableString(statement, 2, beforeFingerprint);
            SQLiteDestructiveRows.setNullableString(statement, 3, afterFingerprint);
            statement.setString(4, detail);
            statement.setLong(5, now);
            statement.setString(6, removal.operationId().toString());
            statement.setString(7, removal.instanceId().value().toString());
            statement.setString(8, removal.claimToken());
            if (statement.executeUpdate() != SINGLE_ROW) {
                return false;
            }
        }
        SQLiteDestructiveAdministrationStore.appendAudit(
                connection,
                "destructive_operation",
                removal.operationId().toString(),
                "destructive_target_review_required",
                "SYSTEM",
                "{\"instanceId\":\"" + removal.instanceId().value()
                        + "\",\"effectState\":\"" + effectState.name() + "\"}",
                now);
        return true;
    }

    private static int moveExpiredClaimsToReview(
            Connection connection,
            long now,
            int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE destructive_targets SET state = 'REVIEW_REQUIRED', "
                        + "effect_state = 'AMBIGUOUS', claim_token = NULL, claim_expires_at = NULL, "
                        + "last_error = 'The removal claim expired; physical outcome is unknown.', "
                        + "updated_at = ? WHERE rowid IN (SELECT rowid FROM destructive_targets "
                        + "WHERE state = 'CLAIMED' AND claim_expires_at <= ? "
                        + "ORDER BY claim_expires_at, operation_id, instance_id LIMIT ?)")) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setInt(3, limit);
            return statement.executeUpdate();
        }
    }

    private static void markRemoved(
            Connection connection,
            LoreInstanceId instanceId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE lore_instances SET lifecycle_state = 'REMOVED', terminal_at = ? "
                        + "WHERE instance_id = ? AND lifecycle_state = 'ACTIVE'")) {
            statement.setLong(1, now);
            statement.setString(2, instanceId.value().toString());
            statement.executeUpdate();
        }
    }

    private static Candidate readCandidate(ResultSet resultSet) throws SQLException {
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

    private static String normalizeClaimToken(String value) {
        String normalized = normalizeDetail(value, "claimToken");
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("claimToken is too long");
        }
        return normalized;
    }

    private static String normalizeDetail(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > 2_000) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
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

    private record DeletionOperation(
            UUID operationId,
            DestructiveOperationState state) {}
}