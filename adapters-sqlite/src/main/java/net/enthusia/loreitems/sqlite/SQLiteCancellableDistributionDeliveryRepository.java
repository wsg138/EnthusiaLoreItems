package net.enthusia.loreitems.sqlite;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DistributionDeliveryRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;

public final class SQLiteCancellableDistributionDeliveryRepository
        implements DistributionDeliveryRepository {
    private static final int SINGLE_ROW = 1;
    private static final String QUEUED_SOURCE = "campaign-delivery-queued";

    private final SQLiteStorageRuntime storage;
    private final SQLiteDistributionDeliveryRepository delegate;

    public SQLiteCancellableDistributionDeliveryRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.delegate = new SQLiteDistributionDeliveryRepository(storage);
    }

    @Override
    public CompletionStage<Page<CampaignRecipient>> claimPending(
            String claimToken, Instant now, Duration lease, int limit) {
        return delegate.claimPending(claimToken, now, lease, limit);
    }

    @Override
    public CompletionStage<Optional<PreparedDistributionDelivery>> prepareClaimed(
            CampaignRecipient recipient, Instant now) {
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
    public CompletionStage<Boolean> cancelClaimed(CampaignRecipient recipient, Instant now) {
        requireUnpreparedClaim(recipient);
        long nowMillis = requireNonNegative(now, "now");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET state = 'CANCELLED', "
                            + "claim_token = NULL, claim_expires_at = NULL, next_attempt_at = NULL, "
                            + "updated_at = ? WHERE campaign_id = ? AND recipient_key = ? "
                            + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id IS NULL "
                            + "AND claim_token = ? AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                            + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
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
            PreparedDistributionDelivery delivery, Instant now) {
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
        return delegate.completePrepared(delivery, inventorySlot, afterFingerprint, completedAt);
    }

    @Override
    public CompletionStage<Boolean> moveClaimedToReview(
            CampaignRecipient recipient, String reason, Instant reviewedAt) {
        return delegate.moveClaimedToReview(recipient, reason, reviewedAt);
    }

    @Override
    public CompletionStage<Boolean> movePreparedToReview(
            PreparedDistributionDelivery delivery, String reason, Instant reviewedAt) {
        return delegate.movePreparedToReview(delivery, reason, reviewedAt);
    }

    @Override
    public CompletionStage<Integer> wakePlayer(UUID playerId, Instant now, int limit) {
        return delegate.wakePlayer(playerId, now, limit);
    }

    @Override
    public CompletionStage<Integer> recoverExpiredClaims(Instant now, int limit) {
        return delegate.recoverExpiredClaims(now, limit);
    }

    private static boolean cancelPrepared(
            java.sql.Connection connection,
            PreparedDistributionDelivery delivery,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'CANCELLED', instance_id = NULL, "
                        + "claim_token = NULL, claim_expires_at = NULL, next_attempt_at = NULL, "
                        + "updated_at = ? WHERE campaign_id = ? AND recipient_key = ? "
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id = ? "
                        + "AND claim_token = ? AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                        + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
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
                "DELETE FROM lore_instances WHERE instance_id = ? AND lifecycle_state = 'ACTIVE'")) {
            statement.setString(1, delivery.instanceId().value().toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Cancelled campaign instance could not be safely discarded");
            }
        }
        return true;
    }

    private static void deletePreparedTracking(
            java.sql.Connection connection,
            PreparedDistributionDelivery delivery) throws SQLException {
        String path = deliveryPath(delivery);
        try (PreparedStatement current = connection.prepareStatement(
                "DELETE FROM instance_current_state WHERE instance_id = ? "
                        + "AND state = 'CONFIRMED_NOW' AND location_type = 'QUEUED_DELIVERY' "
                        + "AND location_key = ? AND container_path = ?")) {
            current.setString(1, delivery.instanceId().value().toString());
            current.setString(2, delivery.playerId().toString());
            current.setString(3, path);
            if (current.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Cancelled prepared campaign current-state evidence changed");
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
                throw new SQLException("Cancelled prepared campaign queued observation changed");
            }
        }
    }

    private static void requireUnpreparedClaim(CampaignRecipient recipient) {
        Objects.requireNonNull(recipient, "recipient");
        if (recipient.state() != CampaignRecipientState.RESERVED_IN_FLIGHT
                || recipient.playerId() == null
                || recipient.claimToken() == null
                || recipient.instanceId() != null) {
            throw new IllegalArgumentException("Recipient must be an unprepared campaign claim");
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
}
