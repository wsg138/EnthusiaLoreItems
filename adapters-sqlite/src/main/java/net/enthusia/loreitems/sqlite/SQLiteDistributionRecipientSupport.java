package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.LoreInstanceId;

final class SQLiteDistributionRecipientSupport {
    static final String CAMPAIGN_ID_ARGUMENT = "campaignId";
    static final String RECIPIENT_KEY_ARGUMENT = "recipientKey";
    static final String STATE_ARGUMENT = "state";
    static final String NOW_ARGUMENT = "now";
    static final String STATE_COLUMN = "state";
    static final int SINGLE_UPDATED_ROW = 1;

    private static final String DRAFT_STATE = "DRAFT";
    static final long MIN_LEASE_MILLIS = 1L;
    private static final long UNIX_EPOCH_MILLIS = 0L;
    private static final String RECIPIENT_KEY_PREDICATE =
            "WHERE campaign_id = ? AND recipient_key = ? ";
    private static final String CAMPAIGN_EXISTS_PREFIX =
            "AND EXISTS (SELECT 1 FROM distribution_campaigns campaign ";
    private static final String CAMPAIGN_CORRELATION =
            "WHERE campaign.campaign_id = distribution_recipients.campaign_id ";
    private static final String CLEAR_NEXT_ATTEMPT =
            "next_attempt_at = NULL, updated_at = ? ";

    private SQLiteDistributionRecipientSupport() {
    }

    static void validateInitialRecipient(UUID campaignId, CampaignRecipient recipient) {
        Objects.requireNonNull(recipient, "recipient");
        if (!campaignId.equals(recipient.campaignId())) {
            throw new IllegalArgumentException("Recipient belongs to another campaign");
        }
        validateInitialState(recipient);
        validateInitialWorkflowMetadata(recipient);
    }

    private static void validateInitialState(CampaignRecipient recipient) {
        if (recipient.state() != CampaignRecipientState.PENDING_NAME
                && recipient.state() != CampaignRecipientState.PENDING_OFFLINE) {
            throw new IllegalArgumentException(
                    "Initial recipients must be unresolved names or UUID-bound offline recipients");
        }
    }

    private static void validateInitialWorkflowMetadata(CampaignRecipient recipient) {
        if (recipient.instanceId() != null
                || recipient.claimToken() != null
                || recipient.claimExpiresAtEpochMillis() != null
                || recipient.attemptCount() != 0
                || recipient.nextAttemptAtEpochMillis() != null
                || recipient.deliveredAtEpochMillis() != null) {
            throw new IllegalArgumentException("Initial recipient contains mutable workflow metadata");
        }
    }

    static void requireDraftCampaign(Connection connection, UUID campaignId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM distribution_campaigns WHERE campaign_id = ?")) {
            statement.setString(1, campaignId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Unknown distribution campaign");
                }
                if (!DRAFT_STATE.equals(resultSet.getString(STATE_COLUMN))) {
                    throw new IllegalStateException(
                            "Distribution recipient snapshot is sealed after activation");
                }
            }
        }
    }

    static void insertBatch(Connection connection, List<CampaignRecipient> recipients)
            throws SQLException {
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

    static Page<CampaignRecipient> claimPending(
            Connection connection,
            UUID campaignId,
            String claimToken,
            long now,
            long expiresAt,
            int limit) throws SQLException {
        List<CampaignRecipient> candidates = findClaimCandidates(connection, campaignId, now, limit);
        boolean hasMore = trimLookahead(candidates, limit);
        List<CampaignRecipient> claimed =
                reserveCandidates(connection, campaignId, claimToken, now, expiresAt, candidates);
        return new Page<>(claimed, 0, limit, hasMore);
    }

    private static List<CampaignRecipient> findClaimCandidates(
            Connection connection, UUID campaignId, long now, int limit) throws SQLException {
        List<CampaignRecipient> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                selectColumns() + " WHERE campaign_id = ? "
                        + "AND state IN ('PENDING_OFFLINE', 'PENDING_SPACE') "
                        + "AND player_id IS NOT NULL "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + CAMPAIGN_EXISTS_PREFIX
                        + CAMPAIGN_CORRELATION
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
        return candidates;
    }

    private static boolean trimLookahead(List<?> values, int limit) {
        boolean hasMore = values.size() > limit;
        if (hasMore) {
            values.remove(values.size() - 1);
        }
        return hasMore;
    }

    private static List<CampaignRecipient> reserveCandidates(
            Connection connection,
            UUID campaignId,
            String claimToken,
            long now,
            long expiresAt,
            List<CampaignRecipient> candidates) throws SQLException {
        List<CampaignRecipient> claimed = new ArrayList<>(candidates.size());
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE distribution_recipients SET state = 'RESERVED', claim_token = ?, "
                        + "claim_expires_at = ?, attempt_count = attempt_count + 1, "
                        + CLEAR_NEXT_ATTEMPT
                        + RECIPIENT_KEY_PREDICATE
                        + "AND state = ? "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + CAMPAIGN_EXISTS_PREFIX
                        + CAMPAIGN_CORRELATION
                        + "AND campaign.state = 'ACTIVE')")) {
            for (CampaignRecipient candidate : candidates) {
                bindReservation(update, campaignId, claimToken, now, expiresAt, candidate);
                if (update.executeUpdate() == SINGLE_UPDATED_ROW) {
                    claimed.add(reserved(candidate, claimToken, now, expiresAt));
                }
            }
        }
        return claimed;
    }

    private static void bindReservation(
            PreparedStatement update,
            UUID campaignId,
            String claimToken,
            long now,
            long expiresAt,
            CampaignRecipient candidate) throws SQLException {
        update.setString(1, claimToken);
        update.setLong(2, expiresAt);
        update.setLong(3, now);
        update.setString(4, campaignId.toString());
        update.setString(5, candidate.recipientKey().value());
        update.setString(6, candidate.state().name());
        update.setLong(7, now);
    }

    private static CampaignRecipient reserved(
            CampaignRecipient candidate, String claimToken, long now, long expiresAt) {
        return new CampaignRecipient(
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
                now);
    }

    static Page<CampaignRecipient> readPage(
            PreparedStatement statement, PageRequest request) throws SQLException {
        List<CampaignRecipient> recipients = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                recipients.add(readRecipient(resultSet));
            }
        }
        boolean hasMore = trimLookahead(recipients, request.limit());
        return new Page<>(recipients, request.offset(), request.limit(), hasMore);
    }

    static String selectColumns() {
        return "SELECT campaign_id, recipient_key, snapshot_index, original_value, "
                + "player_id, state, instance_id, claim_token, claim_expires_at, "
                + "attempt_count, next_attempt_at, delivered_at, updated_at "
                + "FROM distribution_recipients";
    }

    static CampaignRecipient readRecipient(ResultSet resultSet) throws SQLException {
        String playerValue = resultSet.getString("player_id");
        String instanceValue = resultSet.getString("instance_id");
        return new CampaignRecipient(
                UUID.fromString(resultSet.getString("campaign_id")),
                new CampaignRecipientKey(resultSet.getString("recipient_key")),
                resultSet.getInt("snapshot_index"),
                resultSet.getString("original_value"),
                playerValue == null ? null : UUID.fromString(playerValue),
                CampaignRecipientState.valueOf(resultSet.getString(STATE_COLUMN)),
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

    static long count(Map<CampaignRecipientState, Long> counts, CampaignRecipientState state) {
        return counts.getOrDefault(state, 0L);
    }

    static String normalizeClaimToken(String claimToken) {
        Objects.requireNonNull(claimToken, "claimToken");
        String normalized = claimToken.strip();
        if (normalized.isEmpty()
                || normalized.length() > CampaignRecipient.MAX_CLAIM_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid claim token");
        }
        return normalized;
    }

    static long requireNonNegative(Instant instant, String name) {
        long value = instant.toEpochMilli();
        if (value < UNIX_EPOCH_MILLIS) {
            throw new IllegalArgumentException(name + " must not precede the Unix epoch");
        }
        return value;
    }
}
