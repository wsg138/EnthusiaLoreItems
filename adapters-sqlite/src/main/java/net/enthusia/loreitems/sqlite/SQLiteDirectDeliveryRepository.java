package net.enthusia.loreitems.sqlite;

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
import net.enthusia.loreitems.application.DirectDeliveryRecord;
import net.enthusia.loreitems.application.DirectDeliveryRepository;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.ExternalDeliveryAcceptance;
import net.enthusia.loreitems.application.ExternalDeliveryCommand;
import net.enthusia.loreitems.application.ExternalDeliveryOutcome;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PreparedDirectDelivery;
import net.enthusia.loreitems.domain.DirectDeliveryState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;

public final class SQLiteDirectDeliveryRepository implements DirectDeliveryRepository {
    private static final String NOW_ARGUMENT = "now";
    private static final String AGGREGATE_TYPE = "lore_instance";
    private static final String SYSTEM_ACTOR = "system";
    private static final String QUEUED_EVENT = "direct_delivery_queued";
    private static final String COMPLETED_EVENT = "direct_delivery_completed";
    private static final String REVIEW_EVENT = "direct_delivery_review_required";
    private static final String QUEUED_SOURCE = "direct-delivery-queued";
    private static final String COMPLETED_SOURCE = "direct-delivery-completed";
    private static final String REVIEW_REQUIRED_STATE = "REVIEW_REQUIRED";
    private static final String COMPLETED_STATE = "COMPLETED";
    private static final String DELIVERY_ID_COLUMN = "delivery_id";
    private static final String INSTANCE_ID_COLUMN = "instance_id";
    private static final String PLAYER_ID_COLUMN = "player_id";
    private static final long MIN_LEASE_MILLIS = 1L;
    private static final int SINGLE_UPDATED_ROW = 1;
    private static final int MAX_REVIEW_REASON_LENGTH = 4_096;
    private static final int MIN_INVENTORY_SLOT = 0;
    private static final int MAX_PLAYER_INVENTORY_SLOT = 35;
    private static final char FIRST_NON_CONTROL_CHARACTER = 0x20;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final SQLiteStorageRuntime storage;

    public SQLiteDirectDeliveryRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<ExternalDeliveryAcceptance> acceptExternal(
            ExternalDeliveryCommand command, Instant now) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(now, NOW_ARGUMENT);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection, transaction -> acceptExternal(transaction, command, now.toEpochMilli())));
    }

    @Override
    public CompletionStage<Page<DirectDeliveryRecord>> claimPending(
            String claimToken, Instant now, Duration lease, int limit) {
        ClaimArguments arguments = validateClaimArguments(claimToken, now, lease, limit);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(connection, transaction -> {
            Page<PreparedDirectDelivery> prepared = claimPreparedPending(
                    transaction,
                    arguments.claimToken(),
                    arguments.nowMillis(),
                    arguments.leaseMillis(),
                    limit);
            return new Page<>(
                    prepared.items().stream().map(PreparedDirectDelivery::record).toList(),
                    prepared.offset(),
                    prepared.limit(),
                    prepared.hasMore());
        }));
    }

    @Override
    public CompletionStage<Page<PreparedDirectDelivery>> claimPreparedPending(
            String claimToken, Instant now, Duration lease, int limit) {
        ClaimArguments arguments = validateClaimArguments(claimToken, now, lease, limit);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> claimPreparedPending(
                        transaction,
                        arguments.claimToken(),
                        arguments.nowMillis(),
                        arguments.leaseMillis(),
                        limit)));
    }

    @Override
    public CompletionStage<Boolean> deferClaimed(
            UUID deliveryId,
            String claimToken,
            Instant now,
            Instant nextAttemptAt) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        String normalizedToken = requireClaimToken(claimToken);
        Objects.requireNonNull(now, NOW_ARGUMENT);
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (nextAttemptAt.isBefore(now)) {
            throw new IllegalArgumentException("nextAttemptAt must not precede now");
        }
        DirectDeliveryState.RESERVED.transitionTo(DirectDeliveryState.PENDING);
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE direct_deliveries SET state = 'PENDING', claim_token = NULL, "
                            + "claim_expires_at = NULL, next_attempt_at = ?, updated_at = ? "
                            + "WHERE delivery_id = ? AND state = 'RESERVED' AND claim_token = ? "
                            + "AND claim_expires_at > ?")) {
                statement.setLong(1, nextAttemptAt.toEpochMilli());
                statement.setLong(2, now.toEpochMilli());
                statement.setString(3, deliveryId.toString());
                statement.setString(4, normalizedToken);
                statement.setLong(5, now.toEpochMilli());
                return statement.executeUpdate() == SINGLE_UPDATED_ROW;
            }
        });
    }

    @Override
    public CompletionStage<Boolean> completeClaimed(
            PreparedDirectDelivery delivery,
            int inventorySlot,
            String afterFingerprint,
            Instant completedAt) {
        Objects.requireNonNull(delivery, "delivery");
        requireInventorySlot(inventorySlot);
        String normalizedFingerprint = requireFingerprint(afterFingerprint);
        Objects.requireNonNull(completedAt, "completedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> completeClaimedInTransaction(
                        transaction,
                        delivery,
                        inventorySlot,
                        normalizedFingerprint,
                        completedAt.toEpochMilli())));
    }

    @Override
    public CompletionStage<Boolean> moveClaimedToReview(
            PreparedDirectDelivery delivery,
            String reason,
            Instant reviewedAt) {
        Objects.requireNonNull(delivery, "delivery");
        String normalizedReason = requireReviewReason(reason);
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> moveClaimedToReviewInTransaction(
                        transaction,
                        delivery,
                        normalizedReason,
                        reviewedAt.toEpochMilli())));
    }

    @Override
    public CompletionStage<Integer> wakePendingForPlayer(
            UUID playerId,
            Instant now,
            int limit) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(now, NOW_ARGUMENT);
        requireBoundedLimit(limit);
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE direct_deliveries SET next_attempt_at = NULL, updated_at = ? "
                            + "WHERE rowid IN (SELECT rowid FROM direct_deliveries "
                            + "WHERE player_id = ? AND state = 'PENDING' "
                            + "AND next_attempt_at IS NOT NULL "
                            + "ORDER BY created_at, delivery_id LIMIT ?)")) {
                statement.setLong(1, now.toEpochMilli());
                statement.setString(2, playerId.toString());
                statement.setInt(3, limit);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletionStage<Boolean> transitionClaimed(
            UUID deliveryId,
            DirectDeliveryState expected,
            DirectDeliveryState target,
            String claimToken,
            Instant now) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        String normalizedToken = requireClaimToken(claimToken);
        Objects.requireNonNull(now, NOW_ARGUMENT);
        expected.transitionTo(target);
        boolean clearClaim = target == DirectDeliveryState.PENDING
                || target == DirectDeliveryState.COMPLETED
                || target == DirectDeliveryState.REVIEW_REQUIRED;
        String sql = clearClaim
                ? "UPDATE direct_deliveries SET state = ?, claim_token = NULL, "
                        + "claim_expires_at = NULL, updated_at = ? "
                        + "WHERE delivery_id = ? AND state = ? AND claim_token = ? "
                        + "AND claim_expires_at > ?"
                : "UPDATE direct_deliveries SET state = ?, updated_at = ? "
                        + "WHERE delivery_id = ? AND state = ? AND claim_token = ? "
                        + "AND claim_expires_at > ?";
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, target.name());
                statement.setLong(2, now.toEpochMilli());
                statement.setString(3, deliveryId.toString());
                statement.setString(4, expected.name());
                statement.setString(5, normalizedToken);
                statement.setLong(6, now.toEpochMilli());
                return statement.executeUpdate() == SINGLE_UPDATED_ROW;
            }
        });
    }

    @Override
    public CompletionStage<Integer> moveExpiredClaimsToReview(Instant now, int limit) {
        Objects.requireNonNull(now, NOW_ARGUMENT);
        requireBoundedLimit(limit);
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> moveExpiredClaimsToReviewInTransaction(
                        transaction, now.toEpochMilli(), limit)));
    }

    @Override
    public CompletionStage<Page<DirectDeliveryRecord>> listNonTerminal(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            List<DirectDeliveryRecord> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM direct_deliveries "
                            + "WHERE state NOT IN ('COMPLETED', 'CANCELLED') "
                            + "ORDER BY created_at, delivery_id LIMIT ? OFFSET ?")) {
                statement.setInt(1, request.limit() + 1);
                statement.setInt(2, request.offset());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        records.add(readRecord(resultSet));
                    }
                }
            }
            boolean hasMore = records.size() > request.limit();
            if (hasMore) {
                records.remove(records.size() - 1);
            }
            return new Page<>(records, request.offset(), request.limit(), hasMore);
        });
    }

    private static ClaimArguments validateClaimArguments(
            String claimToken,
            Instant now,
            Duration lease,
            int limit) {
        String normalizedToken = requireClaimToken(claimToken);
        Objects.requireNonNull(now, NOW_ARGUMENT);
        Objects.requireNonNull(lease, "lease");
        requireBoundedLimit(limit);
        long leaseMillis = lease.toMillis();
        if (leaseMillis < MIN_LEASE_MILLIS) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return new ClaimArguments(normalizedToken, now.toEpochMilli(), leaseMillis);
    }

    private static String requireClaimToken(String claimToken) {
        Objects.requireNonNull(claimToken, "claimToken");
        String normalizedToken = claimToken.strip();
        if (normalizedToken.isEmpty()) {
            throw new IllegalArgumentException("claimToken must not be blank");
        }
        return normalizedToken;
    }

    private static void requireBoundedLimit(int limit) {
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
    }

    private static void requireInventorySlot(int inventorySlot) {
        if (inventorySlot < MIN_INVENTORY_SLOT || inventorySlot > MAX_PLAYER_INVENTORY_SLOT) {
            throw new IllegalArgumentException("inventorySlot must identify player storage");
        }
    }

    private static ExternalDeliveryAcceptance acceptExternal(
            Connection connection,
            ExternalDeliveryCommand command,
            long now) throws SQLException {
        ExistingRequest existing = findExistingRequest(connection, command.externalOperationId());
        if (existing != null) {
            return replayExisting(connection, command, existing, now);
        }
        DefinitionRevision definition = findDefinition(connection, command.definitionKey().value());
        if (definition == null) {
            return rejectUnknownDefinition(connection, command, now);
        }
        return acceptNewDelivery(connection, command, definition, now);
    }

    private static ExternalDeliveryAcceptance replayExisting(
            Connection connection,
            ExternalDeliveryCommand command,
            ExistingRequest existing,
            long now) throws SQLException {
        if (!existing.definitionKey().equals(command.definitionKey().value())
                || !existing.playerId().equals(command.playerId())) {
            return new ExternalDeliveryAcceptance(
                    ExternalDeliveryOutcome.VALIDATION_FAILURE,
                    command.externalOperationId(),
                    Optional.ofNullable(existing.deliveryId()),
                    "The external operation ID was already used with different arguments.");
        }
        ExternalDeliveryOutcome stored = ExternalDeliveryOutcome.valueOf(existing.outcome());
        if (stored == ExternalDeliveryOutcome.UNKNOWN_DEFINITION) {
            DefinitionRevision definition = findDefinition(connection, command.definitionKey().value());
            if (definition != null) {
                return acceptPreviouslyUnknownDelivery(connection, command, definition, now);
            }
        }
        ExternalDeliveryOutcome replay = stored == ExternalDeliveryOutcome.ACCEPTED_QUEUED
                ? ExternalDeliveryOutcome.ALREADY_ACCEPTED
                : stored;
        return new ExternalDeliveryAcceptance(
                replay,
                command.externalOperationId(),
                Optional.ofNullable(existing.deliveryId()),
                "Returned the durable result for the existing external operation.");
    }

    private static ExternalDeliveryAcceptance rejectUnknownDefinition(
            Connection connection,
            ExternalDeliveryCommand command,
            long now) throws SQLException {
        insertExternalRequest(
                connection,
                command,
                null,
                ExternalDeliveryOutcome.UNKNOWN_DEFINITION,
                now);
        return new ExternalDeliveryAcceptance(
                ExternalDeliveryOutcome.UNKNOWN_DEFINITION,
                command.externalOperationId(),
                Optional.empty(),
                "No active lore definition has that key.");
    }

    private static ExternalDeliveryAcceptance acceptNewDelivery(
            Connection connection,
            ExternalDeliveryCommand command,
            DefinitionRevision definition,
            long now) throws SQLException {
        return acceptDelivery(connection, command, definition, now, false);
    }

    private static ExternalDeliveryAcceptance acceptPreviouslyUnknownDelivery(
            Connection connection,
            ExternalDeliveryCommand command,
            DefinitionRevision definition,
            long now) throws SQLException {
        return acceptDelivery(connection, command, definition, now, true);
    }

    private static ExternalDeliveryAcceptance acceptDelivery(
            Connection connection,
            ExternalDeliveryCommand command,
            DefinitionRevision definition,
            long now,
            boolean replaceUnknownRequest) throws SQLException {
        UUID instanceId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        insertInstance(connection, instanceId, definition, now);
        long observationId = insertQueuedObservation(
                connection,
                instanceId,
                definition.definitionId(),
                deliveryId,
                command.playerId(),
                now);
        insertQueuedCurrentState(
                connection,
                instanceId,
                deliveryId,
                command.playerId(),
                observationId,
                now);
        insertDelivery(connection, deliveryId, instanceId, command, now);
        if (replaceUnknownRequest) {
            updateUnknownExternalRequest(connection, command, deliveryId, now);
        } else {
            insertExternalRequest(
                    connection,
                    command,
                    deliveryId,
                    ExternalDeliveryOutcome.ACCEPTED_QUEUED,
                    now);
        }
        appendAudit(
                connection,
                instanceId,
                QUEUED_EVENT,
                command.playerId().toString(),
                queuedDetail(deliveryId, command.playerId()),
                now);
        return new ExternalDeliveryAcceptance(
                ExternalDeliveryOutcome.ACCEPTED_QUEUED,
                command.externalOperationId(),
                Optional.of(deliveryId),
                "Durable delivery intent was accepted and queued for inventory execution.");
    }

    private static Page<PreparedDirectDelivery> claimPreparedPending(
            Connection connection,
            String claimToken,
            long now,
            long leaseMillis,
            int limit) throws SQLException {
        List<DeliveryCandidate> candidates = listClaimableCandidates(connection, now, limit);
        boolean hasMore = candidates.size() > limit;
        if (hasMore) {
            candidates.remove(candidates.size() - 1);
        }
        List<PreparedDirectDelivery> claimed = new ArrayList<>(candidates.size());
        long expiresAt = Math.addExact(now, leaseMillis);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE direct_deliveries SET state = 'RESERVED', claim_token = ?, "
                        + "claim_expires_at = ?, attempt_count = attempt_count + 1, "
                        + "updated_at = ? WHERE delivery_id = ? AND state = 'PENDING'")) {
            for (DeliveryCandidate candidate : candidates) {
                update.setString(1, claimToken);
                update.setLong(2, expiresAt);
                update.setLong(3, now);
                update.setString(4, candidate.deliveryId().toString());
                if (update.executeUpdate() == SINGLE_UPDATED_ROW) {
                    claimed.add(candidate.claimed(claimToken, expiresAt, now));
                }
            }
        }
        return new Page<>(claimed, 0, limit, hasMore);
    }

    private static List<DeliveryCandidate> listClaimableCandidates(
            Connection connection,
            long now,
            int limit) throws SQLException {
        List<DeliveryCandidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT d.delivery_id, d.instance_id, d.player_id, d.idempotency_key, "
                        + "d.attempt_count, d.created_at, d.updated_at, i.definition_id, "
                        + "i.applied_revision, r.codec_version, r.template_blob "
                        + "FROM direct_deliveries d "
                        + "JOIN lore_instances i ON i.instance_id = d.instance_id "
                        + "JOIN lore_definition_revisions r ON r.definition_id = i.definition_id "
                        + "AND r.revision = i.applied_revision "
                        + "WHERE d.state = 'PENDING' "
                        + "AND i.lifecycle_state = 'ACTIVE' "
                        + "AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= ?) "
                        + "ORDER BY d.created_at, d.delivery_id LIMIT ?")) {
            statement.setLong(1, now);
            statement.setInt(2, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(readCandidate(resultSet));
                }
            }
        }
        return candidates;
    }

    private static boolean completeClaimedInTransaction(
            Connection connection,
            PreparedDirectDelivery delivery,
            int inventorySlot,
            String afterFingerprint,
            long completedAt) throws SQLException {
        if (!transitionDelivery(
                connection,
                delivery,
                DirectDeliveryState.RESERVED,
                DirectDeliveryState.APPLIED,
                completedAt,
                false)) {
            return false;
        }
        long observationId = insertCompletedObservation(
                connection, delivery, inventorySlot, completedAt);
        updateCompletedCurrentState(
                connection, delivery, inventorySlot, observationId, completedAt);
        requireTransition(
                connection,
                delivery,
                DirectDeliveryState.APPLIED,
                DirectDeliveryState.VERIFIED,
                completedAt,
                false);
        requireTransition(
                connection,
                delivery,
                DirectDeliveryState.VERIFIED,
                DirectDeliveryState.COMPLETED,
                completedAt,
                true);
        appendAudit(
                connection,
                delivery.instanceId().value(),
                COMPLETED_EVENT,
                delivery.playerId().toString(),
                completedDetail(delivery, inventorySlot, afterFingerprint),
                completedAt);
        return true;
    }

    private static boolean moveClaimedToReviewInTransaction(
            Connection connection,
            PreparedDirectDelivery delivery,
            String reason,
            long reviewedAt) throws SQLException {
        DeliveryState state = findDeliveryState(connection, delivery.deliveryId());
        if (state == null || !state.instanceId().equals(delivery.instanceId().value())) {
            return false;
        }
        if (REVIEW_REQUIRED_STATE.equals(state.state())) {
            return true;
        }
        if (COMPLETED_STATE.equals(state.state())
                || !delivery.claimToken().equals(state.claimToken())) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE direct_deliveries SET state = 'REVIEW_REQUIRED', claim_token = NULL, "
                        + "claim_expires_at = NULL, updated_at = ? WHERE delivery_id = ? "
                        + "AND instance_id = ? AND claim_token = ? "
                        + "AND state IN ('RESERVED', 'APPLIED', 'VERIFIED')")) {
            statement.setLong(1, reviewedAt);
            statement.setString(2, delivery.deliveryId().toString());
            statement.setString(3, delivery.instanceId().value().toString());
            statement.setString(4, delivery.claimToken());
            if (statement.executeUpdate() != SINGLE_UPDATED_ROW) {
                return false;
            }
        }
        markQueuedCurrentStateUnresolved(
                connection,
                delivery.instanceId().value(),
                delivery.deliveryId(),
                delivery.playerId(),
                reviewedAt);
        appendAudit(
                connection,
                delivery.instanceId().value(),
                REVIEW_EVENT,
                delivery.playerId().toString(),
                reviewDetail(delivery.deliveryId(), reason),
                reviewedAt);
        return true;
    }

    private static int moveExpiredClaimsToReviewInTransaction(
            Connection connection,
            long now,
            int limit) throws SQLException {
        List<ExpiredClaim> expired = findExpiredClaims(connection, now, limit);
        int updated = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE direct_deliveries SET state = 'REVIEW_REQUIRED', claim_token = NULL, "
                        + "claim_expires_at = NULL, updated_at = ? WHERE delivery_id = ? "
                        + "AND state = ? AND claim_token = ? AND claim_expires_at <= ?")) {
            for (ExpiredClaim claim : expired) {
                statement.setLong(1, now);
                statement.setString(2, claim.deliveryId().toString());
                statement.setString(3, claim.state());
                statement.setString(4, claim.claimToken());
                statement.setLong(5, now);
                if (statement.executeUpdate() == SINGLE_UPDATED_ROW) {
                    updated++;
                    markQueuedCurrentStateUnresolved(
                            connection,
                            claim.instanceId(),
                            claim.deliveryId(),
                            claim.playerId(),
                            now);
                    appendAudit(
                            connection,
                            claim.instanceId(),
                            REVIEW_EVENT,
                            claim.playerId().toString(),
                            reviewDetail(
                                    claim.deliveryId(),
                                    "The delivery claim expired before safe completion."),
                            now);
                }
            }
        }
        return updated;
    }

    private static List<ExpiredClaim> findExpiredClaims(
            Connection connection,
            long now,
            int limit) throws SQLException {
        List<ExpiredClaim> expired = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT delivery_id, instance_id, player_id, state, claim_token "
                        + "FROM direct_deliveries WHERE state IN ('RESERVED', 'APPLIED', 'VERIFIED') "
                        + "AND claim_expires_at <= ? ORDER BY claim_expires_at, delivery_id LIMIT ?")) {
            statement.setLong(1, now);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    expired.add(new ExpiredClaim(
                            UUID.fromString(resultSet.getString(DELIVERY_ID_COLUMN)),
                            UUID.fromString(resultSet.getString(INSTANCE_ID_COLUMN)),
                            UUID.fromString(resultSet.getString(PLAYER_ID_COLUMN)),
                            resultSet.getString("state"),
                            resultSet.getString("claim_token")));
                }
            }
        }
        return expired;
    }

    private static boolean transitionDelivery(
            Connection connection,
            PreparedDirectDelivery delivery,
            DirectDeliveryState expected,
            DirectDeliveryState target,
            long now,
            boolean clearClaim) throws SQLException {
        expected.transitionTo(target);
        String sql = clearClaim
                ? "UPDATE direct_deliveries SET state = ?, claim_token = NULL, "
                        + "claim_expires_at = NULL, next_attempt_at = NULL, updated_at = ? "
                        + "WHERE delivery_id = ? AND instance_id = ? AND state = ? "
                        + "AND claim_token = ? AND claim_expires_at > ?"
                : "UPDATE direct_deliveries SET state = ?, updated_at = ? "
                        + "WHERE delivery_id = ? AND instance_id = ? AND state = ? "
                        + "AND claim_token = ? AND claim_expires_at > ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.name());
            statement.setLong(2, now);
            statement.setString(3, delivery.deliveryId().toString());
            statement.setString(4, delivery.instanceId().value().toString());
            statement.setString(5, expected.name());
            statement.setString(6, delivery.claimToken());
            statement.setLong(7, now);
            return statement.executeUpdate() == SINGLE_UPDATED_ROW;
        }
    }

    private static void requireTransition(
            Connection connection,
            PreparedDirectDelivery delivery,
            DirectDeliveryState expected,
            DirectDeliveryState target,
            long now,
            boolean clearClaim) throws SQLException {
        if (!transitionDelivery(connection, delivery, expected, target, now, clearClaim)) {
            throw new SQLException("Delivery lost transition " + expected + " -> " + target);
        }
    }

    private static ExistingRequest findExistingRequest(
            Connection connection,
            String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_key, player_id, delivery_id, outcome "
                        + "FROM external_delivery_requests WHERE external_operation_id = ?")) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String deliveryValue = resultSet.getString(DELIVERY_ID_COLUMN);
                return new ExistingRequest(
                        resultSet.getString("definition_key"),
                        UUID.fromString(resultSet.getString(PLAYER_ID_COLUMN)),
                        deliveryValue == null ? null : UUID.fromString(deliveryValue),
                        resultSet.getString("outcome"));
            }
        }
    }

    private static DeliveryState findDeliveryState(
            Connection connection,
            UUID deliveryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT instance_id, state, claim_token FROM direct_deliveries "
                        + "WHERE delivery_id = ?")) {
            statement.setString(1, deliveryId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new DeliveryState(
                                UUID.fromString(resultSet.getString(INSTANCE_ID_COLUMN)),
                                resultSet.getString("state"),
                                resultSet.getString("claim_token"))
                        : null;
            }
        }
    }

    private static DefinitionRevision findDefinition(
            Connection connection,
            String definitionKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, current_revision FROM lore_definitions "
                        + "WHERE lookup_key = ? AND deleted_at IS NULL")) {
            statement.setString(1, definitionKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new DefinitionRevision(
                        UUID.fromString(resultSet.getString("definition_id")),
                        resultSet.getInt("current_revision"));
            }
        }
    }

    private static void insertInstance(
            Connection connection,
            UUID instanceId,
            DefinitionRevision definition,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, instanceId.toString());
            statement.setString(2, definition.definitionId().toString());
            statement.setInt(3, definition.revision());
            statement.setInt(4, definition.revision());
            statement.setString(5, "ACTIVE");
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    private static long insertQueuedObservation(
            Connection connection,
            UUID instanceId,
            UUID definitionId,
            UUID deliveryId,
            UUID playerId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, 'QUEUED_DELIVERY', ?, ?, 'CONFIRMED_NOW', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, instanceId.toString());
            statement.setString(2, definitionId.toString());
            statement.setString(3, playerId.toString());
            statement.setString(4, deliveryPath(deliveryId));
            statement.setString(5, QUEUED_SOURCE);
            statement.setLong(6, now);
            statement.executeUpdate();
            return requireGeneratedKey(statement, "Queued delivery observation");
        }
    }

    private static void insertQueuedCurrentState(
            Connection connection,
            UUID instanceId,
            UUID deliveryId,
            UUID playerId,
            long observationId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_current_state(instance_id, state, location_type, "
                        + "location_key, container_path, last_observation_id, state_revision, "
                        + "updated_at) VALUES (?, 'CONFIRMED_NOW', 'QUEUED_DELIVERY', ?, ?, ?, 1, ?)")) {
            statement.setString(1, instanceId.toString());
            statement.setString(2, playerId.toString());
            statement.setString(3, deliveryPath(deliveryId));
            statement.setLong(4, observationId);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static void insertDelivery(
            Connection connection,
            UUID deliveryId,
            UUID instanceId,
            ExternalDeliveryCommand command,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO direct_deliveries(delivery_id, instance_id, player_id, state, "
                        + "idempotency_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, deliveryId.toString());
            statement.setString(2, instanceId.toString());
            statement.setString(3, command.playerId().toString());
            statement.setString(4, DirectDeliveryState.PENDING.name());
            statement.setString(5, command.externalOperationId());
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private static void insertExternalRequest(
            Connection connection,
            ExternalDeliveryCommand command,
            UUID deliveryId,
            ExternalDeliveryOutcome outcome,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO external_delivery_requests(external_operation_id, definition_key, "
                        + "player_id, delivery_id, outcome, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, command.externalOperationId());
            statement.setString(2, command.definitionKey().value());
            statement.setString(3, command.playerId().toString());
            statement.setString(4, deliveryId == null ? null : deliveryId.toString());
            statement.setString(5, outcome.name());
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private static void updateUnknownExternalRequest(
            Connection connection,
            ExternalDeliveryCommand command,
            UUID deliveryId,
            long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE external_delivery_requests SET delivery_id = ?, outcome = ?, updated_at = ? "
                        + "WHERE external_operation_id = ? AND definition_key = ? AND player_id = ? "
                        + "AND outcome = ? AND delivery_id IS NULL")) {
            statement.setString(1, deliveryId.toString());
            statement.setString(2, ExternalDeliveryOutcome.ACCEPTED_QUEUED.name());
            statement.setLong(3, now);
            statement.setString(4, command.externalOperationId());
            statement.setString(5, command.definitionKey().value());
            statement.setString(6, command.playerId().toString());
            statement.setString(7, ExternalDeliveryOutcome.UNKNOWN_DEFINITION.name());
            if (statement.executeUpdate() != SINGLE_UPDATED_ROW) {
                throw new SQLException("External delivery retry lost its durable unknown-definition fence");
            }
        }
    }

    private static long insertCompletedObservation(
            Connection connection,
            PreparedDirectDelivery delivery,
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
            return requireGeneratedKey(statement, "Completed delivery observation");
        }
    }

    private static void updateCompletedCurrentState(
            Connection connection,
            PreparedDirectDelivery delivery,
            int inventorySlot,
            long observationId,
            long completedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'CONFIRMED_NOW', "
                        + "location_type = 'PLAYER_INVENTORY', location_key = ?, "
                        + "container_path = ?, last_observation_id = ?, "
                        + "state_revision = state_revision + 1, updated_at = ? "
                        + "WHERE instance_id = ? AND state = 'CONFIRMED_NOW' "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ? AND state_revision = 1")) {
            statement.setString(1, delivery.playerId().toString());
            statement.setString(2, inventoryPath(inventorySlot));
            statement.setLong(3, observationId);
            statement.setLong(4, completedAt);
            statement.setString(5, delivery.instanceId().value().toString());
            statement.setString(6, delivery.playerId().toString());
            statement.setString(7, deliveryPath(delivery.deliveryId()));
            if (statement.executeUpdate() != SINGLE_UPDATED_ROW) {
                throw new SQLException("Delivery current-state verification lost its queued state");
            }
        }
    }


    private static void markQueuedCurrentStateUnresolved(
            Connection connection,
            UUID instanceId,
            UUID deliveryId,
            UUID playerId,
            long updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'MISSING_UNRESOLVED', "
                        + "location_type = NULL, location_key = NULL, container_path = NULL, "
                        + "last_observation_id = NULL, state_revision = state_revision + 1, "
                        + "updated_at = ? WHERE instance_id = ? AND state = 'CONFIRMED_NOW' "
                        + "AND location_type = 'QUEUED_DELIVERY' AND location_key = ? "
                        + "AND container_path = ?")) {
            statement.setLong(1, updatedAt);
            statement.setString(2, instanceId.toString());
            statement.setString(3, playerId.toString());
            statement.setString(4, deliveryPath(deliveryId));
            statement.executeUpdate();
        }
    }

    private static long requireGeneratedKey(
            PreparedStatement statement,
            String operation) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException(operation + " did not return an identifier");
            }
            return keys.getLong(1);
        }
    }

    private static void appendAudit(
            Connection connection,
            UUID instanceId,
            String eventType,
            String actorId,
            String detail,
            long occurredAt) throws SQLException {
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                AGGREGATE_TYPE,
                instanceId.toString(),
                eventType,
                SYSTEM_ACTOR,
                actorId,
                detail,
                occurredAt));
    }

    private static DirectDeliveryRecord readRecord(ResultSet resultSet) throws SQLException {
        long expires = resultSet.getLong("claim_expires_at");
        Long claimExpiresAt = resultSet.wasNull() ? null : expires;
        return new DirectDeliveryRecord(
                UUID.fromString(resultSet.getString(DELIVERY_ID_COLUMN)),
                new LoreInstanceId(UUID.fromString(resultSet.getString(INSTANCE_ID_COLUMN))),
                UUID.fromString(resultSet.getString(PLAYER_ID_COLUMN)),
                DirectDeliveryState.valueOf(resultSet.getString("state")),
                resultSet.getString("idempotency_key"),
                resultSet.getString("claim_token"),
                claimExpiresAt,
                resultSet.getInt("attempt_count"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    private static DeliveryCandidate readCandidate(ResultSet resultSet) throws SQLException {
        return new DeliveryCandidate(
                UUID.fromString(resultSet.getString(DELIVERY_ID_COLUMN)),
                new LoreInstanceId(UUID.fromString(resultSet.getString(INSTANCE_ID_COLUMN))),
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                UUID.fromString(resultSet.getString(PLAYER_ID_COLUMN)),
                new TemplateRevision(resultSet.getLong("applied_revision")),
                new EncodedItemTemplate(
                        resultSet.getInt("codec_version"),
                        resultSet.getBytes("template_blob")),
                resultSet.getString("idempotency_key"),
                resultSet.getInt("attempt_count"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    private static String queuedDetail(UUID deliveryId, UUID playerId) {
        return "{\"deliveryId\":\"" + deliveryId
                + "\",\"playerId\":\"" + playerId + "\"}";
    }

    private static String completedDetail(
            PreparedDirectDelivery delivery,
            int inventorySlot,
            String afterFingerprint) {
        return "{\"deliveryId\":\"" + delivery.deliveryId()
                + "\",\"playerId\":\"" + delivery.playerId()
                + "\",\"slot\":" + inventorySlot
                + ",\"revision\":" + delivery.appliedRevision().value()
                + ",\"afterFingerprint\":\"" + afterFingerprint + "\"}";
    }

    private static String reviewDetail(UUID deliveryId, String reason) {
        return "{\"deliveryId\":\"" + deliveryId
                + "\",\"reason\":\"" + escapeJson(reason) + "\"}";
    }

    private static String deliveryPath(UUID deliveryId) {
        return "delivery:" + deliveryId;
    }

    private static String inventoryPath(int slot) {
        return "storage:" + slot;
    }

    private static String requireFingerprint(String value) {
        Objects.requireNonNull(value, "afterFingerprint");
        String normalized = value.strip();
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "afterFingerprint must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String requireReviewReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_REVIEW_REASON_LENGTH) {
            throw new IllegalArgumentException("Invalid delivery review reason");
        }
        return normalized;
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
                    if (character < FIRST_NON_CONTROL_CHARACTER) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record ClaimArguments(String claimToken, long nowMillis, long leaseMillis) {
    }

    private record ExistingRequest(
            String definitionKey,
            UUID playerId,
            UUID deliveryId,
            String outcome) {
    }

    private record DefinitionRevision(UUID definitionId, int revision) {
    }

    private record DeliveryState(UUID instanceId, String state, String claimToken) {
    }

    private record ExpiredClaim(
            UUID deliveryId,
            UUID instanceId,
            UUID playerId,
            String state,
            String claimToken) {
    }

    private record DeliveryCandidate(
            UUID deliveryId,
            LoreInstanceId instanceId,
            LoreDefinitionId definitionId,
            UUID playerId,
            TemplateRevision appliedRevision,
            EncodedItemTemplate template,
            String idempotencyKey,
            int attemptCount,
            long createdAtEpochMillis,
            long updatedAtEpochMillis) {
        private PreparedDirectDelivery claimed(
                String claimToken,
                long claimExpiresAtEpochMillis,
                long claimedAtEpochMillis) {
            return new PreparedDirectDelivery(
                    deliveryId,
                    instanceId,
                    definitionId,
                    playerId,
                    appliedRevision,
                    template,
                    idempotencyKey,
                    claimToken,
                    claimExpiresAtEpochMillis,
                    attemptCount + 1,
                    createdAtEpochMillis,
                    claimedAtEpochMillis);
        }
    }
}
