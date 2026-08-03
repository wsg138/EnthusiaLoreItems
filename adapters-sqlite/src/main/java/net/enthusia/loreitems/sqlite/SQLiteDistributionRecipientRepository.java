package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.CampaignRecipientCounts;
import net.enthusia.loreitems.application.DistributionRecipientRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.LoreInstanceId;

public final class SQLiteDistributionRecipientRepository
        implements DistributionRecipientRepository {
    private final SQLiteStorageRuntime storage;

    public SQLiteDistributionRecipientRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Void> insertBatch(
            UUID campaignId, List<CampaignRecipient> recipients) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(recipients, "recipients");
        List<CampaignRecipient> snapshot = List.copyOf(recipients);
        if (snapshot.isEmpty() || snapshot.size() > MAX_INSERT_BATCH) {
            throw new IllegalArgumentException(
                    "Recipient batch must contain between 1 and " + MAX_INSERT_BATCH + " entries");
        }
        for (CampaignRecipient recipient : snapshot) {
            validateInitialRecipient(campaignId, recipient);
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> {
                    requireDraftCampaign(transaction, campaignId);
                    insertBatch(transaction, snapshot);
                    return null;
                }));
    }

    @Override
    public CompletionStage<Optional<CampaignRecipient>> find(
            UUID campaignId, CampaignRecipientKey recipientKey) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(recipientKey, "recipientKey");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE campaign_id = ? AND recipient_key = ?")) {
                statement.setString(1, campaignId.toString());
                statement.setString(2, recipientKey.value());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(readRecipient(resultSet))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> listByCampaign(
            UUID campaignId, PageRequest request) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE campaign_id = ? "
                            + "ORDER BY snapshot_index, recipient_key LIMIT ? OFFSET ?")) {
                statement.setString(1, campaignId.toString());
                statement.setInt(2, request.limit() + 1);
                statement.setInt(3, request.offset());
                return readPage(statement, request);
            }
        });
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> listByCampaignAndState(
            UUID campaignId, CampaignRecipientState state, PageRequest request) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE campaign_id = ? AND state = ? "
                            + "ORDER BY snapshot_index, recipient_key LIMIT ? OFFSET ?")) {
                statement.setString(1, campaignId.toString());
                statement.setString(2, state.name());
                statement.setInt(3, request.limit() + 1);
                statement.setInt(4, request.offset());
                return readPage(statement, request);
            }
        });
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> listUnresolvedByKey(
            CampaignRecipientKey recipientKey, PageRequest request) {
        Objects.requireNonNull(recipientKey, "recipientKey");
        Objects.requireNonNull(request, "request");
        if (!recipientKey.unresolvedNameKey()) {
            throw new IllegalArgumentException("Unresolved lookup requires a name recipient key");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE recipient_key = ? AND state = 'PENDING_NAME' "
                            + "ORDER BY updated_at, campaign_id LIMIT ? OFFSET ?")) {
                statement.setString(1, recipientKey.value());
                statement.setInt(2, request.limit() + 1);
                statement.setInt(3, request.offset());
                return readPage(statement, request);
            }
        });
    }

    @Override
    public CompletionStage<CampaignRecipientCounts> countByState(UUID campaignId) {
        Objects.requireNonNull(campaignId, "campaignId");
        return storage.execute(connection -> {
            Map<CampaignRecipientState, Long> counts =
                    new EnumMap<>(CampaignRecipientState.class);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state, COUNT(*) AS state_count FROM distribution_recipients "
                            + "WHERE campaign_id = ? GROUP BY state")) {
                statement.setString(1, campaignId.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        counts.put(
                                CampaignRecipientState.valueOf(resultSet.getString("state")),
                                resultSet.getLong("state_count"));
                    }
                }
            }
            return new CampaignRecipientCounts(
                    count(counts, CampaignRecipientState.PENDING_NAME),
                    count(counts, CampaignRecipientState.PENDING_OFFLINE),
                    count(counts, CampaignRecipientState.PENDING_SPACE),
                    count(counts, CampaignRecipientState.RESERVED),
                    count(counts, CampaignRecipientState.DELIVERED),
                    count(counts, CampaignRecipientState.CANCELLED),
                    count(counts, CampaignRecipientState.REVIEW_REQUIRED));
        });
    }

    @Override
    public CompletionStage<Boolean> bindUnresolvedName(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            UUID playerId,
            Instant now) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(recipientKey, "recipientKey");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(now, "now");
        if (!recipientKey.unresolvedNameKey()) {
            throw new IllegalArgumentException("Name binding requires an unresolved-name key");
        }
        long nowMillis = requireNonNegative(now, "now");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET player_id = ?, "
                            + "state = 'PENDING_OFFLINE', updated_at = ? "
                            + "WHERE campaign_id = ? AND recipient_key = ? "
                            + "AND state = 'PENDING_NAME' AND player_id IS NULL "
                            + "AND updated_at <= ? "
                            + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                            + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
                            + "AND campaign.state IN ('DRAFT', 'ACTIVE', 'PAUSED')) "
                            + "AND NOT EXISTS (SELECT 1 FROM distribution_recipients other "
                            + "WHERE other.campaign_id = distribution_recipients.campaign_id "
                            + "AND other.player_id = ? "
                            + "AND other.recipient_key <> distribution_recipients.recipient_key)")) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, nowMillis);
                statement.setString(3, campaignId.toString());
                statement.setString(4, recipientKey.value());
                statement.setLong(5, nowMillis);
                statement.setString(6, playerId.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> claimPending(
            UUID campaignId,
            String claimToken,
            Instant now,
            Duration lease,
            int limit) {
        Objects.requireNonNull(campaignId, "campaignId");
        String normalizedToken = normalizeClaimToken(claimToken);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
        long nowMillis = requireNonNegative(now, "now");
        long leaseMillis = lease.toMillis();
        if (leaseMillis < 1L) {
            throw new IllegalArgumentException("lease must be positive");
        }
        long expiresAt = Math.addExact(nowMillis, leaseMillis);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> claimPending(
                        transaction,
                        campaignId,
                        normalizedToken,
                        nowMillis,
                        expiresAt,
                        limit)));
    }

    @Override
    public CompletionStage<Boolean> releaseClaim(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            CampaignRecipientState targetPendingState,
            String claimToken,
            Instant now,
            Instant nextAttemptAt) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(recipientKey, "recipientKey");
        Objects.requireNonNull(targetPendingState, "targetPendingState");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        CampaignRecipientState.RESERVED.transitionTo(targetPendingState);
        if (!targetPendingState.claimable()) {
            throw new IllegalArgumentException(
                    "Released campaign claims must return to an offline or full-inventory state");
        }
        String normalizedToken = normalizeClaimToken(claimToken);
        long nowMillis = requireNonNegative(now, "now");
        long nextAttemptMillis = requireNonNegative(nextAttemptAt, "nextAttemptAt");
        if (nextAttemptMillis < nowMillis) {
            throw new IllegalArgumentException("nextAttemptAt must not precede now");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET state = ?, claim_token = NULL, "
                            + "claim_expires_at = NULL, next_attempt_at = ?, updated_at = ? "
                            + "WHERE campaign_id = ? AND recipient_key = ? "
                            + "AND state = 'RESERVED' AND claim_token = ? "
                            + "AND claim_expires_at > ? AND instance_id IS NULL "
                            + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                            + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
                            + "AND campaign.state IN ('ACTIVE', 'PAUSED'))")) {
                statement.setString(1, targetPendingState.name());
                statement.setLong(2, nextAttemptMillis);
                statement.setLong(3, nowMillis);
                statement.setString(4, campaignId.toString());
                statement.setString(5, recipientKey.value());
                statement.setString(6, normalizedToken);
                statement.setLong(7, nowMillis);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<Boolean> completeClaim(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            String claimToken,
            LoreInstanceId instanceId,
            Instant deliveredAt) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(recipientKey, "recipientKey");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        String normalizedToken = normalizeClaimToken(claimToken);
        long deliveredAtMillis = requireNonNegative(deliveredAt, "deliveredAt");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET state = 'DELIVERED', instance_id = ?, "
                            + "claim_token = NULL, claim_expires_at = NULL, next_attempt_at = NULL, "
                            + "delivered_at = ?, updated_at = ? "
                            + "WHERE campaign_id = ? AND recipient_key = ? "
                            + "AND state = 'RESERVED' AND claim_token = ? "
                            + "AND claim_expires_at > ? "
                            + "AND (instance_id IS NULL OR instance_id = ?) "
                            + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                            + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
                            + "AND campaign.state IN ('ACTIVE', 'PAUSED', 'CANCELLED'))")) {
                String instanceValue = instanceId.value().toString();
                statement.setString(1, instanceValue);
                statement.setLong(2, deliveredAtMillis);
                statement.setLong(3, deliveredAtMillis);
                statement.setString(4, campaignId.toString());
                statement.setString(5, recipientKey.value());
                statement.setString(6, normalizedToken);
                statement.setLong(7, deliveredAtMillis);
                statement.setString(8, instanceValue);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<Boolean> moveClaimToReview(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            String claimToken,
            LoreInstanceId instanceId,
            Instant now) {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(recipientKey, "recipientKey");
        Objects.requireNonNull(now, "now");
        String normalizedToken = normalizeClaimToken(claimToken);
        long nowMillis = requireNonNegative(now, "now");
        return storage.execute(connection -> {
            String sql = instanceId == null
                    ? "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', "
                            + "claim_token = NULL, claim_expires_at = NULL, "
                            + "next_attempt_at = NULL, updated_at = ? "
                            + "WHERE campaign_id = ? AND recipient_key = ? "
                            + "AND state = 'RESERVED' AND claim_token = ? "
                            + "AND claim_expires_at > ?"
                    : "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', "
                            + "instance_id = ?, claim_token = NULL, claim_expires_at = NULL, "
                            + "next_attempt_at = NULL, updated_at = ? "
                            + "WHERE campaign_id = ? AND recipient_key = ? "
                            + "AND state = 'RESERVED' AND claim_token = ? "
                            + "AND claim_expires_at > ? "
                            + "AND (instance_id IS NULL OR instance_id = ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                String instanceValue = null;
                if (instanceId != null) {
                    instanceValue = instanceId.value().toString();
                    statement.setString(index++, instanceValue);
                }
                statement.setLong(index++, nowMillis);
                statement.setString(index++, campaignId.toString());
                statement.setString(index++, recipientKey.value());
                statement.setString(index++, normalizedToken);
                statement.setLong(index++, nowMillis);
                if (instanceValue != null) {
                    statement.setString(index, instanceValue);
                }
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
        long nowMillis = requireNonNegative(now, "now");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', "
                            + "claim_token = NULL, claim_expires_at = NULL, "
                            + "next_attempt_at = NULL, updated_at = ? "
                            + "WHERE rowid IN (SELECT rowid FROM distribution_recipients "
                            + "WHERE state = 'RESERVED' AND claim_expires_at <= ? "
                            + "ORDER BY claim_expires_at, campaign_id, snapshot_index LIMIT ?)")) {
                statement.setLong(1, nowMillis);
                statement.setLong(2, nowMillis);
                statement.setInt(3, limit);
                return statement.executeUpdate();
            }
        });
    }

    private static void validateInitialRecipient(
            UUID campaignId, CampaignRecipient recipient) {
        Objects.requireNonNull(recipient, "recipient");
        if (!campaignId.equals(recipient.campaignId())) {
            throw new IllegalArgumentException("Recipient belongs to another campaign");
        }
        if (recipient.state() != CampaignRecipientState.PENDING_NAME
                && recipient.state() != CampaignRecipientState.PENDING_OFFLINE) {
            throw new IllegalArgumentException(
                    "Initial recipients must be unresolved names or UUID-bound offline recipients");
        }
        if (recipient.instanceId() != null
                || recipient.claimToken() != null
                || recipient.claimExpiresAtEpochMillis() != null
                || recipient.attemptCount() != 0
                || recipient.nextAttemptAtEpochMillis() != null
                || recipient.deliveredAtEpochMillis() != null) {
            throw new IllegalArgumentException("Initial recipient contains mutable workflow metadata");
        }
    }

    private static void requireDraftCampaign(Connection connection, UUID campaignId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM distribution_campaigns WHERE campaign_id = ?")) {
            statement.setString(1, campaignId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Unknown distribution campaign");
                }
                if (!"DRAFT".equals(resultSet.getString("state"))) {
                    throw new IllegalStateException(
                            "Distribution recipient snapshot is sealed after activation");
                }
            }
        }
    }

    private static void insertBatch(
            Connection connection, List<CampaignRecipient> recipients) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO distribution_recipients(campaign_id, recipient_key, "
                        + "snapshot_index, original_value, player_id, state, instance_id, "
                        + "claim_token, claim_expires_at, attempt_count, next_attempt_at, "
                        + "delivered_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0, NULL, NULL, ?)")) {
            for (CampaignRecipient recipient : recipients) {
                statement.setString(1, recipient.campaignId().toString());
                statement.setString(2, recipient.recipientKey().value());
                statement.setInt(3, recipient.snapshotIndex());
                statement.setString(4, recipient.originalValue());
                if (recipient.playerId() == null) {
                    statement.setNull(5, Types.VARCHAR);
                } else {
                    statement.setString(5, recipient.playerId().toString());
                }
                statement.setString(6, recipient.state().name());
                statement.setLong(7, recipient.updatedAtEpochMillis());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Page<CampaignRecipient> claimPending(
            Connection connection,
            UUID campaignId,
            String claimToken,
            long now,
            long expiresAt,
            int limit) throws SQLException {
        List<CampaignRecipient> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                selectColumns() + " WHERE campaign_id = ? "
                        + "AND state IN ('PENDING_OFFLINE', 'PENDING_SPACE') "
                        + "AND player_id IS NOT NULL "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                        + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
                        + "AND campaign.state = 'ACTIVE') "
                        + "ORDER BY snapshot_index, recipient_key LIMIT ?")) {
            statement.setString(1, campaignId.toString());
            statement.setLong(2, now);
            statement.setInt(3, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(readRecipient(resultSet));
                }
            }
        }
        boolean hasMore = candidates.size() > limit;
        if (hasMore) {
            candidates.remove(candidates.size() - 1);
        }

        List<CampaignRecipient> claimed = new ArrayList<>(candidates.size());
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'RESERVED', claim_token = ?, "
                        + "claim_expires_at = ?, attempt_count = attempt_count + 1, "
                        + "next_attempt_at = NULL, updated_at = ? "
                        + "WHERE campaign_id = ? AND recipient_key = ? AND state = ? "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                        + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
                        + "AND campaign.state = 'ACTIVE')")) {
            for (CampaignRecipient candidate : candidates) {
                update.setString(1, claimToken);
                update.setLong(2, expiresAt);
                update.setLong(3, now);
                update.setString(4, campaignId.toString());
                update.setString(5, candidate.recipientKey().value());
                update.setString(6, candidate.state().name());
                update.setLong(7, now);
                if (update.executeUpdate() == 1) {
                    claimed.add(new CampaignRecipient(
                            candidate.campaignId(),
                            candidate.recipientKey(),
                            candidate.snapshotIndex(),
                            candidate.originalValue(),
                            candidate.playerId(),
                            CampaignRecipientState.RESERVED,
                            candidate.instanceId(),
                            claimToken,
                            expiresAt,
                            candidate.attemptCount() + 1,
                            null,
                            null,
                            now));
                }
            }
        }
        return new Page<>(claimed, 0, limit, hasMore);
    }

    private static Page<CampaignRecipient> readPage(
            PreparedStatement statement, PageRequest request) throws SQLException {
        List<CampaignRecipient> recipients = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                recipients.add(readRecipient(resultSet));
            }
        }
        boolean hasMore = recipients.size() > request.limit();
        if (hasMore) {
            recipients.remove(recipients.size() - 1);
        }
        return new Page<>(recipients, request.offset(), request.limit(), hasMore);
    }

    private static String selectColumns() {
        return "SELECT campaign_id, recipient_key, snapshot_index, original_value, "
                + "player_id, state, instance_id, claim_token, claim_expires_at, "
                + "attempt_count, next_attempt_at, delivered_at, updated_at "
                + "FROM distribution_recipients";
    }

    private static CampaignRecipient readRecipient(ResultSet resultSet) throws SQLException {
        String playerValue = resultSet.getString("player_id");
        String instanceValue = resultSet.getString("instance_id");
        return new CampaignRecipient(
                UUID.fromString(resultSet.getString("campaign_id")),
                new CampaignRecipientKey(resultSet.getString("recipient_key")),
                resultSet.getInt("snapshot_index"),
                resultSet.getString("original_value"),
                playerValue == null ? null : UUID.fromString(playerValue),
                CampaignRecipientState.valueOf(resultSet.getString("state")),
                instanceValue == null
                        ? null
                        : new LoreInstanceId(UUID.fromString(instanceValue)),
                resultSet.getString("claim_token"),
                nullableLong(resultSet, "claim_expires_at"),
                resultSet.getInt("attempt_count"),
                nullableLong(resultSet, "next_attempt_at"),
                nullableLong(resultSet, "delivered_at"),
                resultSet.getLong("updated_at"));
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static long count(
            Map<CampaignRecipientState, Long> counts, CampaignRecipientState state) {
        return counts.getOrDefault(state, 0L);
    }

    private static String normalizeClaimToken(String claimToken) {
        Objects.requireNonNull(claimToken, "claimToken");
        String normalized = claimToken.strip();
        if (normalized.isEmpty()
                || normalized.length() > CampaignRecipient.MAX_CLAIM_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid claim token");
        }
        return normalized;
    }

    private static long requireNonNegative(Instant instant, String name) {
        long value = instant.toEpochMilli();
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not precede the Unix epoch");
        }
        return value;
    }
}
