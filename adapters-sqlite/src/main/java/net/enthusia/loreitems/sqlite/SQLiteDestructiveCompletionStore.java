package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.PreparedRemoval;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.LoreInstanceId;

final class SQLiteDestructiveCompletionStore {
    private static final int SINGLE_ROW = 1;
    private static final String NOW_PARAMETER = "now";

    private final SQLiteStorageRuntime storage;

    SQLiteDestructiveCompletionStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    CompletionStage<Boolean> releaseRemoval(
            PreparedRemoval removal,
            String reason,
            Instant now) {
        Objects.requireNonNull(removal, "removal");
        String normalizedReason = normalizeDetail(reason, "reason");
        Objects.requireNonNull(now, NOW_PARAMETER);
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
        Objects.requireNonNull(now, NOW_PARAMETER);
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
        validateReviewArguments(removal, effectState, now);
        String normalizedDetail = normalizeDetail(detail, "detail");
        String normalizedBefore = normalizeNullable(beforeFingerprint);
        String normalizedAfter = normalizeNullable(afterFingerprint);
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
        Objects.requireNonNull(now, NOW_PARAMETER);
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
        return storage.execute(connection -> moveExpiredClaimsToReview(
                connection, now.toEpochMilli(), limit));
    }

    private static void validateReviewArguments(
            PreparedRemoval removal,
            DestructiveEffectState effectState,
            Instant now) {
        Objects.requireNonNull(removal, "removal");
        Objects.requireNonNull(effectState, "effectState");
        Objects.requireNonNull(now, NOW_PARAMETER);
        if (effectState == DestructiveEffectState.UNKNOWN) {
            throw new IllegalArgumentException(
                    "Review evidence must classify the observed physical effect");
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
            bindClaim(statement, removal, 4);
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
        if (!markTargetCompleted(connection, removal, beforeFingerprint, now)) {
            return false;
        }
        markRemoved(connection, removal.instanceId(), now);
        SQLiteDestructiveControlStore.appendAudit(
                connection,
                removal.operationId(),
                "destructive_target_removed",
                "SYSTEM",
                removedDetail(removal.instanceId(), beforeFingerprint),
                now);
        SQLiteDestructiveControlStore.refreshParentTerminalState(
                connection, removal.operationId(), now);
        return true;
    }

    private static boolean markTargetCompleted(
            Connection connection,
            PreparedRemoval removal,
            String beforeFingerprint,
            long now) throws SQLException {
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
            bindClaim(statement, removal, 3);
            statement.setLong(6, now);
            statement.setString(7, beforeFingerprint);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static boolean requireRemovalReview(
            Connection connection,
            PreparedRemoval removal,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail,
            long now) throws SQLException {
        if (!markReviewRequired(
                connection,
                removal,
                effectState,
                beforeFingerprint,
                afterFingerprint,
                detail,
                now)) {
            return false;
        }
        SQLiteDestructiveControlStore.appendAudit(
                connection,
                removal.operationId(),
                "destructive_target_review_required",
                "SYSTEM",
                reviewDetail(removal.instanceId(), effectState),
                now);
        return true;
    }

    private static boolean markReviewRequired(
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
            bindClaim(statement, removal, 6);
            return statement.executeUpdate() == SINGLE_ROW;
        }
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

    private static void bindClaim(
            PreparedStatement statement,
            PreparedRemoval removal,
            int firstIndex) throws SQLException {
        statement.setString(firstIndex, removal.operationId().toString());
        statement.setString(firstIndex + 1, removal.instanceId().value().toString());
        statement.setString(firstIndex + 2, removal.claimToken());
    }

    private static String normalizeDetail(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 2_000) {
            throw new IllegalArgumentException("Invalid " + name);
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

    private static String removedDetail(
            LoreInstanceId instanceId,
            String beforeFingerprint) {
        return "{\"instanceId\":\"" + instanceId.value()
                + "\",\"beforeFingerprint\":\"" + SQLiteDestructiveControlStore.escapeJson(beforeFingerprint) + "\"}";
    }

    private static String reviewDetail(
            LoreInstanceId instanceId,
            DestructiveEffectState effectState) {
        return "{\"instanceId\":\"" + instanceId.value()
                + "\",\"effectState\":\"" + effectState.name() + "\"}";
    }
}