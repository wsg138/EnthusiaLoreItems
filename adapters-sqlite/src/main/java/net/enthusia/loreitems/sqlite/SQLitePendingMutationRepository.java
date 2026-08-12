package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DestructiveOperationStore;
import net.enthusia.loreitems.application.DestructiveOperationStoreProvider;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.application.PendingMutationRepository;
import net.enthusia.loreitems.application.PendingMutationReviewStore;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.application.TemplateUpdateExecutionStore;
import net.enthusia.loreitems.application.TemplateUpdatePrepareResult;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.PendingMutationState;

public final class SQLitePendingMutationRepository
        implements PendingMutationRepository,
                PendingMutationReviewStore,
                TemplateUpdateExecutionStore,
                DestructiveOperationStoreProvider {
    private static final String NOW_ARGUMENT = "now";
    private static final String MUTATION_TYPE_ARGUMENT = "mutationType";
    private static final long MIN_LEASE_MILLIS = 1L;
    private static final int SINGLE_UPDATED_ROW = 1;

    private final SQLiteStorageRuntime storage;
    private final SQLiteTemplateUpdateExecutionStore templateUpdates;
    private final SQLiteDestructiveOperationStore destructiveOperations;
    private final SQLitePendingMutationReviewStore reviewStore;

    public SQLitePendingMutationRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.templateUpdates = new SQLiteTemplateUpdateExecutionStore(storage);
        this.destructiveOperations = new SQLiteDestructiveOperationStore(storage);
        this.reviewStore = new SQLitePendingMutationReviewStore(storage);
    }

    @Override
    public DestructiveOperationStore destructiveOperationStore() {
        return destructiveOperations;
    }

    @Override
    public CompletionStage<PendingMutationReviewStore.Status> resolve(
            UUID mutationId,
            String expectedMutationType,
            PendingMutationReviewStore.Resolution resolution,
            AuditEventRecord auditEvent,
            Instant now) {
        return reviewStore.resolve(
                mutationId, expectedMutationType, resolution, auditEvent, now);
    }

    @Override
    public CompletionStage<Void> insert(PendingMutationRecord mutation) {
        Objects.requireNonNull(mutation, "mutation");
        if (mutation.state() != PendingMutationState.PENDING
                || mutation.claimToken() != null
                || mutation.attemptCount() != 0) {
            throw new IllegalArgumentException("New mutations must be unclaimed PENDING records");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pending_mutations(mutation_id, mutation_type, definition_id, "
                            + "instance_id, desired_revision, state, claim_token, "
                            + "claim_expires_at, attempt_count, next_attempt_at, created_at, "
                            + "updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, 0, ?, ?, ?)")) {
                statement.setString(1, mutation.mutationId().toString());
                statement.setString(2, mutation.mutationType());
                setNullableString(statement, 3, mutation.definitionId() == null
                        ? null
                        : mutation.definitionId().value().toString());
                setNullableString(statement, 4, mutation.instanceId() == null
                        ? null
                        : mutation.instanceId().value().toString());
                setNullableLong(statement, 5, mutation.desiredRevision());
                statement.setString(6, PendingMutationState.PENDING.name());
                setNullableLong(statement, 7, mutation.nextAttemptAtEpochMillis());
                statement.setLong(8, mutation.createdAtEpochMillis());
                statement.setLong(9, mutation.updatedAtEpochMillis());
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletionStage<Page<PendingMutationRecord>> claimPending(
            String mutationType,
            String claimToken,
            Instant now,
            Duration lease,
            int limit) {
        String normalizedType = normalizeMutationType(mutationType);
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(now, NOW_ARGUMENT);
        Objects.requireNonNull(lease, "lease");
        String normalizedToken = claimToken.strip();
        if (normalizedToken.isEmpty()) {
            throw new IllegalArgumentException("claimToken must not be blank");
        }
        requireBoundedLimit(limit);
        long leaseMillis = lease.toMillis();
        if (leaseMillis < MIN_LEASE_MILLIS) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> claimPending(
                        transaction,
                        normalizedType,
                        normalizedToken,
                        now.toEpochMilli(),
                        leaseMillis,
                        limit)));
    }

    @Override
    public CompletionStage<Boolean> transitionClaimed(
            UUID mutationId,
            PendingMutationState expected,
            PendingMutationState target,
            String claimToken,
            Instant now) {
        Objects.requireNonNull(mutationId, "mutationId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(now, NOW_ARGUMENT);
        expected.transitionTo(target);
        boolean clearClaim = target.terminal()
                || target == PendingMutationState.REVIEW_REQUIRED
                || target == PendingMutationState.PENDING;
        String sql = target == PendingMutationState.PENDING
                ? "UPDATE pending_mutations SET state = ?, claim_token = NULL, "
                        + "claim_expires_at = NULL, next_attempt_at = ?, updated_at = ? "
                        + "WHERE mutation_id = ? AND state = ? AND claim_token = ? "
                        + "AND claim_expires_at > ?"
                : clearClaim
                        ? "UPDATE pending_mutations SET state = ?, claim_token = NULL, "
                                + "claim_expires_at = NULL, updated_at = ? "
                                + "WHERE mutation_id = ? AND state = ? AND claim_token = ? "
                                + "AND claim_expires_at > ?"
                        : "UPDATE pending_mutations SET state = ?, updated_at = ? "
                                + "WHERE mutation_id = ? AND state = ? AND claim_token = ? "
                                + "AND claim_expires_at > ?";
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, target.name());
                if (target == PendingMutationState.PENDING) {
                    statement.setLong(index++, now.toEpochMilli());
                }
                statement.setLong(index++, now.toEpochMilli());
                statement.setString(index++, mutationId.toString());
                statement.setString(index++, expected.name());
                statement.setString(index++, claimToken);
                statement.setLong(index, now.toEpochMilli());
                return statement.executeUpdate() == SINGLE_UPDATED_ROW;
            }
        });
    }

    @Override
    public CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit) {
        Objects.requireNonNull(now, NOW_ARGUMENT);
        requireBoundedLimit(limit);
        long nowMillis = now.toEpochMilli();
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pending_mutations SET state = 'REVIEW_REQUIRED', claim_token = NULL, "
                            + "claim_expires_at = NULL, updated_at = ? "
                            + "WHERE rowid IN (SELECT rowid FROM pending_mutations "
                            + "WHERE state IN ('CLAIMED', 'APPLIED', 'VERIFIED') "
                            + "AND claim_expires_at <= ? "
                            + "ORDER BY claim_expires_at, mutation_id LIMIT ?)")) {
                statement.setLong(1, nowMillis);
                statement.setLong(2, nowMillis);
                statement.setInt(3, limit);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletionStage<Page<PendingMutationRecord>> listNonTerminal(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> listNonTerminal(connection, null, request));
    }

    @Override
    public CompletionStage<Page<PendingMutationRecord>> listNonTerminal(
            String mutationType, PageRequest request) {
        String normalizedType = normalizeMutationType(mutationType);
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> listNonTerminal(connection, normalizedType, request));
    }

    @Override
    public CompletionStage<TemplateUpdatePrepareResult> prepareTemplateUpdate(
            LoreItemIdentity observedIdentity,
            String claimToken,
            Instant now,
            Duration lease) {
        return templateUpdates.prepareTemplateUpdate(observedIdentity, claimToken, now, lease);
    }

    @Override
    public CompletionStage<Boolean> releaseTemplateUpdate(
            PreparedTemplateUpdate update,
            String reason,
            Instant now) {
        return templateUpdates.releaseTemplateUpdate(update, reason, now);
    }

    @Override
    public CompletionStage<Boolean> completeTemplateUpdate(
            PreparedTemplateUpdate update,
            String beforeFingerprint,
            String afterFingerprint,
            Instant now) {
        return templateUpdates.completeTemplateUpdate(
                update, beforeFingerprint, afterFingerprint, now);
    }

    @Override
    public CompletionStage<Boolean> requireTemplateUpdateReview(
            PreparedTemplateUpdate update,
            String reason,
            String beforeFingerprint,
            String afterFingerprint,
            Instant now) {
        return templateUpdates.requireTemplateUpdateReview(
                update, reason, beforeFingerprint, afterFingerprint, now);
    }

    private static Page<PendingMutationRecord> listNonTerminal(
            Connection connection,
            String mutationType,
            PageRequest request) throws SQLException {
        List<PendingMutationRecord> records = new ArrayList<>();
        String sql = mutationType == null
                ? "SELECT * FROM pending_mutations "
                        + "WHERE state NOT IN ('COMPLETED', 'CANCELLED') "
                        + "ORDER BY created_at, mutation_id LIMIT ? OFFSET ?"
                : "SELECT * FROM pending_mutations WHERE mutation_type = ? "
                        + "AND state NOT IN ('COMPLETED', 'CANCELLED') "
                        + "ORDER BY created_at, mutation_id LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (mutationType != null) {
                statement.setString(index++, mutationType);
            }
            statement.setInt(index++, request.limit() + 1);
            statement.setInt(index, request.offset());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(readRecord(resultSet));
                }
            }
        }
        boolean hasMore = records.size() > request.limit();
        if (hasMore) {
            records.remove(records.size() - 1);
        }
        return new Page<>(records, request.offset(), request.limit(), hasMore);
    }

    private static void requireBoundedLimit(int limit) {
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
    }

    private static String normalizeMutationType(String mutationType) {
        Objects.requireNonNull(mutationType, MUTATION_TYPE_ARGUMENT);
        String normalized = mutationType.strip();
        if (normalized.isEmpty()
                || normalized.length() > PendingMutationRecord.MAX_MUTATION_TYPE_LENGTH) {
            throw new IllegalArgumentException("Invalid mutation type");
        }
        return normalized;
    }

    private static Page<PendingMutationRecord> claimPending(
            Connection connection,
            String mutationType,
            String claimToken,
            long now,
            long leaseMillis,
            int limit) throws SQLException {
        List<PendingMutationRecord> candidates =
                findClaimCandidates(connection, mutationType, now, limit);
        boolean hasMore = trimLookahead(candidates, limit);
        List<PendingMutationRecord> claimed =
                claimCandidates(connection, claimToken, now, leaseMillis, candidates);
        return new Page<>(claimed, 0, limit, hasMore);
    }

    private static List<PendingMutationRecord> findClaimCandidates(
            Connection connection,
            String mutationType,
            long now,
            int limit) throws SQLException {
        List<PendingMutationRecord> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM pending_mutations WHERE mutation_type = ? AND state = 'PENDING' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "ORDER BY created_at, mutation_id LIMIT ?")) {
            statement.setString(1, mutationType);
            statement.setLong(2, now);
            statement.setInt(3, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(readRecord(resultSet));
                }
            }
        }
        return candidates;
    }

    private static boolean trimLookahead(List<?> values, int limit) {
        boolean hasMore = values.size() > limit;
        if (hasMore) {
            values.remove(values.size() - 1);
        }
        return hasMore;
    }

    private static List<PendingMutationRecord> claimCandidates(
            Connection connection,
            String claimToken,
            long now,
            long leaseMillis,
            List<PendingMutationRecord> candidates) throws SQLException {
        List<PendingMutationRecord> claimed = new ArrayList<>(candidates.size());
        long expiresAt = Math.addExact(now, leaseMillis);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE pending_mutations SET state = 'CLAIMED', claim_token = ?, "
                        + "claim_expires_at = ?, attempt_count = attempt_count + 1, updated_at = ? "
                        + "WHERE mutation_id = ? AND state = 'PENDING'")) {
            for (PendingMutationRecord candidate : candidates) {
                bindClaim(update, claimToken, expiresAt, now, candidate);
                if (update.executeUpdate() == SINGLE_UPDATED_ROW) {
                    claimed.add(claimedRecord(candidate, claimToken, expiresAt, now));
                }
            }
        }
        return claimed;
    }

    private static void bindClaim(
            PreparedStatement update,
            String claimToken,
            long expiresAt,
            long now,
            PendingMutationRecord candidate) throws SQLException {
        update.setString(1, claimToken);
        update.setLong(2, expiresAt);
        update.setLong(3, now);
        update.setString(4, candidate.mutationId().toString());
    }

    private static PendingMutationRecord claimedRecord(
            PendingMutationRecord candidate, String claimToken, long expiresAt, long now) {
        return new PendingMutationRecord(
                candidate.mutationId(),
                candidate.mutationType(),
                candidate.definitionId(),
                candidate.instanceId(),
                candidate.desiredRevision(),
                PendingMutationState.CLAIMED,
                claimToken,
                expiresAt,
                candidate.attemptCount() + 1,
                candidate.nextAttemptAtEpochMillis(),
                candidate.createdAtEpochMillis(),
                now);
    }

    private static PendingMutationRecord readRecord(ResultSet resultSet) throws SQLException {
        String definitionValue = resultSet.getString("definition_id");
        String instanceValue = resultSet.getString("instance_id");
        long desiredRevisionValue = resultSet.getLong("desired_revision");
        Long desiredRevision = resultSet.wasNull() ? null : desiredRevisionValue;
        long expiresValue = resultSet.getLong("claim_expires_at");
        Long claimExpiresAt = resultSet.wasNull() ? null : expiresValue;
        long nextAttemptValue = resultSet.getLong("next_attempt_at");
        Long nextAttemptAt = resultSet.wasNull() ? null : nextAttemptValue;
        return new PendingMutationRecord(
                UUID.fromString(resultSet.getString("mutation_id")),
                resultSet.getString("mutation_type"),
                definitionValue == null
                        ? null
                        : new LoreDefinitionId(UUID.fromString(definitionValue)),
                instanceValue == null
                        ? null
                        : new LoreInstanceId(UUID.fromString(instanceValue)),
                desiredRevision,
                PendingMutationState.valueOf(resultSet.getString("state")),
                resultSet.getString("claim_token"),
                claimExpiresAt,
                resultSet.getInt("attempt_count"),
                nextAttemptAt,
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
