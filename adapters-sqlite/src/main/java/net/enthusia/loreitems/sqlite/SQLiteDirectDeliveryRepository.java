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
import net.enthusia.loreitems.application.DirectDeliveryRecord;
import net.enthusia.loreitems.application.DirectDeliveryRepository;
import net.enthusia.loreitems.application.ExternalDeliveryAcceptance;
import net.enthusia.loreitems.application.ExternalDeliveryCommand;
import net.enthusia.loreitems.application.ExternalDeliveryOutcome;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DirectDeliveryState;
import net.enthusia.loreitems.domain.LoreInstanceId;

public final class SQLiteDirectDeliveryRepository implements DirectDeliveryRepository {
    private final SQLiteStorageRuntime storage;

    public SQLiteDirectDeliveryRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<ExternalDeliveryAcceptance> acceptExternal(
            ExternalDeliveryCommand command, Instant now) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection, transaction -> acceptExternal(transaction, command, now.toEpochMilli())));
    }

    @Override
    public CompletionStage<Page<DirectDeliveryRecord>> claimPending(
            String claimToken, Instant now, Duration lease, int limit) {
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        String normalizedToken = claimToken.strip();
        if (normalizedToken.isEmpty()) {
            throw new IllegalArgumentException("claimToken must not be blank");
        }
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside bounded page limits");
        }
        long leaseMillis = lease.toMillis();
        if (leaseMillis < 1L) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(connection, transaction ->
                claimPending(transaction, normalizedToken, now.toEpochMilli(), leaseMillis, limit)));
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
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(now, "now");
        expected.transitionTo(target);
        boolean clearClaim = target == DirectDeliveryState.COMPLETED
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
                statement.setString(5, claimToken);
                statement.setLong(6, now.toEpochMilli());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletionStage<Integer> moveExpiredClaimsToReview(Instant now) {
        Objects.requireNonNull(now, "now");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE direct_deliveries SET state = 'REVIEW_REQUIRED', claim_token = NULL, "
                            + "claim_expires_at = NULL, updated_at = ? "
                            + "WHERE state IN ('RESERVED', 'APPLIED', 'VERIFIED') "
                            + "AND claim_expires_at <= ?")) {
                statement.setLong(1, now.toEpochMilli());
                statement.setLong(2, now.toEpochMilli());
                return statement.executeUpdate();
            }
        });
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

    private static ExternalDeliveryAcceptance acceptExternal(
            Connection connection, ExternalDeliveryCommand command, long now) throws SQLException {
        ExistingRequest existing = findExistingRequest(connection, command.externalOperationId());
        if (existing != null) {
            if (!existing.definitionKey().equals(command.definitionKey().value())
                    || !existing.playerId().equals(command.playerId())) {
                return new ExternalDeliveryAcceptance(
                        ExternalDeliveryOutcome.VALIDATION_FAILURE,
                        command.externalOperationId(),
                        Optional.ofNullable(existing.deliveryId()),
                        "The external operation ID was already used with different arguments.");
            }
            ExternalDeliveryOutcome stored = ExternalDeliveryOutcome.valueOf(existing.outcome());
            ExternalDeliveryOutcome replay = stored == ExternalDeliveryOutcome.ACCEPTED_QUEUED
                    ? ExternalDeliveryOutcome.ALREADY_ACCEPTED
                    : stored;
            return new ExternalDeliveryAcceptance(
                    replay,
                    command.externalOperationId(),
                    Optional.ofNullable(existing.deliveryId()),
                    "Returned the durable result for the existing external operation.");
        }

        DefinitionRevision definition = findDefinition(connection, command.definitionKey().value());
        if (definition == null) {
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

        UUID instanceId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        insertInstance(connection, instanceId, definition, now);
        insertDelivery(connection, deliveryId, instanceId, command, now);
        insertExternalRequest(
                connection,
                command,
                deliveryId,
                ExternalDeliveryOutcome.ACCEPTED_QUEUED,
                now);
        return new ExternalDeliveryAcceptance(
                ExternalDeliveryOutcome.ACCEPTED_QUEUED,
                command.externalOperationId(),
                Optional.of(deliveryId),
                "Durable delivery intent was accepted; physical insertion remains deferred.");
    }

    private static Page<DirectDeliveryRecord> claimPending(
            Connection connection, String claimToken, long now, long leaseMillis, int limit)
            throws SQLException {
        List<DirectDeliveryRecord> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM direct_deliveries WHERE state = 'PENDING' "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "ORDER BY created_at, delivery_id LIMIT ?")) {
            statement.setLong(1, now);
            statement.setInt(2, limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(readRecord(resultSet));
                }
            }
        }
        boolean hasMore = candidates.size() > limit;
        if (hasMore) {
            candidates.remove(candidates.size() - 1);
        }

        List<DirectDeliveryRecord> claimed = new ArrayList<>(candidates.size());
        long expiresAt = Math.addExact(now, leaseMillis);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE direct_deliveries SET state = 'RESERVED', claim_token = ?, "
                        + "claim_expires_at = ?, attempt_count = attempt_count + 1, updated_at = ? "
                        + "WHERE delivery_id = ? AND state = 'PENDING'")) {
            for (DirectDeliveryRecord candidate : candidates) {
                update.setString(1, claimToken);
                update.setLong(2, expiresAt);
                update.setLong(3, now);
                update.setString(4, candidate.deliveryId().toString());
                if (update.executeUpdate() == 1) {
                    claimed.add(new DirectDeliveryRecord(
                            candidate.deliveryId(),
                            candidate.instanceId(),
                            candidate.playerId(),
                            DirectDeliveryState.RESERVED,
                            candidate.idempotencyKey(),
                            claimToken,
                            expiresAt,
                            candidate.attemptCount() + 1,
                            candidate.createdAtEpochMillis(),
                            now));
                }
            }
        }
        return new Page<>(claimed, 0, limit, hasMore);
    }

    private static ExistingRequest findExistingRequest(Connection connection, String operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_key, player_id, delivery_id, outcome "
                        + "FROM external_delivery_requests WHERE external_operation_id = ?")) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String deliveryValue = resultSet.getString("delivery_id");
                return new ExistingRequest(
                        resultSet.getString("definition_key"),
                        UUID.fromString(resultSet.getString("player_id")),
                        deliveryValue == null ? null : UUID.fromString(deliveryValue),
                        resultSet.getString("outcome"));
            }
        }
    }

    private static DefinitionRevision findDefinition(Connection connection, String definitionKey)
            throws SQLException {
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
            Connection connection, UUID instanceId, DefinitionRevision definition, long now)
            throws SQLException {
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

    private static DirectDeliveryRecord readRecord(ResultSet resultSet) throws SQLException {
        long expires = resultSet.getLong("claim_expires_at");
        Long claimExpiresAt = resultSet.wasNull() ? null : expires;
        return new DirectDeliveryRecord(
                UUID.fromString(resultSet.getString("delivery_id")),
                new LoreInstanceId(UUID.fromString(resultSet.getString("instance_id"))),
                UUID.fromString(resultSet.getString("player_id")),
                DirectDeliveryState.valueOf(resultSet.getString("state")),
                resultSet.getString("idempotency_key"),
                resultSet.getString("claim_token"),
                claimExpiresAt,
                resultSet.getInt("attempt_count"),
                resultSet.getLong("created_at"),
                resultSet.getLong("updated_at"));
    }

    private record ExistingRequest(
            String definitionKey, UUID playerId, UUID deliveryId, String outcome) {
    }

    private record DefinitionRevision(UUID definitionId, int revision) {
    }
}
