package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.DistributionCampaignStartRequest;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDistributionDeliveryRepositoryTest {
    private static final long CREATED_AT = 1_000L;
    private static final Instant CLAIM_AT = Instant.ofEpochMilli(2_000L);
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final String FINGERPRINT = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void preparedDeliveryPinsCampaignRevisionAndCompletesExactlyOnce() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("complete.db"));
        try {
            CampaignFixture fixture = seedCampaign(runtime, "sha256:pinned", 2L);
            advanceDefinition(runtime, fixture.definitionId(), 3L);
            SQLiteDistributionDeliveryRepository repository =
                    new SQLiteDistributionDeliveryRepository(runtime);

            CampaignRecipient claimed = repository.claimPending("claim-1", CLAIM_AT, LEASE, 10)
                    .toCompletableFuture().join().items().getFirst();
            PreparedDistributionDelivery prepared = repository.prepareClaimed(claimed, CLAIM_AT.plusMillis(1))
                    .toCompletableFuture().join().orElseThrow();

            assertEquals(new TemplateRevision(2L), prepared.appliedRevision());
            assertArrayEquals(new byte[] {2}, prepared.template().payload());
            assertTrue(repository.completePrepared(
                            prepared, 4, FINGERPRINT, CLAIM_AT.plusMillis(2))
                    .toCompletableFuture().join());
            assertFalse(repository.completePrepared(
                            prepared, 4, FINGERPRINT, CLAIM_AT.plusMillis(3))
                    .toCompletableFuture().join());

            CampaignRecipient delivered = new SQLiteDistributionRecipientRepository(runtime)
                    .find(fixture.campaignId(), fixture.recipient().recipientKey())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(CampaignRecipientState.DELIVERED, delivered.state());
            assertEquals(prepared.instanceId(), delivered.instanceId());
            assertEquals(
                    DistributionCampaignState.COMPLETED,
                    new SQLiteDistributionCampaignRepository(runtime)
                            .findById(fixture.campaignId())
                            .toCompletableFuture().join().orElseThrow().state());
            assertTrackedRevision(runtime, prepared, 2L);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void fullInventoryDeferralDeletesUnusedPreparedInstance() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("defer.db"));
        try {
            CampaignFixture fixture = seedCampaign(runtime, "sha256:defer", 1L);
            SQLiteDistributionDeliveryRepository repository =
                    new SQLiteDistributionDeliveryRepository(runtime);
            CampaignRecipient claimed = repository.claimPending("claim-2", CLAIM_AT, LEASE, 10)
                    .toCompletableFuture().join().items().getFirst();
            PreparedDistributionDelivery prepared = repository.prepareClaimed(claimed, CLAIM_AT.plusMillis(1))
                    .toCompletableFuture().join().orElseThrow();

            assertTrue(repository.deferPrepared(
                            prepared,
                            CampaignRecipientState.QUEUED_INVENTORY_FULL,
                            CLAIM_AT.plusMillis(2),
                            CLAIM_AT.plusSeconds(30))
                    .toCompletableFuture().join());

            CampaignRecipient deferred = new SQLiteDistributionRecipientRepository(runtime)
                    .find(fixture.campaignId(), fixture.recipient().recipientKey())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(CampaignRecipientState.QUEUED_INVENTORY_FULL, deferred.state());
            assertEquals(null, deferred.instanceId());
            assertEquals(0L, countRows(runtime, "lore_instances", "instance_id", prepared.instanceId().value().toString()));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void expiredPreparedClaimMovesToReviewInsteadOfRetryingPhysicalSideEffect() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("expired.db"));
        try {
            CampaignFixture fixture = seedCampaign(runtime, "sha256:expired", 1L);
            SQLiteDistributionDeliveryRepository repository =
                    new SQLiteDistributionDeliveryRepository(runtime);
            CampaignRecipient claimed = repository.claimPending(
                            "claim-3", CLAIM_AT, Duration.ofMillis(10), 10)
                    .toCompletableFuture().join().items().getFirst();
            PreparedDistributionDelivery prepared = repository.prepareClaimed(
                            claimed, CLAIM_AT.plusMillis(1))
                    .toCompletableFuture().join().orElseThrow();

            assertEquals(1, repository.recoverExpiredClaims(CLAIM_AT.plusMillis(11), 10)
                    .toCompletableFuture().join());

            CampaignRecipient review = new SQLiteDistributionRecipientRepository(runtime)
                    .find(fixture.campaignId(), fixture.recipient().recipientKey())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(CampaignRecipientState.REVIEW_REQUIRED, review.state());
            assertEquals(prepared.instanceId(), review.instanceId());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static CampaignFixture seedCampaign(
            SQLiteStorageRuntime runtime, String fingerprint, long revision) {
        UUID definitionId = seedDefinition(runtime, revision);
        UUID campaignId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DistributionCampaign campaign = new DistributionCampaign(
                campaignId,
                fingerprint,
                "group.yml",
                "Group",
                new LoreDefinitionId(definitionId),
                new TemplateRevision(revision),
                DistributionCampaignState.DRAFT,
                CREATED_AT,
                CREATED_AT,
                null);
        CampaignRecipient recipient = CampaignRecipient.knownPlayer(
                campaignId, 0, playerId, playerId.toString(), CREATED_AT);
        new SQLiteDistributionCampaignStartRepository(runtime)
                .start(new DistributionCampaignStartRequest(
                        campaign, List.of(recipient), "PLAYER", "operator"))
                .toCompletableFuture().join();
        return new CampaignFixture(campaignId, definitionId, recipient);
    }

    private static UUID seedDefinition(SQLiteStorageRuntime runtime, long currentRevision) {
        UUID definitionId = UUID.randomUUID();
        runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, current_revision, created_at) "
                            + "VALUES (?, ?, 'Definition', ?, 1)")) {
                statement.setString(1, definitionId.toString());
                statement.setString(2, "definition-" + definitionId);
                statement.setLong(3, currentRevision);
                statement.executeUpdate();
            }
            for (long revision = 1L; revision <= currentRevision; revision++) {
                insertRevision(connection, definitionId, revision);
            }
            return null;
        }).toCompletableFuture().join();
        return definitionId;
    }

    private static void advanceDefinition(
            SQLiteStorageRuntime runtime, UUID definitionId, long revision) {
        runtime.execute(connection -> {
            insertRevision(connection, definitionId, revision);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE lore_definitions SET current_revision = ? WHERE definition_id = ?")) {
                statement.setLong(1, revision);
                statement.setString(2, definitionId.toString());
                statement.executeUpdate();
            }
            return null;
        }).toCompletableFuture().join();
    }

    private static void insertRevision(
            java.sql.Connection connection, UUID definitionId, long revision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, template_blob, created_at) "
                        + "VALUES (?, ?, 1, ?, ?)")) {
            statement.setString(1, definitionId.toString());
            statement.setLong(2, revision);
            statement.setBytes(3, new byte[] {(byte) revision});
            statement.setLong(4, revision);
            statement.executeUpdate();
        }
    }

    private static void assertTrackedRevision(
            SQLiteStorageRuntime runtime,
            PreparedDistributionDelivery delivery,
            long expectedRevision) {
        runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT applied_revision FROM lore_instances WHERE instance_id = ?")) {
                statement.setString(1, delivery.instanceId().value().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(expectedRevision, resultSet.getLong("applied_revision"));
                }
            }
            return null;
        }).toCompletableFuture().join();
    }

    private static long countRows(
            SQLiteStorageRuntime runtime,
            String table,
            String column,
            String value) {
        return runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) AS count_value FROM " + table + " WHERE " + column + " = ?")) {
                statement.setString(1, value);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong("count_value");
                }
            }
        }).toCompletableFuture().join();
    }

    private static SQLiteStorageRuntime start(Path database) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("test-database", 32, metrics),
                metrics);
        assertEquals(
                net.enthusia.loreitems.application.StorageState.READ_WRITE,
                runtime.start().toCompletableFuture().join().state());
        return runtime;
    }

    private record CampaignFixture(
            UUID campaignId,
            UUID definitionId,
            CampaignRecipient recipient) {
    }
}
