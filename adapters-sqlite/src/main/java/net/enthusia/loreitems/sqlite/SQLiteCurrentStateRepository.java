package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.CurrentStateRepository;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreInstanceId;

public final class SQLiteCurrentStateRepository implements CurrentStateRepository {
    private static final long INITIAL_STATE_REVISION = 0L;
    private final SQLiteStorageRuntime storage;

    public SQLiteCurrentStateRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Void> create(InstanceCurrentState currentState) {
        Objects.requireNonNull(currentState, "currentState");
        if (currentState.stateRevision() != INITIAL_STATE_REVISION) {
            throw new IllegalArgumentException("Initial current state revision must be zero");
        }
        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> {
                    validateObservation(transaction, currentState);
                    try (PreparedStatement statement = transaction.prepareStatement(
                            "INSERT INTO instance_current_state(instance_id, state, "
                                    + "location_type, location_key, container_path, "
                                    + "last_observation_id, state_revision, updated_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        bindState(statement, currentState);
                        statement.executeUpdate();
                        return null;
                    }
                }));
    }

    @Override
    public CompletionStage<Optional<InstanceCurrentState>> findByInstance(
            LoreInstanceId instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT instance_id, state, location_type, location_key, "
                            + "container_path, last_observation_id, state_revision, updated_at "
                            + "FROM instance_current_state WHERE instance_id = ?")) {
                statement.setString(1, instanceId.value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(readState(resultSet))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletionStage<Boolean> compareAndSet(
            LoreInstanceId instanceId,
            long expectedStateRevision,
            InstanceCurrentState targetState) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(targetState, "targetState");
        if (!instanceId.equals(targetState.instanceId())) {
            throw new IllegalArgumentException("Target state belongs to another instance");
        }
        if (expectedStateRevision < 0L
                || targetState.stateRevision() != Math.addExact(expectedStateRevision, 1L)) {
            throw new IllegalArgumentException(
                    "Target state revision must advance expected revision by one");
        }
        if (targetState.state() == InstanceCurrentState.State.MISSING_UNRESOLVED) {
            throw new IllegalArgumentException(
                    "Current state must not discard durable last-observed evidence");
        }

        return storage.execute(connection -> SQLiteTransactions.inTransaction(
                connection,
                transaction -> {
                    validateObservation(transaction, targetState);
                    try (PreparedStatement statement = transaction.prepareStatement(
                            "UPDATE instance_current_state SET state = ?, location_type = ?, "
                                    + "location_key = ?, container_path = ?, "
                                    + "last_observation_id = ?, state_revision = ?, updated_at = ? "
                                    + "WHERE instance_id = ? AND state_revision = ? "
                                    + "AND updated_at <= ? AND (last_observation_id IS NULL "
                                    + "OR last_observation_id < ?)")) {
                        bindMutableState(statement, targetState);
                        statement.setString(8, instanceId.value().toString());
                        statement.setLong(9, expectedStateRevision);
                        statement.setLong(10, targetState.updatedAtEpochMillis());
                        statement.setLong(11, targetState.lastObservationId());
                        return statement.executeUpdate() == 1;
                    }
                }));
    }

    private static void validateObservation(
            Connection connection, InstanceCurrentState state) throws SQLException {
        if (state.lastObservationId() == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT location_type, location_key, container_path, confidence, observed_at "
                        + "FROM instance_observations "
                        + "WHERE observation_id = ? AND instance_id = ?")) {
            statement.setLong(1, state.lastObservationId());
            statement.setString(2, state.instanceId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException(
                            "Current state observation does not belong to the instance");
                }
                LocationDescriptor location = state.location();
                if (!location.type().name().equals(resultSet.getString("location_type"))
                        || !location.locationKey().equals(resultSet.getString("location_key"))
                        || !Objects.equals(
                                location.containerPath(),
                                resultSet.getString("container_path"))) {
                    throw new IllegalArgumentException(
                            "Current state location must match its observation");
                }
                InstanceObservation.Confidence confidence =
                        InstanceObservation.Confidence.valueOf(
                                resultSet.getString("confidence"));
                if (!confidenceMatches(state.state(), confidence)) {
                    throw new IllegalArgumentException(
                            "Current state does not match observation confidence");
                }
                if (state.updatedAtEpochMillis() < resultSet.getLong("observed_at")) {
                    throw new IllegalArgumentException(
                            "Current state cannot predate its observation");
                }
            }
        }
    }

    private static boolean confidenceMatches(
            InstanceCurrentState.State state,
            InstanceObservation.Confidence confidence) {
        return switch (state) {
            case CONFIRMED_NOW -> confidence == InstanceObservation.Confidence.CONFIRMED_NOW;
            case LAST_CONFIRMED -> confidence == InstanceObservation.Confidence.CONFIRMED_NOW
                    || confidence == InstanceObservation.Confidence.LAST_CONFIRMED;
            case CONFLICTING -> confidence == InstanceObservation.Confidence.CONFLICTING;
            case TERMINAL_VOID -> confidence == InstanceObservation.Confidence.TERMINAL_VOID;
            case MISSING_UNRESOLVED -> false;
        };
    }

    private static void bindState(
            PreparedStatement statement, InstanceCurrentState state) throws SQLException {
        statement.setString(1, state.instanceId().value().toString());
        statement.setString(2, state.state().name());
        bindNullableLocation(statement, 3, state.location());
        if (state.lastObservationId() == null) {
            statement.setNull(6, Types.BIGINT);
        } else {
            statement.setLong(6, state.lastObservationId());
        }
        statement.setLong(7, state.stateRevision());
        statement.setLong(8, state.updatedAtEpochMillis());
    }

    private static void bindMutableState(
            PreparedStatement statement, InstanceCurrentState state) throws SQLException {
        statement.setString(1, state.state().name());
        bindNullableLocation(statement, 2, state.location());
        statement.setLong(5, state.lastObservationId());
        statement.setLong(6, state.stateRevision());
        statement.setLong(7, state.updatedAtEpochMillis());
    }

    private static void bindNullableLocation(
            PreparedStatement statement, int firstIndex, LocationDescriptor location)
            throws SQLException {
        if (location == null) {
            statement.setNull(firstIndex, Types.VARCHAR);
            statement.setNull(firstIndex + 1, Types.VARCHAR);
            statement.setNull(firstIndex + 2, Types.VARCHAR);
        } else {
            statement.setString(firstIndex, location.type().name());
            statement.setString(firstIndex + 1, location.locationKey());
            statement.setString(firstIndex + 2, location.containerPath());
        }
    }

    private static InstanceCurrentState readState(ResultSet resultSet) throws SQLException {
        String locationType = resultSet.getString("location_type");
        LocationDescriptor location = locationType == null
                ? null
                : new LocationDescriptor(
                        LocationDescriptor.Type.valueOf(locationType),
                        resultSet.getString("location_key"),
                        resultSet.getString("container_path"));
        long lastObservationId = resultSet.getLong("last_observation_id");
        Long observationValue = resultSet.wasNull() ? null : lastObservationId;
        return new InstanceCurrentState(
                new LoreInstanceId(UUID.fromString(resultSet.getString("instance_id"))),
                InstanceCurrentState.State.valueOf(resultSet.getString("state")),
                location,
                observationValue,
                resultSet.getLong("state_revision"),
                resultSet.getLong("updated_at"));
    }
}
