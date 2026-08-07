package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.CLEAR_CLAIM_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.CLEAR_RETRY_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.RECIPIENT_PREDICATE_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.SINGLE_ROW;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.appendCampaignAudit;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.deliveryPath;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.reviewDetail;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.requireNonNegative;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DistributionDeliveryRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.LoreInstanceId;

/** Adds cancellation-safe terminalization and recovery around the normal delivery repository. */
public final class SQLiteCancellableDistributionDeliveryRepository
        implements DistributionDeliveryRepository {
    private static final String QUEUED_SOURCE = "campaign-delivery-queued";
    private static final String REVIEW_EVENT = "DISTRIBUTION_RECIPIENT_REVIEW_REQUIRED";
    private static final String NOW_ARGUMENT = "now";
    private static final int MIN_LIMIT = 1;

    private final SQLiteStorageRuntime storage;
    private final SQLiteDistributionDeliveryRepository delegate;

    public SQLiteCancellableDistributionDeliveryRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        delegate = new SQLiteDistributionDeliveryRepository(storage);
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
    public CompletionStage<Boolean> cancelClaimed(
            CampaignRecipient recipient, Instant now) {
        requireUnpreparedClaim(recipient);
        long nowMillis = requireNonNegative(
                Objects.requireNonNull(now, NOW_ARGUMENT), NOW_ARGUMENT);
        return storage.execute(connection -> cancelClaimed(connection, recipient, nowMillis));
    }

    @Override
    public CompletionStage<Boolean> cancelPrepared(
            PreparedDistributionDelivery delivery, Instant now) {
        Objects.requireNonNull(delivery, "delivery");
        long nowMillis = requireNonNegative(
                Objects.requireNonNull(now, NOW_ARGUMENT), NOW_ARGUMENT);
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
        long nowMillis = requireNonNegative(
                Objects.requireNonNull(now, NOW_ARGUMENT), NOW_ARGUMENT);
        requireLimit(limit);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                        connection,
                        transaction -> recoverCancelledClaims(transaction, nowMillis, limit)))
                .thenCompose(cancelled -> recoverOrdinaryClaims(now, limit, cancelled));
    }

    private CompletionStage<Integer> recoverOrdinaryClaims(
            Instant now, int limit, int cancelled) {
        if (cancelled > 0) {
            return CompletableFuture.completedFuture(cancelled);
        }
        return delegate.recoverExpiredClaims(now, limit);
    }

    private static boolean cancelClaimed(
            Connection connection, CampaignRecipient recipient, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'CANCELLED', "
                        + CLEAR_CLAIM_SQL
                        + CLEAR_RETRY_SQL
                        + RECIPIENT_PREDICATE_SQL
                        + "AND state = 'RESERVED_IN_FLIGHT' AND instance_id IS NULL "
                        + "AND claim_token = ? "
                        + cancelledCampaignExistsSql())) {
            statement.setLong(1, now);
            statement.setString(2, recipient.campaignId().toString());
            statement.setString(3, recipient.recipientKey().value());
            statement.setString(4, recipient.claimToken());
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static boolean cancelPrepared(
            Connection connection,
            PreparedDistributionDelivery delivery,
            long now) throws SQLException {
        if (!markPreparedCancelled(connection, delivery, now)) {
            return false;
        }
        deletePreparedTracking(connection, delivery);
        deletePreparedInstance(connection, delivery);
        return true;
    }

    private static boolean markPreparedCancelled(
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
                        + cancelledCampaignExistsSql())) {
            statement.setLong(1, now);
            statement.setString(2, delivery.campaignId().toString());
            statement.setString(3, delivery.recipientKey().value());
            statement.setString(4, delivery.instanceId().value().toString());
            statement.setString(5, delivery.claimToken());
            return statement.executeUpdate() == SINGLE_ROW;
        }
    }

    private static String cancelledCampaignExistsSql() {
        return "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign "
                + "WHERE campaign.campaign_id = distribution_recipients.campaign_id "
                + "AND campaign.state = 'CANCELLED')";
    }

    private static void deletePreparedTracking(
            Connection connection,
            PreparedDistributionDelivery delivery) throws SQLException {
        String path = deliveryPath(delivery.campaignId(), delivery.recipientKey());
        deletePreparedCurrentState(connection, delivery, path);
        deletePreparedObservation(connection, delivery, path);
    }

    private static void deletePreparedCurrentState(
            Connection connection,
            PreparedDistributionDelivery delivery,
            String path) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM instance_current_state WHERE instance_id = ? "
                        + "AND state = 'CONFIRMED_NOW' AND location_type = 'QUEUED_DELIVERY' "
                        + "AND location_key = ? AND container_path = ?")) {
            statement.setString(1, delivery.instanceId().value().toString());
            statement.setString(2, delivery.playerId().toString());
            statement.setString(3, path);
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Cancelled prepared campaign current-state evidence changed");
            }
        }
    }

    private static void deletePreparedObservation(
            Connection connection,
            PreparedDistributionDelivery delivery,
            String path) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM instance_observations WHERE instance_id = ? AND source = ? "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ?")) {
            statement.setString(1, delivery.instanceId().value().toString());
            statement.setString(2, QUEUED_SOURCE);
            statement.setString(3, delivery.playerId().toString());
            statement.setString(4, path);
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Cancelled prepared campaign queued observation changed");
            }
        }
    }

    private static void deletePreparedInstance(
            Connection connection, PreparedDistributionDelivery delivery) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM lore_instances WHERE instance_id = ? "
                        + "AND lifecycle_state = 'ACTIVE'")) {
            statement.setString(1, delivery.instanceId().value().toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Cancelled campaign instance could not be safely discarded");
            }
        }
    }

    // The query is bounded by LIMIT; each row is an independent immutable claim snapshot.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static int recoverCancelledClaims(
            Connection connection, long now, int limit) throws SQLException {
        List<ExpiredCancelledClaim> claims = loadExpiredCancelledClaims(connection, now, limit);
        int recovered = 0;
        for (ExpiredCancelledClaim claim : claims) {
            recovered += claim.instanceId() == null
                    ? cancelExpiredUnprepared(connection, claim, now)
                    : reviewExpiredPrepared(connection, claim, now);
        }
        return recovered;
    }

    private static List<ExpiredCancelledClaim> loadExpiredCancelledClaims(
            Connection connection, long now, int limit) throws SQLException {
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
                    claims.add(readExpiredCancelledClaim(resultSet));
                }
            }
        }
        return claims;
    }

    private static ExpiredCancelledClaim readExpiredCancelledClaim(ResultSet resultSet)
            throws SQLException {
        String instance = resultSet.getString("instance_id");
        return new ExpiredCancelledClaim(
                UUID.fromString(resultSet.getString("campaign_id")),
                new CampaignRecipientKey(resultSet.getString("recipient_key")),
                UUID.fromString(resultSet.getString("player_id")),
                instance == null ? null : new LoreInstanceId(UUID.fromString(instance)),
                resultSet.getString("claim_token"));
    }

    private static int cancelExpiredUnprepared(
            Connection connection, ExpiredCancelledClaim claim, long now) throws SQLException {
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
            Connection connection, ExpiredCancelledClaim claim, long now) throws SQLException {
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
            Connection connection, ExpiredCancelledClaim claim, long now) throws SQLException {
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

    private static void appendCancellationReviewAudit(
            Connection connection, ExpiredCancelledClaim claim, long now) throws SQLException {
        appendCampaignAudit(
                connection,
                claim.campaignId(),
                REVIEW_EVENT,
                claim.playerId().toString(),
                reviewDetail(
                        claim.recipientKey(),
                        "Prepared delivery claim expired after campaign cancellation; "
                                + "physical insertion outcome is ambiguous."),
                now);
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
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
    }

    private record ExpiredCancelledClaim(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            UUID playerId,
            LoreInstanceId instanceId,
            String claimToken) {
    }
}
