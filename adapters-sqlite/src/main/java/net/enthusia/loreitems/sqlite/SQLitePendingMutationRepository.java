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
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.application.PendingMutationRepository;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.PendingMutationState;

public final class SQLitePendingMutationRepository implements PendingMutationRepository {
    private static final String NOW_ARGUMENT = "now";
    private static final long MIN_LEASE_MILLIS = 1L;
    private static final int SINGLE_UPDATED_ROW = 1;

    private final SQLiteStorageRuntime storage;

    public SQLitePendingMutationRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
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
                            + "instance_id, desired_revision, state, claim_token, claim_expires_at, "
                            + "attempt_count, next_attempt_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, 0, ?, ?, ?)")) {
                statement.setString(1, mutation.mutationId().toString());
                statement.setString(2, mutation.mutationType());
                setNullableString(statement, 3, mutation.definitionId() == null
                        ? null
                        : mutation.definitionId().value().toString());
                setNullableString(statement, 4, mutation.instanceId() == null
                        ? null
                        : mutation.instanceId().value().toString());
                setNullableInteger(statement, 5, mutation.desiredRevision());
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
            String claimToken, Instant now, Duration lease, int limit) {
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(now, NOW_ARGUMENT);
        Objects.requireNonNull(lease, "lease");
        String normalizedToken = claimToken.strip();
        if (normalizedToken.isEmpty()) {
            throw new IllegalArgumentException("claimToken must not be blank");
        }
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
        long leaseMillis = lease.toMillis();
        if (leaseMillis < MIN_LEASE_MILLIS) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> claimPending(
                        transaction,
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
        boolean clearClaim = target == PendingMutationState.COMPLETED
                || target == PendingMutationState.REVIEW_REQUIRED;
        String sql = clearClaim
                ? "UPDATE pending_mutations SET state = ?, claim_token = NULL, "
                        + "claim_expires_at = NULL, updated_at = ? "
                        + "WHERE mutation_id = ? AND state = ? AND claim_token = ? "
                        + "AND claim_expires_at > ?"
                : "UPDATE pending_mutations SET state = ?, updated_at = ? "
                        + "WHERE mutation_id = ? AND state = ? AND claim_token = ? "
                        + "AND claim_expires_at > ?";
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, target.name());
                statement.setLong(2, now.toEpochMilli());
                statement.setString(3, mutationId.toString());
                statement.setString(4, expected.name());
                statement.setString(5, claimToken);
                statement.setLong(6, now.toEpochMilli());
                return statement.executeUpdate() == SINGLE_UPDATED_ROW;
            }
        });
    }

    @Override
    public CompletionStage<Integer> moveExpiredClaimsToReview(Instant now) {
        Objects.requireNonNull(now, NOW_ARGUMENT);
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pending_mutations SET state = 'REVIEW_REQUIRED', claim_token = NULL, "
                            + "claim_expires_at = NULL, updated_at = ? "
                            + "WHERE state IN ('CLAIMED', 'APPLIED', 'VERIFIED') "
                            + "AND claim_expires_at <= ?")) {
                statement.setLong(1, now.toEpochMilli());
                statement.setLong(2, now.toEpochMilli());
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletionStage<Page<PendingMutationRecord>> listNonTerminal(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            List<PendingMutationRecord> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pending_mutations WHERE state <> 'COMPLETED' "
                            + "ORDER BY created_at, mutation_id LIMIT ? OFFSET ?")) {
                statement.setInt(1, request.limit() + 1);
                statement.setInt(2, request.offset());
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
        });
    }

    private static Page<PendingMutationRecord> claimPending(
            Connection connection,
            String claimToken,
            long now,
            long leaseMillis,
            int limit) throws SQLException {
        List<PendingMutationRecord> candidates = findClaimCandidates(connection, now, limit);
        boolean hasMore = trimLookahead(candidates, limit);
        List<PendingMutationRecord> claimed =
                claimCandidates(connection, claimToken, now, leaseMillis, candidates);
        return new Page<>(claimed, 0, limit, hasMore);
    }

    private static List<PendingMutationRecord> findClaimCandidates(
            Connection connection, long now, int limit) throws SQLException {
        List<PendingMutationRecord> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM pending_mutations WHERE state = 'PENDING' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "ORDER BY created_at, mutation_id LIMIT ?")) {
            statement.setLong(1, now);
            statement.setInt(2, limit + 1);
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
        int desiredRevisionValue = resultSet.getInt("desired_revision");
        Integer desiredRevision = resultSet.wasNull() ? null : desiredRevisionValue;
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

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
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
