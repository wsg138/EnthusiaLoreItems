package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DistributionDeliveryRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.LoreInstanceId;

public final class SQLiteCancellableDistributionDeliveryRepository
        implements DistributionDeliveryRepository {
    private static final int SINGLE_ROW = 1;
    private static final int MIN_LIMIT = 1;
    private static final int JSON_CONTROL_CHARACTER_LIMIT = 0x20;
    private static final String CLEAR_CLAIM_SQL =
            "claim_token = NULL, claim_expires_at = NULL, ";
    private static final String CLEAR_RETRY_SQL =
            "next_attempt_at = NULL, updated_at = ? ";
    private static final String RECIPIENT_PREDICATE_SQL =
            "WHERE campaign_id = ? AND recipient_key = ? ";
    private static final String QUEUED_SOURCE = "campaign-delivery-queued";
    private static final String AGGREGATE_TYPE = "DISTRIBUTION_CAMPAIGN";
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final String REVIEW_EVENT = "DISTRIBUTION_RECIPIENT_REVIEW_REQUIRED";

    private final SQLiteStorageRuntime storage;
    private final SQLiteDistributionDeliveryRepository delegate;

    public SQLiteCancellableDistributionDeliveryRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.delegate = new SQLiteDistributionDeliveryRepository(storage);
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> claimPending(
            String claimToken,
            Instant now,
            Duration lease,
            int limit) {
        return delegate.claimPending(claimToken, now, lease, limit);
    }

    @Override
    public CompletionStage<Optional<PreparedDistributionDelivery>> prepareClaimed(
            CampaignRecipient recipient,
            Instant now) {
        return delegate.prepareClaimed(recipient, now);
    }

    @Override
    public CompletionStage<Boolean> deferClaimed(
            CampaignRecipient recipient,
            CampaignRecipientState targetPendingState,
            Instant now,
            Instant nextAttemptAt) {
        return delegate.deferClaimed(recipient, targetPendingState, now, nextAttemptAt);
    }

    @Override
    public CompletionStage<Boolean> deferPrepared(
            PreparedDistributionDelivery delivery,
            CampaignRecipientState targetPendingState,
            Instant now,
            Instant nextAttemptAt) {
        return delegate.deferPrepared(delivery, targetPendingState, now, nextAttemptAt);
    }

    @Override
    public CompletionStage<Boolean> cancelClaimed(
            CampaignRecipient recipient,
            Instant now) {
        requireUnpreparedClaim(recipient);
        long nowMillis = requireNonNegative(now, "now");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET state = 'CANCELLED', "
                            + CLEAR_CLAIM_SQL
                            + CLEAR_RETRY_SQL
                            + RECIPIENT_PREDICATE_SQL
                            + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id IS NULL "
                            + "AND claim_token = ? "
                            + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                            + "WHERE campaign.campaign_id = "
                            + "distribution_recipients.campaign_id "
                            + "AND campaign.state = 'CANCELLED')")) {
                statement.setLong(1, nowMillis);
                statement.setString(2, recipient.campaignId().toString());
                statement.setString(3, recipient.recipientKey().value());
                statement.setString(4, recipient.claimToken());
                return statement.executeUpdate() == SINGLE_ROW;
            }
        });
    }

    @Override
    public CompletionStage<Boolean> cancelPrepared(
            PreparedDistributionDelivery delivery,
            Instant now) {
        Objects.requireNonNull(delivery, "delivery");
        long nowMillis = requireNonNegative(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> cancelPrepared(transaction, delivery, nowMillis)));
    }

    @Override
    public CompletionStage<Boolean> completePrepared(
            PreparedDistributionDelivery delivery,
            int inventorySlot,
            String afterFingerprint,
            Instant completedAt) {
        return delegate.completePrepared(
                delivery, inventorySlot, afterFingerprint, completedAt);
    }

    @Override
    public CompletionStage<Boolean> moveClaimedToReview(
            CampaignRecipient recipient,
            String reason,
            Instant reviewedAt) {
        return delegate.moveClaimedToReview(recipient, reason, reviewedAt);
    }

    @Override
    public CompletionStage<Boolean> movePreparedToReview(
            PreparedDistributionDelivery delivery,
            String reason,
            Instant reviewedAt) {
        return delegate.movePreparedToReview(delivery, reason, reviewedAt);
    }

    @Override
    public CompletionStage<Integer> wakePlayer(UUID playerId, Instant now, int limit) {
        return delegate.wakePlayer(playerId, now, limit);
    }

    @Override
    public CompletionStage<Integer> recoverExpiredClaims(Instant now, int limit) {
        long nowMillis = requireNonNegative(now, "now");
        requireLimit(limit);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                        connection,
                        transaction -> recoverCancelledClaims(
                                transaction, nowMillis, limit)))
                .thenCompose(cancelled -> {
                    int remaining = limit - cancelled;
                    if (remaining <= 0) {
                        return java.util.concurrent.CompletableFuture.completedFuture(cancelled);
                    }
                    return delegate.recoverExpiredClaims(now, remaining)
                            .thenApply(recovered -> cancelled + recovered);
                });
    }

    private static boolean cancelPrepared(
            Connection connection,
            PreparedDistributionDelivery delivery,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'CANCELLED', instance_id = NULL, "
                        + CLEAR_CLAIM_SQL
                        + CLEAR_RETRY_SQL
                        + RECIPIENT_PREDICATE_SQL
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id = ? "
                        + "AND claim_token = ? "
                        + "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                        + "WHERE campaign.campaign_id = "
                        + "distribution_recipients.campaign_id "
                        + "AND campaign.state = 'CANCELLED')")) {
            statement.setLong(1, now);
            statement.setString(2, delivery.campaignId().toString());
            statement.setString(3, delivery.recipientKey().value());
            statement.setString(4, delivery.instanceId().value().toString());
            statement.setString(5, delivery.claimToken());
            if (statement.executeUpdate() != SINGLE_ROW) {
                return false;
            }
        }
        deletePreparedTracking(connection, delivery);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM lore_instances WHERE instance_id = ? "
                        + "AND lifecycle_state = 'ACTIVE'")) {
            statement.setString(1, delivery.instanceId().value().toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Cancelled campaign instance could not be safely discarded");
            }
        }
        return true;
    }

    private static void deletePreparedTracking(
            Connection connection,
            PreparedDistributionDelivery delivery) throws SQLException {
        String path = deliveryPath(delivery);
        try (PreparedStatement current = connection.prepareStatement(
                "DELETE FROM instance_current_state WHERE instance_id = ? "
                        + "AND state = 'CONFIRMED_NOW' "
                        + "AND location_type = 'QUEUED_DELIVERY' "
                        + "AND location_key = ? AND container_path = ?")) {
            current.setString(1, delivery.instanceId().value().toString());
            current.setString(2, delivery.playerId().toString());
            current.setString(3, path);
            if (current.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Cancelled prepared campaign current-state evidence changed");
            }
        }
        try (PreparedStatement observations = connection.prepareStatement(
                "DELETE FROM instance_observations WHERE instance_id = ? AND source = ? "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ?")) {
            observations.setString(1, delivery.instanceId().value().toString());
            observations.setString(2, QUEUED_SOURCE);
            observations.setString(3, delivery.playerId().toString());
            observations.setString(4, path);
            if (observations.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Cancelled prepared campaign queued observation changed");
            }
        }
    }

    // Each result row is a distinct durable claim snapshot, so row materialization necessarily
    // creates independent immutable value objects inside this bounded LIMIT query.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static int recoverCancelledClaims(
            Connection connection,
            long now,
            int limit) throws SQLException {
        List<ExpiredCancelledClaim> claims = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT recipient.campaign_id, recipient.recipient_key, "
                        + "recipient.player_id, recipient.instance_id, recipient.claim_token "
                        + "FROM distribution_recipients recipient "
                        + "JOIN distribution_campaigns campaign "
                        + "ON campaign.campaign_id = recipient.campaign_id "
                        + "WHERE recipient.state = 'RESERVED_IN_FLIGHT' "
                        + "AND recipient.claim_expires_at <= ? "
                        + "AND campaign.state = 'CANCELLED' "
                        + "ORDER BY recipient.claim_expires_at, recipient.campaign_id, "
                        + "recipient.snapshot_index LIMIT ?")) {
            statement.setLong(1, now);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String instance = resultSet.getString("instance_id");
                    claims.add(new ExpiredCancelledClaim(
                            UUID.fromString(resultSet.getString("campaign_id")),
                            new CampaignRecipientKey(resultSet.getString("recipient_key")),
                            UUID.fromString(resultSet.getString("player_id")),
                            instance == null
                                    ? null
                                    : new LoreInstanceId(UUID.fromString(instance)),
                            resultSet.getString("claim_token")));
                }
            }
        }
        int recovered = 0;
        for (ExpiredCancelledClaim claim : claims) {
            recovered += claim.instanceId() == null
                    ? cancelExpiredUnprepared(connection, claim, now)
                    : reviewExpiredPrepared(connection, claim, now);
        }
        return recovered;
    }

    private static int cancelExpiredUnprepared(
            Connection connection,
            ExpiredCancelledClaim claim,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'CANCELLED', "
                        + CLEAR_CLAIM_SQL
                        + CLEAR_RETRY_SQL
                        + RECIPIENT_PREDICATE_SQL
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id IS NULL "
                        + "AND claim_token = ? AND claim_expires_at <= ?")) {
            statement.setLong(1, now);
            statement.setString(2, claim.campaignId().toString());
            statement.setString(3, claim.recipientKey().value());
            statement.setString(4, claim.claimToken());
            statement.setLong(5, now);
            return statement.executeUpdate();
        }
    }

    private static int reviewExpiredPrepared(
            Connection connection,
            ExpiredCancelledClaim claim,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', "
                        + CLEAR_CLAIM_SQL
                        + CLEAR_RETRY_SQL
                        + RECIPIENT_PREDICATE_SQL
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
                markCurrentStateUnresolved(connection, claim, now);
                appendCancellationReviewAudit(connection, claim, now);
            }
            return updated;
        }
    }

    private static void markCurrentStateUnresolved(
            Connection connection,
            ExpiredCancelledClaim claim,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'MISSING_UNRESOLVED', "
                        + "location_type = NULL, location_key = NULL, "
                        + "container_path = NULL, last_observation_id = NULL, "
                        + "state_revision = state_revision + 1, updated_at = ? "
                        + "WHERE instance_id = ? AND state = 'CONFIRMED_NOW' "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ?")) {
            statement.setLong(1, now);
            statement.setString(2, claim.instanceId().value().toString());
            statement.setString(3, claim.playerId().toString());
            statement.setString(4, deliveryPath(claim));
            statement.executeUpdate();
        }
    }

    private static void appendCancellationReviewAudit(
            Connection connection,
            ExpiredCancelledClaim claim,
            long now) throws SQLException {
        String detail = "{\"recipientKey\":\""
                + escapeJson(claim.recipientKey().value())
                + "\",\"reason\":\"Prepared delivery claim expired after campaign "
                + "cancellation; physical insertion outcome is ambiguous.\"}";
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                AGGREGATE_TYPE,
                claim.campaignId().toString(),
                REVIEW_EVENT,
                SYSTEM_ACTOR,
                claim.playerId().toString(),
                detail,
                now));
    }

    private static void requireUnpreparedClaim(CampaignRecipient recipient) {
        Objects.requireNonNull(recipient, "recipient");
        if (recipient.state() != CampaignRecipientState.RESERVED_IN_FLIGHT
                || recipient.playerId() == null
                || recipient.claimToken() == null
                || recipient.instanceId() != null) {
            throw new IllegalArgumentException(
                    "Recipient must be an unprepared campaign claim");
        }
    }

    private static void requireLimit(int limit) {
        if (limit < MIN_LIMIT || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit is outside bounded page limits");
        }
    }

    private static long requireNonNegative(Instant instant, String name) {
        Objects.requireNonNull(instant, name);
        long value = instant.toEpochMilli();
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not precede the Unix epoch");
        }
        return value;
    }

    private static String deliveryPath(PreparedDistributionDelivery delivery) {
        return "campaign:" + delivery.campaignId()
                + ":recipient:" + delivery.recipientKey().value();
    }

    private static String deliveryPath(ExpiredCancelledClaim claim) {
        return "campaign:" + claim.campaignId()
                + ":recipient:" + claim.recipientKey().value();
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
                    if (character < JSON_CONTROL_CHARACTER_LIMIT) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record ExpiredCancelledClaim(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            UUID playerId,
            LoreInstanceId instanceId,
            String claimToken) {
    }
}
