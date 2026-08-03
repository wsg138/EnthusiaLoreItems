package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.VoidLossStore;
import net.enthusia.loreitems.application.VoidLossUseCase;

public final class SQLiteVoidLossStore implements VoidLossStore {
    static final String MUTATION_TYPE = "VOID_TERMINAL_LOSS";

    private static final String AGGREGATE_TYPE = "lore_instance";
    private static final String ACTOR_TYPE = "system";
    private static final String PREPARED_EVENT = "void_loss_prepared";
    private static final String COMPLETED_EVENT = "void_loss_completed";
    private static final String ABORTED_EVENT = "void_loss_aborted";
    private static final String REVIEW_EVENT = "void_loss_review_required";
    private static final String OBSERVATION_SOURCE = "void-terminal-loss";
    private static final int SINGLE_ROW = 1;

    private final SQLiteStorageRuntime storage;

    public SQLiteVoidLossStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<VoidLossUseCase.PrepareResult> prepare(
            VoidLossUseCase.Request request,
            UUID mutationId,
            UUID claimToken,
            Instant preparedAt,
            Instant claimExpiresAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mutationId, "mutationId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(preparedAt, "preparedAt");
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> prepareInTransaction(
                        transaction,
                        request,
                        mutationId,
                        claimToken,
                        preparedAt.toEpochMilli(),
                        claimExpiresAt.toEpochMilli())));
    }

    @Override
    public CompletionStage<Boolean> complete(PreparedVoidLoss loss, Instant completedAt) {
        Objects.requireNonNull(loss, "loss");
        Objects.requireNonNull(completedAt, "completedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> completeInTransaction(
                        transaction, loss, completedAt.toEpochMilli())));
    }

    @Override
    public CompletionStage<Boolean> abort(
            PreparedVoidLoss loss,
            String reason,
            Instant abortedAt) {
        Objects.requireNonNull(loss, "loss");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(abortedAt, "abortedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> finishWithoutRemoval(
                        transaction,
                        loss,
                        "COMPLETED",
                        ABORTED_EVENT,
                        reason,
                        abortedAt.toEpochMilli())));
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            PreparedVoidLoss loss,
            String reason,
            Instant reviewedAt) {
        Objects.requireNonNull(loss, "loss");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(reviewedAt, "reviewedAt");
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> finishWithoutRemoval(
                        transaction,
                        loss,
                        "REVIEW_REQUIRED",
                        REVIEW_EVENT,
                        reason,
                        reviewedAt.toEpochMilli())));
    }

    private static VoidLossUseCase.PrepareResult prepareInTransaction(
            Connection connection,
            VoidLossUseCase.Request request,
            UUID mutationId,
            UUID claimToken,
            long preparedAt,
            long claimExpiresAt) throws SQLException {
        InstanceRow instance = findInstance(connection, request.identity().instanceId().value());
        if (instance == null) {
            return VoidLossUseCase.PrepareResult.of(
                    VoidLossUseCase.PrepareStatus.UNKNOWN_INSTANCE,
                    "The tracked identity has no durable instance record.");
        }
        if (!instance.definitionId().equals(request.identity().definitionId().value())
                || instance.appliedRevision() != request.identity().appliedRevision().value()) {
            return VoidLossUseCase.PrepareResult.of(
                    VoidLossUseCase.PrepareStatus.IDENTITY_MISMATCH,
                    "The physical identity does not match the durable instance record.");
        }
        if ("VOID_DESTROYED".equals(instance.lifecycleState())) {
            return VoidLossUseCase.PrepareResult.of(
                    VoidLossUseCase.PrepareStatus.ALREADY_TERMINAL,
                    "The instance is already recorded as void-destroyed.");
        }
        if (!"ACTIVE".equals(instance.lifecycleState())
                || hasBlockingAnomaly(connection, request.identity().instanceId().value())
                || hasActiveVoidMutation(connection, request.identity().instanceId().value())) {
            return VoidLossUseCase.PrepareResult.of(
                    VoidLossUseCase.PrepareStatus.REVIEW_REQUIRED,
                    "The instance has conflicting durable state and was not destroyed.");
        }

        PreparedVoidLoss loss = new PreparedVoidLoss(
                mutationId,
                request.identity(),
                request.entityId(),
                request.locationKey(),
                claimToken,
                preparedAt,
                claimExpiresAt);
        insertMutation(connection, loss);
        appendAudit(connection, loss, PREPARED_EVENT, preparedDetail(loss), preparedAt);
        return VoidLossUseCase.PrepareResult.prepared(loss);
    }

    private static boolean completeInTransaction(
            Connection connection,
            PreparedVoidLoss loss,
            long completedAt) throws SQLException {
        MutationRow mutation = findMutation(connection, loss.mutationId());
        if (mutation == null) {
            return false;
        }
        if ("COMPLETED".equals(mutation.state())) {
            return isVoidDestroyed(connection, loss);
        }
        if (!"CLAIMED".equals(mutation.state())
                || !loss.claimToken().toString().equals(mutation.claimToken())
                || mutation.claimExpiresAt() == null
                || mutation.claimExpiresAt() <= completedAt) {
            return false;
        }
        requireTransition(connection, loss, "CLAIMED", "APPLIED", completedAt, false);
        markInstanceVoidDestroyed(connection, loss, completedAt);
        long observationId = insertObservation(connection, loss, completedAt);
        updateCurrentState(connection, loss, observationId, completedAt);
        requireTransition(connection, loss, "APPLIED", "VERIFIED", completedAt, false);
        requireTransition(connection, loss, "VERIFIED", "COMPLETED", completedAt, true);
        appendAudit(connection, loss, COMPLETED_EVENT, completedDetail(loss), completedAt);
        return true;
    }

    private static boolean finishWithoutRemoval(
            Connection connection,
            PreparedVoidLoss loss,
            String targetState,
            String eventType,
            String reason,
            long occurredAt) throws SQLException {
        MutationRow mutation = findMutation(connection, loss.mutationId());
        if (mutation == null) {
            return false;
        }
        if (targetState.equals(mutation.state())) {
            return true;
        }
        if (!"CLAIMED".equals(mutation.state())
                || !loss.claimToken().toString().equals(mutation.claimToken())) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_mutations SET state = ?, claim_token = NULL, "
                        + "claim_expires_at = NULL, updated_at = ? "
                        + "WHERE mutation_id = ? AND mutation_type = ? AND instance_id = ? "
                        + "AND state = 'CLAIMED' AND claim_token = ?")) {
            statement.setString(1, targetState);
            statement.setLong(2, occurredAt);
            statement.setString(3, loss.mutationId().toString());
            statement.setString(4, MUTATION_TYPE);
            statement.setString(5, loss.identity().instanceId().value().toString());
            statement.setString(6, loss.claimToken().toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                return false;
            }
        }
        appendAudit(connection, loss, eventType, reasonDetail(loss, reason), occurredAt);
        return true;
    }

    private static InstanceRow findInstance(Connection connection, UUID instanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT definition_id, applied_revision, lifecycle_state "
                        + "FROM lore_instances WHERE instance_id = ?")) {
            statement.setString(1, instanceId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? new InstanceRow(
                                UUID.fromString(resultSet.getString("definition_id")),
                                resultSet.getLong("applied_revision"),
                                resultSet.getString("lifecycle_state"))
                        : null;
            }
        }
    }

    private static boolean hasBlockingAnomaly(Connection connection, UUID instanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM instance_anomalies WHERE instance_id = ? "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED') "
                        + "AND anomaly_type IN ('DUPLICATE_INSTANCE', 'MALFORMED_STACK', "
                        + "'CONFLICTING_OBSERVATION', 'IDENTITY_MISMATCH') LIMIT 1")) {
            statement.setString(1, instanceId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean hasActiveVoidMutation(Connection connection, UUID instanceId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pending_mutations WHERE mutation_type = ? AND instance_id = ? "
                        + "AND state IN ('PENDING', 'CLAIMED', 'APPLIED', 'VERIFIED', "
                        + "'REVIEW_REQUIRED') LIMIT 1")) {
            statement.setString(1, MUTATION_TYPE);
            statement.setString(2, instanceId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void insertMutation(Connection connection, PreparedVoidLoss loss)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pending_mutations(mutation_id, mutation_type, definition_id, "
                        + "instance_id, desired_revision, state, claim_token, claim_expires_at, "
                        + "attempt_count, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CLAIMED', ?, ?, 1, NULL, ?, ?)")) {
            statement.setString(1, loss.mutationId().toString());
            statement.setString(2, MUTATION_TYPE);
            statement.setString(3, loss.identity().definitionId().value().toString());
            statement.setString(4, loss.identity().instanceId().value().toString());
            statement.setInt(5, Math.toIntExact(loss.identity().appliedRevision().value()));
            statement.setString(6, loss.claimToken().toString());
            statement.setLong(7, loss.claimExpiresAtEpochMillis());
            statement.setLong(8, loss.preparedAtEpochMillis());
            statement.setLong(9, loss.preparedAtEpochMillis());
            statement.executeUpdate();
        }
    }

    private static MutationRow findMutation(Connection connection, UUID mutationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state, claim_token, claim_expires_at FROM pending_mutations "
                        + "WHERE mutation_id = ? AND mutation_type = ?")) {
            statement.setString(1, mutationId.toString());
            statement.setString(2, MUTATION_TYPE);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                long expiry = resultSet.getLong("claim_expires_at");
                return new MutationRow(
                        resultSet.getString("state"),
                        resultSet.getString("claim_token"),
                        resultSet.wasNull() ? null : expiry);
            }
        }
    }

    private static void markInstanceVoidDestroyed(
            Connection connection,
            PreparedVoidLoss loss,
            long terminalAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE lore_instances SET lifecycle_state = 'VOID_DESTROYED', terminal_at = ? "
                        + "WHERE instance_id = ? AND definition_id = ? AND applied_revision = ? "
                        + "AND lifecycle_state = 'ACTIVE' AND terminal_at IS NULL")) {
            statement.setLong(1, terminalAt);
            statement.setString(2, loss.identity().instanceId().value().toString());
            statement.setString(3, loss.identity().definitionId().value().toString());
            statement.setLong(4, loss.identity().appliedRevision().value());
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Void loss lost the expected active instance state");
            }
        }
    }

    private static long insertObservation(
            Connection connection,
            PreparedVoidLoss loss,
            long observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO instance_observations(instance_id, definition_id, location_type, "
                        + "location_key, container_path, confidence, source, observed_at) "
                        + "VALUES (?, ?, 'VOID_DESTROYED', ?, NULL, 'TERMINAL_VOID', ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, loss.identity().instanceId().value().toString());
            statement.setString(2, loss.identity().definitionId().value().toString());
            statement.setString(3, loss.locationKey());
            statement.setString(4, OBSERVATION_SOURCE);
            statement.setLong(5, observedAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Void observation did not return an identifier");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void updateCurrentState(
            Connection connection,
            PreparedVoidLoss loss,
            long observationId,
            long updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET state = 'TERMINAL_VOID', "
                        + "location_type = 'VOID_DESTROYED', location_key = ?, "
                        + "container_path = NULL, last_observation_id = ?, "
                        + "state_revision = state_revision + 1, updated_at = ? "
                        + "WHERE instance_id = ? AND state <> 'TERMINAL_VOID'")) {
            statement.setString(1, loss.locationKey());
            statement.setLong(2, observationId);
            statement.setLong(3, updatedAt);
            statement.setString(4, loss.identity().instanceId().value().toString());
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Void loss could not advance current state");
            }
        }
    }

    private static boolean isVoidDestroyed(Connection connection, PreparedVoidLoss loss)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM lore_instances WHERE instance_id = ? AND definition_id = ? "
                        + "AND lifecycle_state = 'VOID_DESTROYED' LIMIT 1")) {
            statement.setString(1, loss.identity().instanceId().value().toString());
            statement.setString(2, loss.identity().definitionId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void requireTransition(
            Connection connection,
            PreparedVoidLoss loss,
            String expected,
            String target,
            long now,
            boolean clearClaim) throws SQLException {
        String claimUpdate = clearClaim
                ? "claim_token = NULL, claim_expires_at = NULL, "
                : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pending_mutations SET state = ?, " + claimUpdate
                        + "updated_at = ? WHERE mutation_id = ? AND mutation_type = ? "
                        + "AND instance_id = ? AND state = ? AND claim_token = ? "
                        + "AND claim_expires_at > ?")) {
            statement.setString(1, target);
            statement.setLong(2, now);
            statement.setString(3, loss.mutationId().toString());
            statement.setString(4, MUTATION_TYPE);
            statement.setString(5, loss.identity().instanceId().value().toString());
            statement.setString(6, expected);
            statement.setString(7, loss.claimToken().toString());
            statement.setLong(8, now);
            if (statement.executeUpdate() != SINGLE_ROW) {
                throw new SQLException("Void loss lost transition " + expected + " -> " + target);
            }
        }
    }

    private static void appendAudit(
            Connection connection,
            PreparedVoidLoss loss,
            String eventType,
            String detail,
            long occurredAt) throws SQLException {
        SQLiteAuditRepository.appendInTransaction(connection, AuditEventRecord.pending(
                AGGREGATE_TYPE,
                loss.identity().instanceId().value().toString(),
                eventType,
                ACTOR_TYPE,
                null,
                detail,
                occurredAt));
    }

    private static String preparedDetail(PreparedVoidLoss loss) {
        return "{\"mutationId\":\"" + loss.mutationId()
                + "\",\"entityId\":\"" + loss.entityId()
                + "\",\"location\":\"" + jsonEscape(loss.locationKey()) + "\"}";
    }

    private static String completedDetail(PreparedVoidLoss loss) {
        return "{\"mutationId\":\"" + loss.mutationId()
                + "\",\"entityId\":\"" + loss.entityId()
                + "\",\"terminal\":true}";
    }

    private static String reasonDetail(PreparedVoidLoss loss, String reason) {
        return "{\"mutationId\":\"" + loss.mutationId()
                + "\",\"entityId\":\"" + loss.entityId()
                + "\",\"reason\":\"" + jsonEscape(reason) + "\"}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private record InstanceRow(
            UUID definitionId,
            long appliedRevision,
            String lifecycleState) {}

    private record MutationRow(
            String state,
            String claimToken,
            Long claimExpiresAt) {}
}
