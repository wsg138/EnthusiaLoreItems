package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DistributionCampaignStartRequest;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDistributionCampaignRestartTest {
    private static final long CREATED_AT = 10_000L;
    private static final UUID CAMPAIGN_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path temporaryDirectory;

    @Test
    void pauseResumeAndCancelRemainAuthoritativeAcrossRestarts() {
        Path database = temporaryDirectory.resolve("controls-restart.db");
        createAndPause(database);
        resumeAndCancelAfterRestart(database);
        assertCancelledAfterSecondRestart(database);
    }

    private static void createAndPause(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            UUID definitionId = seedDefinition(runtime);
            DistributionCampaign campaign = new DistributionCampaign(
                    CAMPAIGN_ID,
                    "sha256:restart-controls",
                    "restart.yml",
                    "Restart controls",
                    new LoreDefinitionId(definitionId),
                    new TemplateRevision(1L),
                    DistributionCampaignState.DRAFT,
                    CREATED_AT,
                    CREATED_AT,
                    null);
            CampaignRecipient recipient = CampaignRecipient.knownPlayer(
                    CAMPAIGN_ID,
                    0,
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    CREATED_AT);
            new SQLiteDistributionCampaignStartRepository(runtime)
                    .start(new DistributionCampaignStartRequest(
                            campaign, List.of(recipient), "PLAYER", "operator"))
                    .toCompletableFuture().join();
            Instant pausedAt = Instant.ofEpochMilli(20_000L);
            assertTrue(new SQLiteDistributionCampaignControlRepository(runtime)
                    .transitionWithAudit(
                            CAMPAIGN_ID,
                            DistributionCampaignState.ACTIVE,
                            DistributionCampaignState.PAUSED,
                            pausedAt,
                            audit("DISTRIBUTION_CAMPAIGN_PAUSED", pausedAt))
                    .toCompletableFuture().join());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void resumeAndCancelAfterRestart(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            assertEquals(DistributionCampaignState.PAUSED, state(runtime));
            assertTrue(new SQLiteDistributionRecipientRepository(runtime)
                    .claimPending(
                            CAMPAIGN_ID,
                            "paused-worker",
                            Instant.ofEpochMilli(21_000L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture().join().items().isEmpty());

            SQLiteDistributionCampaignControlRepository controls =
                    new SQLiteDistributionCampaignControlRepository(runtime);
            Instant resumedAt = Instant.ofEpochMilli(22_000L);
            assertTrue(controls.transitionWithAudit(
                            CAMPAIGN_ID,
                            DistributionCampaignState.PAUSED,
                            DistributionCampaignState.ACTIVE,
                            resumedAt,
                            audit("DISTRIBUTION_CAMPAIGN_RESUMED", resumedAt))
                    .toCompletableFuture().join());
            Instant cancelledAt = Instant.ofEpochMilli(23_000L);
            assertTrue(controls.cancelWithAudit(
                            CAMPAIGN_ID,
                            DistributionCampaignState.ACTIVE,
                            cancelledAt,
                            "DISTRIBUTION_CAMPAIGN_CANCELLED",
                            "PLAYER",
                            "operator")
                    .toCompletableFuture().join().cancelled());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void assertCancelledAfterSecondRestart(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            assertEquals(DistributionCampaignState.CANCELLED, state(runtime));
            assertEquals(1L, new SQLiteDistributionRecipientRepository(runtime)
                    .countByState(CAMPAIGN_ID).toCompletableFuture().join().cancelled());
            assertTrue(new SQLiteDistributionRecipientRepository(runtime)
                    .claimPending(
                            CAMPAIGN_ID,
                            "cancelled-worker",
                            Instant.ofEpochMilli(24_000L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture().join().items().isEmpty());
            assertEquals(
                    List.of(
                            "DISTRIBUTION_CAMPAIGN_CANCELLED",
                            "DISTRIBUTION_CAMPAIGN_RESUMED",
                            "DISTRIBUTION_CAMPAIGN_PAUSED",
                            "DISTRIBUTION_CAMPAIGN_STARTED"),
                    new SQLiteAuditRepository(runtime)
                            .listByAggregate(
                                    "DISTRIBUTION_CAMPAIGN",
                                    CAMPAIGN_ID.toString(),
                                    PageRequest.first(10))
                            .toCompletableFuture().join().items().stream()
                            .map(AuditEventRecord::eventType)
                            .toList());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static DistributionCampaignState state(SQLiteStorageRuntime runtime) {
        return new SQLiteDistributionCampaignRepository(runtime)
                .findById(CAMPAIGN_ID)
                .toCompletableFuture().join().orElseThrow().state();
    }

    private static AuditEventRecord audit(String type, Instant now) {
        return AuditEventRecord.pending(
                "DISTRIBUTION_CAMPAIGN",
                CAMPAIGN_ID.toString(),
                type,
                "PLAYER",
                "operator",
                "{}",
                now.toEpochMilli());
    }

    private static UUID seedDefinition(SQLiteStorageRuntime runtime) {
        UUID definitionId = UUID.randomUUID();
        runtime.execute(connection -> {
            try (PreparedStatement definition = connection.prepareStatement(
                    "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                            + "current_revision, created_at) VALUES (?, ?, 'Definition', 1, ?)");
                    PreparedStatement revision = connection.prepareStatement(
                            "INSERT INTO lore_definition_revisions(definition_id, revision, "
                                    + "codec_version, template_blob, created_at) VALUES (?, 1, 1, ?, ?)")) {
                definition.setString(1, definitionId.toString());
                definition.setString(2, "definition-" + definitionId);
                definition.setLong(3, CREATED_AT);
                definition.executeUpdate();
                revision.setString(1, definitionId.toString());
                revision.setBytes(2, new byte[] {1});
                revision.setLong(3, CREATED_AT);
                revision.executeUpdate();
            }
            return null;
        }).toCompletableFuture().join();
        return definitionId;
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
}
