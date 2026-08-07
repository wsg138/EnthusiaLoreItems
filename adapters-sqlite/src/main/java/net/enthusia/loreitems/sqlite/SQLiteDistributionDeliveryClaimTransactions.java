package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.ACTIVE_CAMPAIGN_EXISTS_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.CLEAR_CLAIM_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.CLEAR_RETRY_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.QUEUED_LOCATION_PREDICATE_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.RECIPIENT_PREDICATE_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.RESERVED_INSTANCE_PREDICATE_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.SINGLE_ROW;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.appendCampaignAudit;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.deliveryPath;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.reviewDetail;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.readRecipient;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionRecipientSupport.selectColumns;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.LoreInstanceId;

final class SQLiteDistributionDeliveryClaimTransactions {
    private static final String REVIEW_EVENT = "DISTRIBUTION_RECIPIENT_REVIEW_REQUIRED";

    private SQLiteDistributionDeliveryClaimTransactions() {
    }

    static Page<CampaignRecipient> claimPending(
            Connection connection,
            String claimToken,
            long now,
            long expiresAt,
            int limit) throws SQLException {
        CandidatePage candidatePage = loadClaimCandidates(connection, now, limit);
        List<CampaignRecipient> claimed = claimCandidates(
                connection, candidatePage.candidates(), claimToken, now, expiresAt);
        return new Page<>(claimed, 0, limit, candidatePage.hasMore());
    }

    private static CandidatePage loadClaimCandidates(
            Connection connection,
            long now,
            int limit) throws SQLException {
        List<CampaignRecipient> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                selectColumns() + " recipient WHERE recipient.state IN "
                        + "('QUEUED_OFFLINE', 'QUEUED_INVENTORY_FULL') "
                        + "AND recipient.player_id IS NOT NULL "
                        + "AND (recipient.next_attempt_at IS NULL "
                        + "OR recipient.next_attempt_at <= ?) "
                        + ACTIVE_CAMPAIGN_EXISTS_SQL
                        + "WHERE campaign.campaign_id = recipient.campaign_id "
                        + "AND campaign.state = 'ACTIVE') "
                        + "ORDER BY recipient.updated_at, recipient.campaign_id, "
                        + "recipient.snapshot_index "
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
        return new CandidatePage(candidates, hasMore);
    }

    private static List<CampaignRecipient> claimCandidates(
            Connection connection,
            List<CampaignRecipient> candidates,
            String claimToken,
            long now,
            long expiresAt) throws SQLException {
        List<CampaignRecipient> claimed = new ArrayList<>(candidates.size());
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'RESERVED_IN_FLIGHT', "
                        + "claim_token = ?, claim_expires_at = ?, "
                        + "attempt_count = attempt_count + 1, "
                        + CLEAR_RETRY_SQL
                        + RECIPIENT_PREDICATE_SQL
                        + "AND state = ? AND instance_id IS NULL "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + ACTIVE_CAMPAIGN_EXISTS_SQL
                        + "WHERE campaign.campaign_id = "
                        + "distribution_recipients.campaign_id "
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
        return claimed;
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

    static int wakePlayer(
            Connection connection,
            UUID playerId,
            long now,
            int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET next_attempt_at = NULL, updated_at = ? "
                        + "WHERE rowid IN (SELECT recipient.rowid "
                        + "FROM distribution_recipients recipient "
                        + "JOIN distribution_campaigns campaign "
                        + "ON campaign.campaign_id = recipient.campaign_id "
                        + "WHERE recipient.player_id = ? "
                        + "AND recipient.state IN ('QUEUED_OFFLINE', "
                        + "'QUEUED_INVENTORY_FULL') "
                        + "AND recipient.next_attempt_at IS NOT NULL "
                        + "AND campaign.state = 'ACTIVE' "
                        + "ORDER BY recipient.updated_at, recipient.campaign_id, "
                        + "recipient.snapshot_index "
                        + "LIMIT ?)")) {
            statement.setLong(1, now);
            statement.setString(2, playerId.toString());
            statement.setInt(3, limit);
            return statement.executeUpdate();
        }
    }

    // This is a bounded LIMIT query. Each row becomes an independent immutable recovery value.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    static int recoverExpiredClaims(
            Connection connection,
            long now,
            int limit) throws SQLException {
        List<ExpiredClaim> expired = loadExpiredClaims(connection, now, limit);
        int recovered = 0;
        for (ExpiredClaim claim : expired) {
            recovered += claim.instanceId() == null
                    ? recoverUnprepared(connection, claim, now)
                    : recoverPrepared(connection, claim, now);
        }
        return recovered;
    }

    private static List<ExpiredClaim> loadExpiredClaims(
            Connection connection,
            long now,
            int limit) throws SQLException {
        List<ExpiredClaim> expired = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT campaign_id, recipient_key, player_id, instance_id, claim_token "
                        + "FROM distribution_recipients "
                        + "WHERE state = 'RESERVED_IN_FLIGHT' "
                        + "AND claim_expires_at <= ? "
                        + "ORDER BY claim_expires_at, campaign_id, snapshot_index "
                        + "LIMIT ?")) {
            statement.setLong(1, now);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    expired.add(readExpiredClaim(resultSet));
                }
            }
        }
        return expired;
    }

    private static ExpiredClaim readExpiredClaim(ResultSet resultSet) throws SQLException {
        String instanceValue = resultSet.getString("instance_id");
        return new ExpiredClaim(
                UUID.fromString(resultSet.getString("campaign_id")),
                new CampaignRecipientKey(resultSet.getString("recipient_key")),
                UUID.fromString(resultSet.getString("player_id")),
                instanceValue == null
                        ? null
                        : new LoreInstanceId(UUID.fromString(instanceValue)),
                resultSet.getString("claim_token"));
    }

    private static int recoverUnprepared(
            Connection connection,
            ExpiredClaim claim,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'QUEUED_OFFLINE', "
                        + CLEAR_CLAIM_SQL
                        + "next_attempt_at = ?, updated_at = ? "
                        + RECIPIENT_PREDICATE_SQL
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
                "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', "
                        + CLEAR_CLAIM_SQL
                        + CLEAR_RETRY_SQL
                        + RECIPIENT_PREDICATE_SQL
                        + RESERVED_INSTANCE_PREDICATE_SQL
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
                                "Prepared campaign delivery claim expired "
                                        + "before durable completion."),
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
                        + "location_type = NULL, location_key = NULL, "
                        + "container_path = NULL, last_observation_id = NULL, "
                        + "state_revision = state_revision + 1, updated_at = ? "
                        + "WHERE instance_id = ? AND state = 'CONFIRMED_NOW' "
                        + QUEUED_LOCATION_PREDICATE_SQL
                        + "AND container_path = ?")) {
            statement.setLong(1, now);
            statement.setString(2, claim.instanceId().value().toString());
            statement.setString(3, claim.playerId().toString());
            statement.setString(
                    4, deliveryPath(claim.campaignId(), claim.recipientKey()));
            statement.executeUpdate();
        }
    }

    private record CandidatePage(List<CampaignRecipient> candidates, boolean hasMore) {
        private CandidatePage {
            candidates = List.copyOf(candidates);
        }
    }

    private record ExpiredClaim(
            UUID campaignId,
            CampaignRecipientKey recipientKey,
            UUID playerId,
            LoreInstanceId instanceId,
            String claimToken) {
    }
}
