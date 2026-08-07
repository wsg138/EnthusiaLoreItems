package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.domain.CampaignRecipientKey;

final class SQLiteDistributionDeliverySupport {
    static final String ACTIVE_CAMPAIGN_EXISTS_SQL =
            "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign ";
    static final String CLEAR_CLAIM_SQL =
            "claim_token = NULL, claim_expires_at = NULL, ";
    static final String CLEAR_RETRY_SQL =
            "next_attempt_at = NULL, updated_at = ? ";
    static final String RECIPIENT_PREDICATE_SQL =
            "WHERE campaign_id = ? AND recipient_key = ? ";
    static final String RESERVED_INSTANCE_PREDICATE_SQL =
            "AND state = 'RESERVED_IN_FLIGHT' AND instance_id = ? ";
    static final String QUEUED_LOCATION_PREDICATE_SQL =
            "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? ";
    static final int SINGLE_ROW = 1;

    private static final String AGGREGATE_TYPE = "DISTRIBUTION_CAMPAIGN";
    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final int JSON_CONTROL_CHARACTER_LIMIT = 0x20;

    private SQLiteDistributionDeliverySupport() {
    }

    static void appendCampaignAudit(
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

    static String deliveryPath(
            UUID campaignId,
            CampaignRecipientKey recipientKey) {
        return "campaign:" + campaignId + ":recipient:" + recipientKey.value();
    }

    static String inventoryPath(int slot) {
        return "storage:" + slot;
    }

    static String reviewDetail(
            CampaignRecipientKey recipientKey,
            String reason) {
        return "{\"recipientKey\":\"" + escapeJson(recipientKey.value())
                + "\",\"reason\":\"" + escapeJson(reason) + "\"}";
    }

    static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> appendOrdinaryCharacter(escaped, character);
            }
        }
        return escaped.toString();
    }

    private static void appendOrdinaryCharacter(StringBuilder escaped, char character) {
        if (character < JSON_CONTROL_CHARACTER_LIMIT) {
            escaped.append(String.format("\\u%04x", (int) character));
        } else {
            escaped.append(character);
        }
    }
}
