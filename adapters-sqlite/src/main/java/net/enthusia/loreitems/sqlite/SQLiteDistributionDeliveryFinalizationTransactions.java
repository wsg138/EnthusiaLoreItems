package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.CLEAR_CLAIM_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.CLEAR_RETRY_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.QUEUED_LOCATION_PREDICATE_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.RECIPIENT_PREDICATE_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.RESERVED_INSTANCE_PREDICATE_SQL;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.SINGLE_ROW;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.appendCampaignAudit;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.deliveryPath;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.escapeJson;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.inventoryPath;
import static net.enthusia.loreitems.sqlite.SQLiteDistributionDeliverySupport.reviewDetail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;

final class SQLiteDistributionDeliveryFinalizationTransactions {
    private static final String COMPLETED_SOURCE = "campaign-delivery-completed";
    private static final String DELIVERED_EVENT = "DISTRIBUTION_RECIPIENT_DELIVERED";
    private static final String REVIEW_EVENT = "DISTRIBUTION_RECIPIENT_REVIEW_REQUIRED";

    private SQLiteDistributionDeliveryFinalizationTransactions() {
    }

    static boolean completePrepared(
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
            throw new SQLException(
                    "Campaign recipient lost its durable completion transition");
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
                        + "AND recipient.state = 'RESERVED_IN_FLIGHT' "
                        + "AND recipient.instance_id = ? "
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
                "INSERT INTO instance_observations(instance_id, definition_id, "
                        + "location_type, location_key, container_path, confidence, "
                        + "source, observed_at) "
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
                    throw new SQLException(
                            "Campaign completion observation did not return an identifier");
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
                        + "location_type = 'PLAYER_INVENTORY', location_key = ?, "
                        + "container_path = ?, last_observation_id = ?, "
                        + "state_revision = state_revision + 1, updated_at = ? "
                        + "WHERE instance_id = ? AND state = 'CONFIRMED_NOW' "
                        + QUEUED_LOCATION_PREDICATE_SQL
                        + "AND container_path = ? AND state_revision = 1")) {
            statement.setString(1, delivery.playerId().toString());
            statement.setString(2, inventoryPath(inventorySlot));
            statement.setLong(3, observationId);
            statement.setLong(4, completedAt);
            statement.setString(5, delivery.instanceId().value().toString());
            statement.setString(6, delivery.playerId().toString());
            statement.setString(
                    7, deliveryPath(delivery.campaignId(), delivery.recipientKey()));
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException(
                        "Campaign current-state verification lost its queued evidence");
            }
        }
    }

    private static boolean markRecipientDelivered(
            Connection connection,
            PreparedDistributionDelivery delivery,
            long completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'DELIVERED', "
                        + CLEAR_CLAIM_SQL
                        + "next_attempt_at = NULL, delivered_at = ?, updated_at = ? "
                        + RECIPIENT_PREDICATE_SQL
                        + RESERVED_INSTANCE_PREDICATE_SQL
                        + "AND player_id = ? AND claim_token = ? "
                        + "AND claim_expires_at > ?")) {
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
                "UPDATE distribution_campaigns SET state = 'COMPLETED', "
                        + "updated_at = ?, terminal_at = ? "
                        + "WHERE campaign_id = ? AND state IN ('ACTIVE', 'PAUSED') "
                        + "AND terminal_at IS NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM distribution_recipients "
                        + "WHERE campaign_id = ? AND state <> 'DELIVERED')")) {
            statement.setLong(1, completedAt);
            statement.setLong(2, completedAt);
            statement.setString(3, campaignId.toString());
            statement.setString(4, campaignId.toString());
            statement.executeUpdate();
        }
    }

    static boolean moveClaimedToReview(
            Connection connection,
            CampaignRecipient recipient,
            String reason,
            long reviewedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', "
                        + CLEAR_CLAIM_SQL
                        + CLEAR_RETRY_SQL
                        + RECIPIENT_PREDICATE_SQL
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

    static boolean movePreparedToReview(
            Connection connection,
            PreparedDistributionDelivery delivery,
            String reason,
            long reviewedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED', "
                        + CLEAR_CLAIM_SQL
                        + CLEAR_RETRY_SQL
                        + RECIPIENT_PREDICATE_SQL
                        + RESERVED_INSTANCE_PREDICATE_SQL
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
                        + "location_type = NULL, location_key = NULL, "
                        + "container_path = NULL, last_observation_id = NULL, "
                        + "state_revision = state_revision + 1, updated_at = ? "
                        + "WHERE instance_id = ? AND state = 'CONFIRMED_NOW' "
                        + QUEUED_LOCATION_PREDICATE_SQL
                        + "AND container_path = ?")) {
            statement.setLong(1, reviewedAt);
            statement.setString(2, delivery.instanceId().value().toString());
            statement.setString(3, delivery.playerId().toString());
            statement.setString(
                    4, deliveryPath(delivery.campaignId(), delivery.recipientKey()));
            statement.executeUpdate();
        }
    }
}
