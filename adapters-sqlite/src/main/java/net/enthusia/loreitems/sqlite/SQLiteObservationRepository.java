package net.enthusia.loreitems.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.ObservationRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;

public final class SQLiteObservationRepository implements ObservationRepository {
    private final SQLiteStorageRuntime storage;

    public SQLiteObservationRepository(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<InstanceObservation> append(InstanceObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (observation.observationId() != 0L) {
            throw new IllegalArgumentException(
                    "New observations must not already have a persistent identifier");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO instance_observations(instance_id, definition_id, "
                            + "location_type, location_key, container_path, confidence, "
                            + "source, observed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, observation.instanceId().value().toString());
                statement.setString(2, observation.definitionId().value().toString());
                statement.setString(3, observation.location().type().name());
                statement.setString(4, observation.location().locationKey());
                statement.setString(5, observation.location().containerPath());
                statement.setString(6, observation.confidence().name());
                statement.setString(7, observation.source());
                statement.setLong(8, observation.observedAtEpochMillis());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Observation insert did not return an identifier");
                    }
                    return observation.withObservationId(keys.getLong(1));
                }
            }
        });
    }

    @Override
    public CompletionStage<Optional<InstanceObservation>> findById(long observationId) {
        if (observationId < 1L) {
            throw new IllegalArgumentException("observationId must be positive");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE observation_id = ?")) {
                statement.setLong(1, observationId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(readObservation(resultSet))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletionStage<Page<InstanceObservation>> listByInstance(
            LoreInstanceId instanceId, PageRequest request) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(request, "request");
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE instance_id = ? "
                            + "ORDER BY observed_at DESC, observation_id DESC LIMIT ? OFFSET ?")) {
                statement.setString(1, instanceId.value().toString());
                statement.setInt(2, request.limit() + 1);
                statement.setInt(3, request.offset());
                return readPage(statement, request);
            }
        });
    }

    @Override
    public CompletionStage<Page<InstanceObservation>> listByLocation(
            LocationDescriptor.Type locationType,
            String locationKey,
            PageRequest request) {
        Objects.requireNonNull(locationType, "locationType");
        Objects.requireNonNull(locationKey, "locationKey");
        Objects.requireNonNull(request, "request");
        String normalizedKey = locationKey.strip();
        if (normalizedKey.isEmpty()
                || normalizedKey.length() > LocationDescriptor.MAX_LOCATION_KEY_LENGTH) {
            throw new IllegalArgumentException("Invalid location key");
        }
        return storage.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    selectColumns() + " WHERE location_type = ? AND location_key = ? "
                            + "ORDER BY observed_at DESC, observation_id DESC LIMIT ? OFFSET ?")) {
                statement.setString(1, locationType.name());
                statement.setString(2, normalizedKey);
                statement.setInt(3, request.limit() + 1);
                statement.setInt(4, request.offset());
                return readPage(statement, request);
            }
        });
    }

    private static String selectColumns() {
        return "SELECT observation_id, instance_id, definition_id, location_type, "
                + "location_key, container_path, confidence, source, observed_at "
                + "FROM instance_observations";
    }

    private static Page<InstanceObservation> readPage(
            PreparedStatement statement, PageRequest request) throws SQLException {
        List<InstanceObservation> observations = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                observations.add(readObservation(resultSet));
            }
        }
        boolean hasMore = observations.size() > request.limit();
        if (hasMore) {
            observations.remove(observations.size() - 1);
        }
        return new Page<>(observations, request.offset(), request.limit(), hasMore);
    }

    private static InstanceObservation readObservation(ResultSet resultSet)
            throws SQLException {
        return new InstanceObservation(
                resultSet.getLong("observation_id"),
                new LoreInstanceId(UUID.fromString(resultSet.getString("instance_id"))),
                new LoreDefinitionId(UUID.fromString(resultSet.getString("definition_id"))),
                new LocationDescriptor(
                        LocationDescriptor.Type.valueOf(resultSet.getString("location_type")),
                        resultSet.getString("location_key"),
                        resultSet.getString("container_path")),
                InstanceObservation.Confidence.valueOf(resultSet.getString("confidence")),
                resultSet.getString("source"),
                resultSet.getLong("observed_at"));
    }
}
