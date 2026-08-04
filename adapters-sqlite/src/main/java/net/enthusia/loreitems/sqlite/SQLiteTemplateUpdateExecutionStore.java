package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.application.TemplateUpdateExecutionStore;
import net.enthusia.loreitems.application.TemplateUpdatePrepareResult;
import net.enthusia.loreitems.domain.PendingMutationState;

public final class SQLiteTemplateUpdateExecutionStore implements TemplateUpdateExecutionStore {
    private static final int SINGLE_UPDATED_ROW = 1;
    private static final long MIN_LEASE_MILLIS = 1L;
    private static final String NOW_PARAMETER = "now";
    private static final String TEMPLATE_UPDATE_MUTATION_FENCE =
            "WHERE mutation_id = ? AND mutation_type = 'TEMPLATE_UPDATE' ";

    private final SQLiteStorageRuntime storage;

    public SQLiteTemplateUpdateExecutionStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<TemplateUpdatePrepareResult> prepareTemplateUpdate(
            LoreItemIdentity observedIdentity,
            String claimToken,
            Instant now,
            Duration lease) {
        Objects.requireNonNull(observedIdentity, "observedIdentity");
        String normalizedToken = normalizeClaimToken(claimToken);
        Objects.requireNonNull(now, NOW_PARAMETER);
        Objects.requireNonNull(lease, "lease");
        long leaseMillis = lease.toMillis();
        if (leaseMillis < MIN_LEASE_MILLIS) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> SQLiteTemplateUpdatePreparation.prepare(
                        transaction,
                        observedIdentity,
                        normalizedToken,
                        now.toEpochMilli(),
                        leaseMillis)));
    }

    @Override
    public CompletionStage<Boolean> releaseTemplateUpdate(
            PreparedTemplateUpdate update,
            String reason,
            Instant now) {
        Objects.requireNonNull(update, "update");
        String normalizedReason = normalizeReason(reason);
        Objects.requireNonNull(now, NOW_PARAMETER);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> releaseInTransaction(
                        transaction, update, normalizedReason, now.toEpochMilli())));
    }

    @Override
    public CompletionStage<Boolean> completeTemplateUpdate(
            PreparedTemplateUpdate update,
            String beforeFingerprint,
            String afterFingerprint,
            Instant now) {
        Objects.requireNonNull(update, "update");
        String normalizedBefore = normalizeFingerprint(beforeFingerprint, "beforeFingerprint");
        String normalizedAfter = normalizeFingerprint(afterFingerprint, "afterFingerprint");
        Objects.requireNonNull(now, NOW_PARAMETER);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> completeInTransaction(
                        transaction,
                        update,
                        normalizedBefore,
                        normalizedAfter,
                        now.toEpochMilli())));
    }

    @Override
    public CompletionStage<Boolean> requireTemplateUpdateReview(
            PreparedTemplateUpdate update,
            String reason,
            String beforeFingerprint,
            String afterFingerprint,
            Instant now) {
        Objects.requireNonNull(update, "update");
        String normalizedReason = normalizeReason(reason);
        String normalizedBefore = normalizeOptionalFingerprint(
                beforeFingerprint, "beforeFingerprint");
        String normalizedAfter = normalizeOptionalFingerprint(
                afterFingerprint, "afterFingerprint");
        Objects.requireNonNull(now, NOW_PARAMETER);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> requireReviewInTransaction(
                        transaction,
                        update,
                        normalizedReason,
                        normalizedBefore,
                        normalizedAfter,
                        now.toEpochMilli())));
    }

    private static boolean releaseInTransaction(
            Connection connection,
            PreparedTemplateUpdate update,
            String reason,
            long now) throws SQLException {
        if (!claimMatches(connection, update, now)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_mutations SET state = 'PENDING', claim_token = NULL, "
                        + "claim_expires_at = NULL, next_attempt_at = ?, updated_at = ? "
                        + TEMPLATE_UPDATE_MUTATION_FENCE
                        + "AND state = 'CLAIMED' AND claim_token = ? "
                        + "AND claim_expires_at > ?")) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, update.mutationId().toString());
            statement.setString(4, update.claimToken());
            statement.setLong(5, now);
            requireSingleUpdate(statement, "Template-update release claim was lost");
        }
        SQLiteTemplateUpdateAudit.appendRelease(connection, update, reason, now);
        return true;
    }

    private static boolean completeInTransaction(
            Connection connection,
            PreparedTemplateUpdate update,
            String beforeFingerprint,
            String afterFingerprint,
            long now) throws SQLException {
        ClaimedTemplateUpdate claimed = findClaimed(connection, update, now);
        if (claimed == null || !claimMatchesPrepared(claimed, update)) {
            return false;
        }
        advanceMutationRetainingClaim(
                connection,
                update,
                PendingMutationState.CLAIMED,
                PendingMutationState.APPLIED,
                now);
        advanceInstance(connection, update, claimed);
        advanceMutationRetainingClaim(
                connection,
                update,
                PendingMutationState.APPLIED,
                PendingMutationState.VERIFIED,
                now);
        SQLiteTemplateUpdateAudit.appendCompletion(
                connection, update, beforeFingerprint, afterFingerprint, now);
        advanceMutationAndClearClaim(
                connection,
                update,
                PendingMutationState.VERIFIED,
                PendingMutationState.COMPLETED,
                now);
        return true;
    }

    private static boolean requireReviewInTransaction(
            Connection connection,
            PreparedTemplateUpdate update,
            String reason,
            String beforeFingerprint,
            String afterFingerprint,
            long now) throws SQLException {
        if (!claimMatches(connection, update, now)) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_mutations SET state = 'REVIEW_REQUIRED', "
                        + "claim_token = NULL, claim_expires_at = NULL, updated_at = ? "
                        + TEMPLATE_UPDATE_MUTATION_FENCE
                        + "AND state = 'CLAIMED' AND claim_token = ? "
                        + "AND claim_expires_at > ?")) {
            statement.setLong(1, now);
            statement.setString(2, update.mutationId().toString());
            statement.setString(3, update.claimToken());
            statement.setLong(4, now);
            requireSingleUpdate(statement, "Template-update review claim was lost");
        }
        SQLiteTemplateUpdateAudit.appendClaimedReview(
                connection,
                update,
                reason,
                beforeFingerprint,
                afterFingerprint,
                now);
        return true;
    }

    private static boolean claimMatches(
            Connection connection,
            PreparedTemplateUpdate update,
            long now) throws SQLException {
        ClaimedTemplateUpdate claimed = findClaimed(connection, update, now);
        return claimed != null && claimMatchesPrepared(claimed, update);
    }

    private static ClaimedTemplateUpdate findClaimed(
            Connection connection,
            PreparedTemplateUpdate update,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pm.definition_id, pm.instance_id, pm.desired_revision, "
                        + "li.applied_revision, li.desired_revision AS instance_desired_revision, "
                        + "li.lifecycle_state FROM pending_mutations pm "
                        + "JOIN lore_instances li ON li.instance_id = pm.instance_id "
                        + "AND li.definition_id = pm.definition_id "
                        + "WHERE pm.mutation_id = ? AND pm.mutation_type = 'TEMPLATE_UPDATE' "
                        + "AND pm.state = 'CLAIMED' AND pm.claim_token = ? "
                        + "AND pm.claim_expires_at > ?")) {
            statement.setString(1, update.mutationId().toString());
            statement.setString(2, update.claimToken());
            statement.setLong(3, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ClaimedTemplateUpdate(
                        resultSet.getString("definition_id"),
                        resultSet.getString("instance_id"),
                        resultSet.getLong("desired_revision"),
                        resultSet.getLong("applied_revision"),
                        resultSet.getLong("instance_desired_revision"),
                        resultSet.getString("lifecycle_state"));
            }
        }
    }

    private static boolean claimMatchesPrepared(
            ClaimedTemplateUpdate claimed,
            PreparedTemplateUpdate update) {
        long observedRevision = update.observedIdentity().appliedRevision().value();
        long targetRevision = update.targetIdentity().appliedRevision().value();
        return claimed.definitionId().equals(
                        update.targetIdentity().definitionId().value().toString())
                && claimed.instanceId().equals(
                        update.targetIdentity().instanceId().value().toString())
                && claimed.mutationDesiredRevision() == targetRevision
                && claimed.instanceDesiredRevision() == targetRevision
                && "ACTIVE".equals(claimed.lifecycleState())
                && (claimed.instanceAppliedRevision() == observedRevision
                        || claimed.instanceAppliedRevision() == targetRevision);
    }

    private static void advanceInstance(
            Connection connection,
            PreparedTemplateUpdate update,
            ClaimedTemplateUpdate claimed) throws SQLException {
        long targetRevision = update.targetIdentity().appliedRevision().value();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE lore_instances SET applied_revision = ? "
                        + "WHERE instance_id = ? AND definition_id = ? "
                        + "AND lifecycle_state = 'ACTIVE' AND desired_revision = ? "
                        + "AND applied_revision = ?")) {
            statement.setLong(1, targetRevision);
            statement.setString(2, update.targetIdentity().instanceId().value().toString());
            statement.setString(3, update.targetIdentity().definitionId().value().toString());
            statement.setLong(4, targetRevision);
            statement.setLong(5, claimed.instanceAppliedRevision());
            requireSingleUpdate(statement, "Template-update instance revision fence was lost");
        }
    }

    private static void advanceMutationRetainingClaim(
            Connection connection,
            PreparedTemplateUpdate update,
            PendingMutationState expected,
            PendingMutationState target,
            long now) throws SQLException {
        expected.transitionTo(target);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_mutations SET state = ?, updated_at = ? "
                        + TEMPLATE_UPDATE_MUTATION_FENCE
                        + "AND state = ? AND claim_token = ? AND claim_expires_at > ?")) {
            bindMutationTransition(statement, update, expected, target, now);
            requireSingleUpdate(statement, "Template-update mutation state fence was lost");
        }
    }

    private static void advanceMutationAndClearClaim(
            Connection connection,
            PreparedTemplateUpdate update,
            PendingMutationState expected,
            PendingMutationState target,
            long now) throws SQLException {
        expected.transitionTo(target);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_mutations SET state = ?, claim_token = NULL, "
                        + "claim_expires_at = NULL, updated_at = ? "
                        + TEMPLATE_UPDATE_MUTATION_FENCE
                        + "AND state = ? AND claim_token = ? AND claim_expires_at > ?")) {
            bindMutationTransition(statement, update, expected, target, now);
            requireSingleUpdate(statement, "Template-update mutation state fence was lost");
        }
    }

    private static void bindMutationTransition(
            PreparedStatement statement,
            PreparedTemplateUpdate update,
            PendingMutationState expected,
            PendingMutationState target,
            long now) throws SQLException {
        statement.setString(1, target.name());
        statement.setLong(2, now);
        statement.setString(3, update.mutationId().toString());
        statement.setString(4, expected.name());
        statement.setString(5, update.claimToken());
        statement.setLong(6, now);
    }

    private static void requireSingleUpdate(PreparedStatement statement, String message)
            throws SQLException {
        if (statement.executeUpdate() != SINGLE_UPDATED_ROW) {
            throw new SQLException(message);
        }
    }

    private static String normalizeClaimToken(String claimToken) {
        Objects.requireNonNull(claimToken, "claimToken");
        String normalized = claimToken.strip();
        if (normalized.isEmpty()
                || normalized.length() > PreparedTemplateUpdate.MAX_CLAIM_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid claim token");
        }
        return normalized;
    }

    private static String normalizeReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.length() > 4_000) {
            throw new IllegalArgumentException("Invalid template-update reason");
        }
        return normalized;
    }

    private static String normalizeFingerprint(String fingerprint, String name) {
        Objects.requireNonNull(fingerprint, name);
        String normalized = fingerprint.strip();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return normalized;
    }

    private static String normalizeOptionalFingerprint(String fingerprint, String name) {
        return fingerprint == null ? null : normalizeFingerprint(fingerprint, name);
    }

    private record ClaimedTemplateUpdate(
            String definitionId,
            String instanceId,
            long mutationDesiredRevision,
            long instanceAppliedRevision,
            long instanceDesiredRevision,
            String lifecycleState) {
        private ClaimedTemplateUpdate {
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(lifecycleState, "lifecycleState");
        }
    }
}
