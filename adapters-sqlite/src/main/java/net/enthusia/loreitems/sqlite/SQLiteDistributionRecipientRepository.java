package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.CAMPAIGN_ID_ARGUMENT;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.MIN_LEASE_MILLIS;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.NOW_ARGUMENT;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.RECIPIENT_KEY_ARGUMENT;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.SINGLE_UPDATED_ROW;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.count;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.normalizeClaimToken;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.readPage;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.readRecipient;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.requireDraftCampaign;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.requireNonNegative;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.selectColumns;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.validateInitialRecipient;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
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
    private static final String RECIPIENT_KEY_PREDICATE =
            "WHERE campaign_id = ? AND recipient_key = ? ";
    private static final String RESERVED_CLAIM_PREDICATE =
            "AND state = 'RESERVED_IN_FLIGHT' AND claim_token = ? ";
    private static final String CAMPAIGN_EXISTS_PREFIX =
            "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign ";
    private static final String CAMPAIGN_CORRELATION =
            "WHERE campaign.campaign_id = distribution_recipients.campaign_id ";
    private static final String REVIEW_REQUIRED_UPDATE =
            "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', ";
    private static final String CLEAR_NEXT_ATTEMPT =
            "next_attempt_at = NULL, updated_at = ? ";

    private final SQLiteStorageRuntime storage;

    public SQLiteDistributionRecipientRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Void> insertBatch(
            UUID campaignId, List<CampaignRecipient> recipients) {
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
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
                    SQLiteDistributionRecipientSupport.insertBatch(transaction, snapshot);
                    return null;
                }));
    }

    @Override
    public CompletionStage<Optional<CampaignRecipient>> find(
            UUID campaignId, CampaignRecipientKey recipientKey) {
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
        Objects.requireNonNull(recipientKey, RECIPIENT_KEY_ARGUMENT);
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " " + RECIPIENT_KEY_PREDICATE)) {
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
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
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
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
        Objects.requireNonNull(state, SQLiteDistributionRecipientSupport.STATE_ARGUMENT);
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
        Objects.requireNonNull(recipientKey, RECIPIENT_KEY_ARGUMENT);
        Objects.requireNonNull(request, "request");
        if (!recipientKey.unresolvedNameKey()) {
            throw new IllegalArgumentException("Unresolved lookup requires a name recipient key");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE recipient_key = ? AND state = 'UNRESOLVED' "
                            + "ORDER BY updated_at, campaign_id LIMIT ? OFFSET ?")) {
                statement.setString(1, recipientKey.value());
                statement.setInt(2, request.limit() + 1);
                statement.setInt(3, request.offset());
                return readPage(statement, request);
            }
        });
    }

    @Override
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    public CompletionStage<CampaignRecipientCounts> countByState(UUID campaignId) {
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
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
                                CampaignRecipientState.valueOf(resultSet.getString(
                                        SQLiteDistributionRecipientSupport.STATE_COLUMN)),
                                resultSet.getLong("state_count"));
                    }
                }
            }
            return new CampaignRecipientCounts(
                    count(counts, CampaignRecipientState.UNRESOLVED),
                    count(counts, CampaignRecipientState.QUEUED_OFFLINE),
                    count(counts, CampaignRecipientState.QUEUED_INVENTORY_FULL),
                    count(counts, CampaignRecipientState.RESERVED_IN_FLIGHT),
                    count(counts, CampaignRecipientState.REVIEW_REQUIRED),
                    count(counts, CampaignRecipientState.DELIVERED),
                    count(counts, CampaignRecipientState.CANCELLED));
        });
    }

    @Override
    public CompletionStage<Boolean> bindUnresolvedName(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            UUID playerId,
            Instant now) {
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
        Objects.requireNonNull(recipientKey, RECIPIENT_KEY_ARGUMENT);
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(now, NOW_ARGUMENT);
        if (!recipientKey.unresolvedNameKey()) {
            throw new IllegalArgumentException("Name binding requires an unresolved-name key");
        }
        long nowMillis = requireNonNegative(now, NOW_ARGUMENT);
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET player_id = ?, "
                            + "state = 'QUEUED_OFFLINE', updated_at = ? "
                            + RECIPIENT_KEY_PREDICATE
                            + "AND state = 'UNRESOLVED' AND player_id IS NULL "
                            + "AND updated_at <= ? "
                            + CAMPAIGN_EXISTS_PREFIX
                            + CAMPAIGN_CORRELATION
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
                return statement.executeUpdate() == SINGLE_UPDATED_ROW;
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
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
        String normalizedToken = normalizeClaimToken(claimToken);
        Objects.requireNonNull(now, NOW_ARGUMENT);
        Objects.requireNonNull(lease, "lease");
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
        long nowMillis = requireNonNegative(now, NOW_ARGUMENT);
        long leaseMillis = lease.toMillis();
        if (leaseMillis < MIN_LEASE_MILLIS) {
            throw new IllegalArgumentException("lease must be positive");
        }
        long expiresAt = Math.addExact(nowMillis, leaseMillis);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> SQLiteDistributionRecipientSupport.claimPending(
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
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
        Objects.requireNonNull(recipientKey, RECIPIENT_KEY_ARGUMENT);
        Objects.requireNonNull(targetPendingState, "targetPendingState");
        Objects.requireNonNull(now, NOW_ARGUMENT);
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        CampaignRecipientState.RESERVED_IN_FLIGHT.transitionTo(targetPendingState);
        if (!targetPendingState.claimable()) {
            throw new IllegalArgumentException(
                    "Released campaign claims must return to an offline or full-inventory state");
        }
        String normalizedToken = normalizeClaimToken(claimToken);
        long nowMillis = requireNonNegative(now, NOW_ARGUMENT);
        long nextAttemptMillis = requireNonNegative(nextAttemptAt, "nextAttemptAt");
        if (nextAttemptMillis < nowMillis) {
            throw new IllegalArgumentException("nextAttemptAt must not precede now");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET state = ?, claim_token = NULL, "
                            + "claim_expires_at = NULL, next_attempt_at = ?, updated_at = ? "
                            + RECIPIENT_KEY_PREDICATE
                            + RESERVED_CLAIM_PREDICATE
                            + "AND claim_expires_at > ? AND instance_id IS NULL "
                            + CAMPAIGN_EXISTS_PREFIX
                            + CAMPAIGN_CORRELATION
                            + "AND campaign.state IN ('ACTIVE', 'PAUSED'))")) {
                statement.setString(1, targetPendingState.name());
                statement.setLong(2, nextAttemptMillis);
                statement.setLong(3, nowMillis);
                statement.setString(4, campaignId.toString());
                statement.setString(5, recipientKey.value());
                statement.setString(6, normalizedToken);
                statement.setLong(7, nowMillis);
                return statement.executeUpdate() == SINGLE_UPDATED_ROW;
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
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
        Objects.requireNonNull(recipientKey, RECIPIENT_KEY_ARGUMENT);
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        String normalizedToken = normalizeClaimToken(claimToken);
        long deliveredAtMillis = requireNonNegative(deliveredAt, "deliveredAt");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET state = 'DELIVERED', instance_id = ?, "
                            + "claim_token = NULL, claim_expires_at = NULL, next_attempt_at = NULL, "
                            + "delivered_at = ?, updated_at = ? "
                            + RECIPIENT_KEY_PREDICATE
                            + RESERVED_CLAIM_PREDICATE
                            + "AND claim_expires_at > ? "
                            + "AND (instance_id IS NULL OR instance_id = ?) "
                            + CAMPAIGN_EXISTS_PREFIX
                            + CAMPAIGN_CORRELATION
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
                return statement.executeUpdate() == SINGLE_UPDATED_ROW;
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
        Objects.requireNonNull(campaignId, CAMPAIGN_ID_ARGUMENT);
        Objects.requireNonNull(recipientKey, RECIPIENT_KEY_ARGUMENT);
        Objects.requireNonNull(now, NOW_ARGUMENT);
        String normalizedToken = normalizeClaimToken(claimToken);
        long nowMillis = requireNonNegative(now, NOW_ARGUMENT);
        return storage.execute(connection -> {
            String sql = instanceId == null
                    ? REVIEW_REQUIRED_UPDATE
                            + "claim_token = NULL, claim_expires_at = NULL, "
                            + CLEAR_NEXT_ATTEMPT
                            + RECIPIENT_KEY_PREDICATE
                            + RESERVED_CLAIM_PREDICATE
                            + "AND claim_expires_at > ?"
                    : REVIEW_REQUIRED_UPDATE
                            + "instance_id = ?, claim_token = NULL, claim_expires_at = NULL, "
                            + CLEAR_NEXT_ATTEMPT
                            + RECIPIENT_KEY_PREDICATE
                            + RESERVED_CLAIM_PREDICATE
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
                return statement.executeUpdate() == SINGLE_UPDATED_ROW;
            }
        });
    }

    @Override
    public CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit) {
        Objects.requireNonNull(now, NOW_ARGUMENT);
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
        long nowMillis = requireNonNegative(now, NOW_ARGUMENT);
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    REVIEW_REQUIRED_UPDATE
                            + "claim_token = NULL, claim_expires_at = NULL, "
                            + CLEAR_NEXT_ATTEMPT
                            + "WHERE rowid IN (SELECT rowid FROM distribution_recipients "
                            + "WHERE state = 'RESERVED_IN_FLIGHT' AND claim_expires_at <= ? "
                            + "ORDER BY claim_expires_at, campaign_id, snapshot_index LIMIT ?)")) {
                statement.setLong(1, nowMillis);
                statement.setLong(2, nowMillis);
                statement.setInt(3, limit);
                return statement.executeUpdate();
            }
        });
    }
}
