package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.sql.PreparedStatement;
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

class SQLiteCancellableDistributionDeliveryRepositoryTest {
    private static final long CREATED_AT = 1_000L;
    private static final Instant CLAIM_AT = Instant.ofEpochMilli(2_000L);
    private static final Duration SHORT_LEASE = Duration.ofMillis(10L);

    @TempDir
    Path temporaryDirectory;

    @Test
    void cancelledCampaignExpiresUnpreparedClaimToCancelled() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("cancel-unprepared.db"));
        try {
            CampaignFixture fixture = seedCampaign(runtime, "sha256:cancel-unprepared");
            SQLiteCancellableDistributionDeliveryRepository repository =
                    new SQLiteCancellableDistributionDeliveryRepository(runtime);
            CampaignRecipient claimed = repository.claimPending(
                            "claim-unprepared", CLAIM_AT, SHORT_LEASE, 10)
                    .toCompletableFuture()
                    .join()
                    .items()
                    .getFirst();

            cancelCampaign(runtime, fixture.campaignId(), CLAIM_AT.plusMillis(2L));
            assertEquals(
                    1,
                    repository.recoverExpiredClaims(CLAIM_AT.plusMillis(11L), 10)
                            .toCompletableFuture()
                            .join());

            CampaignRecipient recovered = new SQLiteDistributionRecipientRepository(runtime)
                    .find(fixture.campaignId(), claimed.recipientKey())
                    .toCompletableFuture()
                    .join()
                    .orElseThrow();
            assertEquals(CampaignRecipientState.CANCELLED, recovered.state());
            assertEquals(null, recovered.instanceId());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void cancelledCampaignExpiresPreparedClaimToReview() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("cancel-prepared.db"));
        try {
            CampaignFixture fixture = seedCampaign(runtime, "sha256:cancel-prepared");
            SQLiteCancellableDistributionDeliveryRepository repository =
                    new SQLiteCancellableDistributionDeliveryRepository(runtime);
            CampaignRecipient claimed = repository.claimPending(
                            "claim-prepared", CLAIM_AT, SHORT_LEASE, 10)
                    .toCompletableFuture()
                    .join()
                    .items()
                    .getFirst();
            PreparedDistributionDelivery prepared = repository.prepareClaimed(
                            claimed, CLAIM_AT.plusMillis(1L))
                    .toCompletableFuture()
                    .join()
                    .orElseThrow();

            cancelCampaign(runtime, fixture.campaignId(), CLAIM_AT.plusMillis(2L));
            assertEquals(
                    1,
                    repository.recoverExpiredClaims(CLAIM_AT.plusMillis(11L), 10)
                            .toCompletableFuture()
                            .join());

            CampaignRecipient recovered = new SQLiteDistributionRecipientRepository(runtime)
                    .find(fixture.campaignId(), claimed.recipientKey())
                    .toCompletableFuture()
                    .join()
                    .orElseThrow();
            assertEquals(CampaignRecipientState.REVIEW_REQUIRED, recovered.state());
            assertEquals(prepared.instanceId(), recovered.instanceId());
            assertNotNull(recovered.instanceId());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static CampaignFixture seedCampaign(
            SQLiteStorageRuntime runtime,
            String fingerprint) {
        UUID definitionId = seedDefinition(runtime);
        UUID campaignId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        DistributionCampaign campaign = new DistributionCampaign(
                campaignId,
                fingerprint,
                "group.yml",
                "Group",
                new LoreDefinitionId(definitionId),
                new TemplateRevision(1L),
                DistributionCampaignState.DRAFT,
                CREATED_AT,
                CREATED_AT,
                null);
        CampaignRecipient recipient = CampaignRecipient.knownPlayer(
                campaignId,
                0,
                playerId,
                playerId.toString(),
                CREATED_AT);
        new SQLiteDistributionCampaignStartRepository(runtime)
                .start(new DistributionCampaignStartRequest(
                        campaign,
                        List.of(recipient),
                        "PLAYER",
                        "operator"))
                .toCompletableFuture()
                .join();
        return new CampaignFixture(campaignId);
    }

    private static UUID seedDefinition(SQLiteStorageRuntime runtime) {
        UUID definitionId = UUID.randomUUID();
        runtime.execute(connection -> {
                    try (PreparedStatement definition = connection.prepareStatement(
                                    "INSERT INTO lore_definitions(definition_id, lookup_key, "
                                            + "display_name, current_revision, created_at) "
                                            + "VALUES (?, ?, 'Definition', 1, 1)")) {
                        definition.setString(1, definitionId.toString());
                        definition.setString(2, "definition-" + definitionId);
                        definition.executeUpdate();
                    }
                    insertRevision(connection, definitionId);
                    return null;
                })
                .toCompletableFuture()
                .join();
        return definitionId;
    }

    private static void insertRevision(
            java.sql.Connection connection,
            UUID definitionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                        + "template_blob, created_at) VALUES (?, 1, 1, ?, 1)")) {
            statement.setString(1, definitionId.toString());
            statement.setBytes(2, new byte[] {1});
            statement.executeUpdate();
        }
    }

    private static void cancelCampaign(
            SQLiteStorageRuntime runtime,
            UUID campaignId,
            Instant now) {
        new SQLiteDistributionCampaignRepository(runtime)
                .cancel(campaignId, DistributionCampaignState.ACTIVE, now)
                .toCompletableFuture()
                .join();
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

    private record CampaignFixture(UUID campaignId) {
    }
}
