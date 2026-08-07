package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.readRecipient;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.requireNonNegative;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.selectColumns;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DistributionDeliveryRepository;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class SQLiteDistributionDeliveryRepository
        implements DistributionDeliveryRepository {
    private static final String AGGREGATE_TYPE = "DISTRIBUTION_CAMPAIGN";
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final String QUEUED_SOURCE = "campaign-delivery-queued";
    private static final String COMPLETED_SOURCE = "campaign-delivery-completed";
    private static final String PREPARED_EVENT = "DISTRIBUTION_RECIPIENT_PREPARED";
    private static final String DELIVERED_EVENT = "DISTRIBUTION_RECIPIENT_DELIVERED";
    private static final String REVIEW_EVENT = "DISTRIBUTION_RECIPIENT_REVIEW_REQUIRED";
    private static final int SINGLE_ROW = 1;
    private static final int MIN_SLOT = 0;
    private static final int MAX_SLOT = 35;
    private static final int MAX_REVIEW_REASON = 4_096;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final SQLiteStorageRuntime storage;

    public SQLiteDistributionDeliveryRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> claimPending(
            String claimToken,
            Instant now,
            Duration lease,
            int limit) {
        String token = SQLiteDistributionRecipientSupport.normalizeClaimToken(claimToken);
        long nowMillis = requireNonNegative(Objects.requireNonNull(now, "now"), "now");
        Objects.requireNonNull(lease, "lease");
        requireLimit(limit);
        long leaseMillis = lease.toMillis();
        if (leaseMillis < SQLiteDistributionRecipientSupport.MIN_LEASE_MILLIS) {
            throw new IllegalArgumentException("lease must be positive");
        }
        long expiresAt = Math.addExact(nowMillis, leaseMillis);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> claimPending(transaction, token, nowMillis, expiresAt, limit)));
    }

    @Override
    public CompletionStage<Optional<PreparedDistributionDelivery>> prepareClaimed(
            CampaignRecipient recipient,
            Instant now) {
        requireClaimedRecipient(recipient);
        long nowMillis = requireNonNegative(Objects.requireNonNull(now, "now"), "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> prepareClaimed(transaction, recipient, nowMillis)));
    }

    @Override
    public CompletionStage<Boolean> deferClaimed(
            CampaignRecipient recipient,
            CampaignRecipientState targetPendingState,
            Instant now,
            Instant nextAttemptAt) {
        requireClaimedRecipient(recipient);
        requirePendingTarget(targetPendingState);
        long nowMillis = requireNonNegative(Objects.requireNonNull(now, "now"), "now");
        long nextMillis = requireNonNegative(
                Objects.requireNonNull(nextAttemptAt, "nextAttemptAt"), "nextAttemptAt");
        if (nextMillis < nowMillis) {
            throw new IllegalArgumentException("nextAttemptAt must not precede now");
        }
        return storage.execute(connection -> deferClaimed(
                connection, recipient, targetPendingState, nowMillis, nextMillis));
    }

    @Override
    public CompletionStage<Boolean> deferPrepared(
            PreparedDistributionDelivery delivery,
            CampaignRecipientState targetPendingState,
            Instant now,
            Instant nextAttemptAt) {
        Objects.requireNonNull(delivery, "delivery");
        requirePendingTarget(targetPendingState);
        long nowMillis = requireNonNegative(Objects.requireNonNull(now, "now"), "now");
        long nextMillis = requireNonNegative(
                Objects.requireNonNull(nextAttemptAt, "nextAttemptAt"), "nextAttemptAt");
        if (nextMillis < nowMillis) {
            throw new IllegalArgumentException("nextAttemptAt must not precede now");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> deferPrepared(
                        transaction, delivery, targetPendingState, nowMillis, nextMillis)));
    }

    @Override
    public CompletionStage<Boolean> completePrepared(
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            String afterFingerprint,
            Instant completedAt) {
        Objects.requireNonNull(delivery, "delivery");
        requireSlot(inventorySlot);
        String fingerprint = requireFingerprint(afterFingerprint);
        long completedMillis = requireNonNegative(
                Objects.requireNonNull(completedAt, "completedAt"), "completedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> completePrepared(
                        transaction, delivery, inventorySlot, fingerprint, completedMillis)));
    }

    @Override
    public CompletionStage<Boolean> moveClaimedToReview(
            CampaignRecipient recipient,
            String reason,
            Instant reviewedAt) {
        requireClaimedRecipient(recipient);
        String normalizedReason = requireReason(reason);
        long reviewedMillis = requireNonNegative(
                Objects.requireNonNull(reviewedAt, "reviewedAt"), "reviewedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> moveClaimedToReview(
                        transaction, recipient, normalizedReason, reviewedMillis)));
    }

    @Override
    public CompletionStage<Boolean> movePreparedToReview(
            PreparedDistributionDelivery delivery,
            String reason,
            Instant reviewedAt) {
        Objects.requireNonNull(delivery, "delivery");
        String normalizedReason = requireReason(reason);
        long reviewedMillis = requireNonNegative(
                Objects.requireNonNull(reviewedAt, "reviewedAt"), "reviewedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> movePreparedToReview(
                        transaction, delivery, normalizedReason, reviewedMillis)));
    }

    @Override
    public CompletionStage<Integer> wakePlayer(UUID playerId, Instant now, int limit) {
        Objects.requireNonNull(playerId, "playerId");
        long nowMillis = requireNonNegative(Objects.requireNonNull(now, "now"), "now");
        requireLimit(limit);
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET next_attempt_at = NULL, updated_at = ? "
                            + "WHERE rowid IN (SELECT recipient.rowid FROM distribution_recipients recipient "
                            + "JOIN distribution_campaigns campaign "
                            + "ON campaign.campaign_id = recipient.campaign_id "
                            + "WHERE recipient.player_id = ? "
                            + "AND recipient.state IN ('QUEUED_OFFLINE', 'QUEUED_INVENTORY_FULL') "
                            + "AND recipient.next_attempt_at IS NOT NULL "
                            + "AND campaign.state = 'ACTIVE' "
                            + "ORDER BY recipient.updated_at, recipient.campaign_id, recipient.snapshot_index "
                            + "LIMIT ?)")) {
                statement.setLong(1, nowMillis);
                statement.setString(2, playerId.toString());
                statement.setInt(3, limit);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletionStage<Integer> recoverExpiredClaims(Instant now, int limit) {
        long nowMillis = requireNonNegative(Objects.requireNonNull(now, "now"), "now");
        requireLimit(limit);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> recoverExpiredClaims(transaction, nowMillis, limit)));
    }

    private static Page<CampaignRecipient> claimPending(
            Connection connection,
            String claimToken,
            long now,
            long expiresAt,
            int limit) throws SQLException {
        List<CampaignRecipient> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                selectColumns() + " recipient WHERE recipient.state IN "
                        + "('QUEUED_OFFLINE', 'QUEUED_INVENTORY_FULL') "
                        + "AND recipient.player_id IS NOT NULL "
                        + "AND (recipient.next_attempt_at IS NULL OR recipient.next_attempt_at <= ?) "
                        + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                        + "WHERE campaign.campaign_id = recipient.campaign_id "
                        + "AND campaign.state = 'ACTIVE') "
                        + "ORDER BY recipient.updated_at, recipient.campaign_id, recipient.snapshot_index "
                        + "LIMIT ?")) {
            statement.setLong(1, now);
            statement.setInt(2, limit + 1);
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
                "UPDATE distribution_recipients SET state = 'RESERVED_IN_FLIGHT', "
                        + "claim_token = ?, claim_expires_at = ?, attempt_count = attempt_count + 1, "
                        + "next_attempt_at = NULL, updated_at = ? "
                        + "WHERE campaign_id = ? AND recipient_key = ? AND state = ? "
                        + "AND instance_id IS NULL "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                        + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
                        + "AND campaign.state = 'ACTIVE')")) {
            for (CampaignRecipient candidate : candidates) {
                update.setString(1, claimToken);
                update.setLong(2, expiresAt);
                update.setLong(3, now);
                update.setString(4, candidate.campaignId().toString());
                update.setString(5, candidate.recipientKey().value());
                update.setString(6, candidate.state().name());
                update.setLong(7, now);
                if (update.executeUpdate() == SINGLE_ROW) {
                    claimed.add(reserved(candidate, claimToken, expiresAt, now));
                }
            }
        }
        return new Page<>(claimed, 0, limit, hasMore);
    }

    private static CampaignRecipient reserved(
            CampaignRecipient candidate,
            String claimToken,
            long expiresAt,
            long now) {
        return new CampaignRecipient(
                candidate.campaignId(),
                candidate.recipientKey(),
                candidate.snapshotIndex(),
                candidate.originalValue(),
                candidate.playerId(),
                CampaignRecipientState.RESERVED_IN_FLIGHT,
                null,
                claimToken,
                expiresAt,
                candidate.attemptCount() + 1,
                null,
                null,
                now);
    }

    private static Optional<PreparedDistributionDelivery> prepareClaimed(
            Connection connection,
            CampaignRecipient recipient,
            long now) throws SQLException {
        PreparationSource source = findPreparationSource(connection, recipient, now);
        if (source == null) {
            return Optional.empty();
        }
        LoreInstanceId instanceId = new LoreInstanceId(UUID.randomUUID());
        insertInstance(connection, instanceId, source, now);
        long observationId = insertQueuedObservation(connection, recipient, instanceId, source, now);
        insertQueuedCurrentState(connection, recipient, instanceId, observationId, now);
        if (!attachInstance(connection, recipient, instanceId, now)) {
            throw new SQLException("Campaign recipient claim changed during durable preparation");
        }
        appendCampaignAudit(
                connection,
                recipient.campaignId(),
                PREPARED_EVENT,
                recipient.playerId().toString(),
                "{\"recipientKey\":\"" + escapeJson(recipient.recipientKey().value())
                        + "\",\"instanceId\":\"" + instanceId.value() + "\"}",
                now);
        return Optional.of(new PreparedDistributionDelivery(
                recipient.campaignId(),
                recipient.recipientKey(),
                instanceId,
                source.definitionId(),
                recipient.playerId(),
                source.revision(),
                source.template(),
                recipient.claimToken(),
                source.claimExpiresAt(),
                source.attemptCount(),
                now));
    }

    private static PreparationSource findPreparationSource(
            Connection connection,
            CampaignRecipient recipient,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT campaign.definition_id, snapshot.definition_revision, "
                        + "revision.codec_version, revision.template_blob, "
                        + "recipient.claim_expires_at, recipient.attempt_count "
                        + "FROM distribution_recipients recipient "
                        + "JOIN distribution_campaigns campaign "
                        + "ON campaign.campaign_id = recipient.campaign_id "
                        + "JOIN distribution_campaign_revision_snapshots snapshot "
                        + "ON snapshot.campaign_id = campaign.campaign_id "
                        + "JOIN lore_definition_revisions revision "
                        + "ON revision.definition_id = snapshot.definition_id "
                        + "AND revision.revision = snapshot.definition_revision "
                        + "WHERE recipient.campaign_id = ? AND recipient.recipient_key = ? "
                        + "AND recipient.state = 'RESERVED_IN_FLIGHT' "
                        + "AND recipient.player_id = ? AND recipient.instance_id IS NULL "
                        + "AND recipient.claim_token = ? AND recipient.claim_expires_at > ? "
                        + "AND campaign.state IN ('ACTIVE', 'PAUSED')")) {
            statement.setString(1, recipient.campaignId().toString());
            statement.setString(2, recipient.recipientKey().value());
            statement.setString(3, recipient.playerId().toString());
            statement.setString(4, recipient.claimToken());
            statement.setLong(5, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new PreparationSource(
                        new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                        new TemplateRevision(resultSet.getLong("definition_revision")),
                        new EncodedItemTemplate(
                                resultSet.getInt("codec_version"),
                                resultSet.getBytes("template_blob")),
                        resultSet.getLong("claim_expires_at"),
                        resultSet.getInt("attempt_count"));
            }
        }
    }

    private static void insertInstance(
            Connection connection,
            LoreInstanceId instanceId,
            PreparationSource source,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?)")) {
            statement.setString(1, instanceId.value().toString());
            statement.setString(2, source.definitionId().value().toString());
            statement.setLong(3, source.revision().value());
            statement.setLong(4, source.revision().value());
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static long insertQueuedObservation(
            Connection connection,
            CampaignRecipient recipient,
            LoreInstanceId instanceId,
            PreparationSource source,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, 'QUEUED_DELIVERY', ?, ?, 'CONFIRMED_NOW', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, instanceId.value().toString());
            statement.setString(2, source.definitionId().value().toString());
            statement.setString(3, recipient.playerId().toString());
            statement.setString(4, deliveryPath(recipient.campaignId(), recipient.recipientKey()));
            statement.setString(5, QUEUED_SOURCE);
            statement.setLong(6, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Campaign queued observation did not return an identifier");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void insertQueuedCurrentState(
            Connection connection,
            CampaignRecipient recipient,
            LoreInstanceId instanceId,
            long observationId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_current_state(instance_id, state, location_type, "
                        + "location_key, container_path, last_observation_id, state_revision, updated_at) "
                        + "VALUES (?, 'CONFIRMED_NOW', 'QUEUED_DELIVERY', ?, ?, ?, 1, ?)")) {
            statement.setString(1, instanceId.value().toString());
            statement.setString(2, recipient.playerId().toString());
            statement.setString(3, deliveryPath(recipient.campaignId(), recipient.recipientKey()));
            statement.setLong(4, observationId);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static boolean attachInstance(
            Connection connection,
            CampaignRecipient recipient,
            LoreInstanceId instanceId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET instance_id = ?, updated_at = ? "
                        + "WHERE campaign_id = ? AND recipient_key = ? "
                        + "AND state = 'RESERVED_IN_FLIGHT' AND player_id = ? "
                        + "AND instance_id IS NULL AND claim_token = ? AND claim_expires_at > ?")) {
            statement.setString(1, instanceId.value().toString());
            statement.setLong(2, now);
            statement.setString(3, recipient.campaignId().toString());
            statement.setString(4, recipient.recipientKey().value());
            statement.setString(5, recipient.playerId().toString());
            statement.setString(6, recipient.claimToken());
            statement.setLong(7, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static boolean deferClaimed(
            Connection connection,
            CampaignRecipient recipient,
            CampaignRecipientState target,
            long now,
            long nextAttemptAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = ?, claim_token = NULL, "
                        + "claim_expires_at = NULL, next_attempt_at = ?, updated_at = ? "
                        + "WHERE campaign_id = ? AND recipient_key = ? "
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id IS NULL "
                        + "AND claim_token = ? AND claim_expires_at > ? "
                        + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                        + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
                        + "AND campaign.state IN ('ACTIVE', 'PAUSED'))")) {
            statement.setString(1, target.name());
            statement.setLong(2, nextAttemptAt);
            statement.setLong(3, now);
            statement.setString(4, recipient.campaignId().toString());
            statement.setString(5, recipient.recipientKey().value());
            statement.setString(6, recipient.claimToken());
            statement.setLong(7, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static boolean deferPrepared(
            Connection connection,
            PreparedDistributionDelivery delivery,
            CampaignRecipientState target,
            long now,
            long nextAttemptAt) throws SQLException {
        if (!releasePreparedRecipient(connection, delivery, target, now, nextAttemptAt)) {
            return false;
        }
        deleteQueuedTracking(connection, delivery);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM lore_instances WHERE instance_id = ? AND lifecycle_state = 'ACTIVE'")) {
            statement.setString(1, delivery.instanceId().value().toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Prepared campaign instance could not be safely discarded");
            }
        }
        return true;
    }

    private static boolean releasePreparedRecipient(
            Connection connection,
            PreparedDistributionDelivery delivery,
            CampaignRecipientState target,
            long now,
            long nextAttemptAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = ?, instance_id = NULL, "
                        + "claim_token = NULL, claim_expires_at = NULL, next_attempt_at = ?, "
                        + "updated_at = ? WHERE campaign_id = ? AND recipient_key = ? "
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id = ? "
                        + "AND claim_token = ? AND claim_expires_at > ? "
                        + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                        + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
                        + "AND campaign.state IN ('ACTIVE', 'PAUSED'))")) {
            statement.setString(1, target.name());
            statement.setLong(2, nextAttemptAt);
            statement.setLong(3, now);
            statement.setString(4, delivery.campaignId().toString());
            statement.setString(5, delivery.recipientKey().value());
            statement.setString(6, delivery.instanceId().value().toString());
            statement.setString(7, delivery.claimToken());
            statement.setLong(8, now);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static void deleteQueuedTracking(
            Connection connection,
            PreparedDistributionDelivery delivery) throws SQLException {
        try (PreparedStatement current = connection.prepareStatement(
                "DELETE FROM instance_current_state WHERE instance_id = ? "
                        + "AND state = 'CONFIRMED_NOW' AND location_type = 'QUEUED_DELIVERY' "
                        + "AND location_key = ? AND container_path = ?")) {
            current.setString(1, delivery.instanceId().value().toString());
            current.setString(2, delivery.playerId().toString());
            current.setString(3, deliveryPath(delivery.campaignId(), delivery.recipientKey()));
            if (current.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Prepared campaign current-state evidence changed before deferral");
            }
        }
        try (PreparedStatement observations = connection.prepareStatement(
                "DELETE FROM instance_observations WHERE instance_id = ? AND source = ? "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ?")) {
            observations.setString(1, delivery.instanceId().value().toString());
            observations.setString(2, QUEUED_SOURCE);
            observations.setString(3, delivery.playerId().toString());
            observations.setString(4, deliveryPath(delivery.campaignId(), delivery.recipientKey()));
            if (observations.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Prepared campaign queued observation changed before deferral");
            }
        }
    }

    private static boolean completePrepared(
            Connection connection,
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            String fingerprint,
            long completedAt) throws SQLException {
        if (!recipientStillCompletable(connection, delivery, completedAt)) {
            return false;
        }
        long observationId = insertCompletedObservation(
                connection, delivery, inventorySlot, completedAt);
        updateCompletedCurrentState(
                connection, delivery, inventorySlot, observationId, completedAt);
        if (!markRecipientDelivered(connection, delivery, completedAt)) {
            throw new SQLException("Campaign recipient lost its durable completion transition");
        }
        appendCampaignAudit(
                connection,
                delivery.campaignId(),
                DELIVERED_EVENT,
                delivery.playerId().toString(),
                "{\"recipientKey\":\"" + escapeJson(delivery.recipientKey().value())
                        + "\",\"instanceId\":\"" + delivery.instanceId().value()
                        + "\",\"slot\":" + inventorySlot
                        + ",\"afterFingerprint\":\"" + fingerprint + "\"}",
                completedAt);
        completeCampaignIfReady(connection, delivery.campaignId(), completedAt);
        return true;
    }

    private static boolean recipientStillCompletable(
            Connection connection,
            PreparedDistributionDelivery delivery,
            long completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM distribution_recipients recipient "
                        + "JOIN distribution_campaigns campaign "
                        + "ON campaign.campaign_id = recipient.campaign_id "
                        + "WHERE recipient.campaign_id = ? AND recipient.recipient_key = ? "
                        + "AND recipient.state = 'RESERVED_IN_FLIGHT' AND recipient.instance_id = ? "
                        + "AND recipient.player_id = ? AND recipient.claim_token = ? "
                        + "AND recipient.claim_expires_at > ? "
                        + "AND campaign.state IN ('ACTIVE', 'PAUSED', 'CANCELLED')")) {
            statement.setString(1, delivery.campaignId().toString());
            statement.setString(2, delivery.recipientKey().value());
            statement.setString(3, delivery.instanceId().value().toString());
            statement.setString(4, delivery.playerId().toString());
            statement.setString(5, delivery.claimToken());
            statement.setLong(6, completedAt);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static long insertCompletedObservation(
            Connection connection,
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            long completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, 'PLAYER_INVENTORY', ?, ?, 'CONFIRMED_NOW', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, delivery.instanceId().value().toString());
            statement.setString(2, delivery.definitionId().value().toString());
            statement.setString(3, delivery.playerId().toString());
            statement.setString(4, inventoryPath(inventorySlot));
            statement.setString(5, COMPLETED_SOURCE);
            statement.setLong(6, completedAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Campaign completion observation did not return an identifier");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void updateCompletedCurrentState(
            Connection connection,
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            long observationId,
            long completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'CONFIRMED_NOW', "
                        + "location_type = 'PLAYER_INVENTORY', location_key = ?, container_path = ?, "
                        + "last_observation_id = ?, state_revision = state_revision + 1, updated_at = ? "
                        + "WHERE instance_id = ? AND state = 'CONFIRMED_NOW' "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ? AND state_revision = 1")) {
            statement.setString(1, delivery.playerId().toString());
            statement.setString(2, inventoryPath(inventorySlot));
            statement.setLong(3, observationId);
            statement.setLong(4, completedAt);
            statement.setString(5, delivery.instanceId().value().toString());
            statement.setString(6, delivery.playerId().toString());
            statement.setString(7, deliveryPath(delivery.campaignId(), delivery.recipientKey()));
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Campaign current-state verification lost its queued evidence");
            }
        }
    }

    private static boolean markRecipientDelivered(
            Connection connection,
            PreparedDistributionDelivery delivery,
            long completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'DELIVERED', claim_token = NULL, "
                        + "claim_expires_at = NULL, next_attempt_at = NULL, delivered_at = ?, "
                        + "updated_at = ? WHERE campaign_id = ? AND recipient_key = ? "
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id = ? "
                        + "AND player_id = ? AND claim_token = ? AND claim_expires_at > ?")) {
            statement.setLong(1, completedAt);
            statement.setLong(2, completedAt);
            statement.setString(3, delivery.campaignId().toString());
            statement.setString(4, delivery.recipientKey().value());
            statement.setString(5, delivery.instanceId().value().toString());
            statement.setString(6, delivery.playerId().toString());
            statement.setString(7, delivery.claimToken());
            statement.setLong(8, completedAt);
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static void completeCampaignIfReady(
            Connection connection,
            UUID campaignId,
            long completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_campaigns SET state = 'COMPLETED', updated_at = ?, terminal_at = ? "
                        + "WHERE campaign_id = ? AND state IN ('ACTIVE', 'PAUSED') "
                        + "AND terminal_at IS NULL AND NOT EXISTS (SELECT 1 FROM distribution_recipients "
                        + "WHERE campaign_id = ? AND state <> 'DELIVERED')")) {
            statement.setLong(1, completedAt);
            statement.setLong(2, completedAt);
            statement.setString(3, campaignId.toString());
            statement.setString(4, campaignId.toString());
            statement.executeUpdate();
        }
    }

    private static boolean moveClaimedToReview(
            Connection connection,
            CampaignRecipient recipient,
            String reason,
            long reviewedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', "
                        + "claim_token = NULL, claim_expires_at = NULL, next_attempt_at = NULL, "
                        + "updated_at = ? WHERE campaign_id = ? AND recipient_key = ? "
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id IS NULL "
                        + "AND claim_token = ?")) {
            statement.setLong(1, reviewedAt);
            statement.setString(2, recipient.campaignId().toString());
            statement.setString(3, recipient.recipientKey().value());
            statement.setString(4, recipient.claimToken());
            if (statement.executeUpdate() != SINGLE_ROW) {
                return false;
            }
        }
        appendCampaignAudit(
                connection,
                recipient.campaignId(),
                REVIEW_EVENT,
                recipient.playerId().toString(),
                reviewDetail(recipient.recipientKey(), reason),
                reviewedAt);
        return true;
    }

    private static boolean movePreparedToReview(
            Connection connection,
            PreparedDistributionDelivery delivery,
            String reason,
            long reviewedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', "
                        + "claim_token = NULL, claim_expires_at = NULL, next_attempt_at = NULL, "
                        + "updated_at = ? WHERE campaign_id = ? AND recipient_key = ? "
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id = ? "
                        + "AND claim_token = ?")) {
            statement.setLong(1, reviewedAt);
            statement.setString(2, delivery.campaignId().toString());
            statement.setString(3, delivery.recipientKey().value());
            statement.setString(4, delivery.instanceId().value().toString());
            statement.setString(5, delivery.claimToken());
            if (statement.executeUpdate() != SINGLE_ROW) {
                return false;
            }
        }
        markCurrentStateUnresolved(connection, delivery, reviewedAt);
        appendCampaignAudit(
                connection,
                delivery.campaignId(),
                REVIEW_EVENT,
                delivery.playerId().toString(),
                reviewDetail(delivery.recipientKey(), reason),
                reviewedAt);
        return true;
    }

    private static void markCurrentStateUnresolved(
            Connection connection,
            PreparedDistributionDelivery delivery,
            long reviewedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'MISSING_UNRESOLVED', "
                        + "location_type = NULL, location_key = NULL, container_path = NULL, "
                        + "last_observation_id = NULL, state_revision = state_revision + 1, "
                        + "updated_at = ? WHERE instance_id = ? AND state = 'CONFIRMED_NOW' "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ?")) {
            statement.setLong(1, reviewedAt);
            statement.setString(2, delivery.instanceId().value().toString());
            statement.setString(3, delivery.playerId().toString());
            statement.setString(4, deliveryPath(delivery.campaignId(), delivery.recipientKey()));
            statement.executeUpdate();
        }
    }

    private static int recoverExpiredClaims(
            Connection connection,
            long now,
            int limit) throws SQLException {
        List<ExpiredClaim> expired = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT campaign_id, recipient_key, player_id, instance_id, claim_token "
                        + "FROM distribution_recipients WHERE state = 'RESERVED_IN_FLIGHT' "
                        + "AND claim_expires_at <= ? ORDER BY claim_expires_at, campaign_id, snapshot_index "
                        + "LIMIT ?")) {
            statement.setLong(1, now);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String instanceValue = resultSet.getString("instance_id");
                    expired.add(new ExpiredClaim(
                            UUID.fromString(resultSet.getString("campaign_id")),
                            new CampaignRecipientKey(resultSet.getString("recipient_key")),
                            UUID.fromString(resultSet.getString("player_id")),
                            instanceValue == null
                                    ? null
                                    : new LoreInstanceId(UUID.fromString(instanceValue)),
                            resultSet.getString("claim_token")));
                }
            }
        }
        int recovered = 0;
        for (ExpiredClaim claim : expired) {
            recovered += claim.instanceId() == null
                    ? recoverUnprepared(connection, claim, now)
                    : recoverPrepared(connection, claim, now);
        }
        return recovered;
    }

    private static int recoverUnprepared(
            Connection connection,
            ExpiredClaim claim,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'QUEUED_OFFLINE', claim_token = NULL, "
                        + "claim_expires_at = NULL, next_attempt_at = ?, updated_at = ? "
                        + "WHERE campaign_id = ? AND recipient_key = ? "
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id IS NULL "
                        + "AND claim_token = ? AND claim_expires_at <= ?")) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, claim.campaignId().toString());
            statement.setString(4, claim.recipientKey().value());
            statement.setString(5, claim.claimToken());
            statement.setLong(6, now);
            return statement.executeUpdate();
        }
    }

    private static int recoverPrepared(
            Connection connection,
            ExpiredClaim claim,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', claim_token = NULL, "
                        + "claim_expires_at = NULL, next_attempt_at = NULL, updated_at = ? "
                        + "WHERE campaign_id = ? AND recipient_key = ? "
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id = ? "
                        + "AND claim_token = ? AND claim_expires_at <= ?")) {
            statement.setLong(1, now);
            statement.setString(2, claim.campaignId().toString());
            statement.setString(3, claim.recipientKey().value());
            statement.setString(4, claim.instanceId().value().toString());
            statement.setString(5, claim.claimToken());
            statement.setLong(6, now);
            int updated = statement.executeUpdate();
            if (updated == SINGLE_ROW) {
                markExpiredCurrentStateUnresolved(connection, claim, now);
                appendCampaignAudit(
                        connection,
                        claim.campaignId(),
                        REVIEW_EVENT,
                        claim.playerId().toString(),
                        reviewDetail(
                                claim.recipientKey(),
                                "Prepared campaign delivery claim expired before durable completion."),
                        now);
            }
            return updated;
        }
    }

    private static void markExpiredCurrentStateUnresolved(
            Connection connection,
            ExpiredClaim claim,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'MISSING_UNRESOLVED', "
                        + "location_type = NULL, location_key = NULL, container_path = NULL, "
                        + "last_observation_id = NULL, state_revision = state_revision + 1, "
                        + "updated_at = ? WHERE instance_id = ? AND state = 'CONFIRMED_NOW' "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ?")) {
            statement.setLong(1, now);
            statement.setString(2, claim.instanceId().value().toString());
            statement.setString(3, claim.playerId().toString());
            statement.setString(4, deliveryPath(claim.campaignId(), claim.recipientKey()));
            statement.executeUpdate();
        }
    }

    private static void appendCampaignAudit(
            Connection connection,
            UUID campaignId,
            String eventType,
            String actorId,
            String detail,
            long occurredAt) throws SQLException {
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                AGGREGATE_TYPE,
                campaignId.toString(),
                eventType,
                SYSTEM_ACTOR,
                actorId,
                detail,
                occurredAt));
    }

    private static void requireClaimedRecipient(CampaignRecipient recipient) {
        Objects.requireNonNull(recipient, "recipient");
        if (recipient.state() != CampaignRecipientState.RESERVED_IN_FLIGHT
                || recipient.playerId() == null
                || recipient.claimToken() == null
                || recipient.instanceId() != null) {
            throw new IllegalArgumentException("Recipient must be a claimed, unprepared campaign delivery");
        }
    }

    private static void requirePendingTarget(CampaignRecipientState target) {
        Objects.requireNonNull(target, "targetPendingState");
        if (target != CampaignRecipientState.QUEUED_OFFLINE
                && target != CampaignRecipientState.QUEUED_INVENTORY_FULL) {
            throw new IllegalArgumentException("Campaign deferral target must be a pending delivery state");
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
    }

    private static void requireSlot(int slot) {
        if (slot < MIN_SLOT || slot > MAX_SLOT) {
            throw new IllegalArgumentException("inventorySlot must identify player storage");
        }
    }

    private static String requireFingerprint(String value) {
        Objects.requireNonNull(value, "afterFingerprint");
        String normalized = value.strip();
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException("afterFingerprint must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String requireReason(String value) {
        Objects.requireNonNull(value, "reason");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_REVIEW_REASON) {
            throw new IllegalArgumentException("Invalid campaign review reason");
        }
        return normalized;
    }

    private static String deliveryPath(UUID campaignId, CampaignRecipientKey recipientKey) {
        return "campaign:" + campaignId + ":recipient:" + recipientKey.value();
    }

    private static String inventoryPath(int slot) {
        return "storage:" + slot;
    }

    private static String reviewDetail(CampaignRecipientKey recipientKey, String reason) {
        return "{\"recipientKey\":\"" + escapeJson(recipientKey.value())
                + "\",\"reason\":\"" + escapeJson(reason) + "\"}";
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record PreparationSource(
            LoreDefinitionId definitionId,
            TemplateRevision revision,
            EncodedItemTemplate template,
            long claimExpiresAt,
            int attemptCount) {
    }

    private record ExpiredClaim(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            UUID playerId,
            LoreInstanceId instanceId,
            String claimToken) {
    }
}
